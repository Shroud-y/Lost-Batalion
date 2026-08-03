package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.units.Unit;

/**
 * Білі кільця дальності огляду, поки затиснуто ALT.
 *
 * <p>Центр — КУРСОР, не юніт. Питання, на яке відповідає кільце: «якби ось цей
 * вибраний юніт стояв тут, як далеко і куди він бачив би». Тому й радіус
 * рахується для точки під курсором: дальність залежить від місцевості
 * спостерігача (ліс ×0.55, низина ×0.40, височина ×1.30), і кільце, що несло б
 * із собою множник із того місця, де юніт стоїть зараз, обіцяло б дальність,
 * якої на новому місці не буде.
 *
 * <p>Кільце РОЗІРВАНЕ там, куди промінь не доходить: ліс, гребінь, край карти.
 * Дуга є рівно в тих напрямках, де огляд справді дістає на повну дальність.
 *
 * <p>Однакові радіуси зливаються в одне кільце — виділивши роту піхоти, гравець
 * отримує одне коло, а не дванадцять однакових одне на одному. Різні типи
 * (піхота 520, артилерія 500) дають різні кільця.
 *
 * <p>Разом із коричневим віялом {@link FogOfWarRenderer#renderCursorSightOverlay}
 * це одна картинка з одного центру: віяло показує ФОРМУ («куди звідси взагалі
 * можна дивитись», без будь-якої дальності), кільце — МЕЖУ («і докуди»).
 *
 * <p><b>Це не дальність ВИЯВЛЕННЯ.</b> {@link VisibilitySystem} додає до цього
 * стелс цілі, і в глибокому лісі ворога помітно аж на 0.1×R.
 *
 * <p>Чистий рендер: нічого тут не входить у симуляцію і в checksum.
 */
public class SightRingRenderer {

    /** Колір кільця. Біле — свідомо нейтральне, щоб не плутати з віялом. */
    private static final float RING_R = 1f, RING_G = 1f, RING_B = 1f;
    private static final float RING_ALPHA = 0.85f;
    /**
     * Товщина в ЕКРАННИХ пікселях; у світові одиниці переводиться за поточним
     * зумом, як і межа віяла — інакше кільце товщало б, коли камеру віддаляють.
     */
    private static final float RING_WIDTH_PX = 2f;

    /**
     * Променів на кільце. Рівномірно, без адаптивного згущення: на відміну від
     * віяла тут не треба ловити силует — потрібне лише «дійшов / не дійшов»,
     * а краї розривів уточнює бісекція нижче. На типовому R ≈ 520 це хорда
     * ~9 світових одиниць, тобто дуга читається як дуга, а не як многокутник.
     */
    private static final int   RING_RAYS = 360;
    /**
     * Скільки разів ділити навпіл кут між чистим і перекритим променем. 5 рівнів
     * ріжуть базовий крок 1° до ~0.03° — далі різниця вже менша за товщину лінії.
     */
    private static final int   EDGE_BISECT_DEPTH = 5;
    /**
     * Наскільки близько до R має дійти промінь, щоб рахуватись чистим. Проміння
     * зупиняється рівно на {@code min(вихід з карти, R)}, тож «чистий» — це
     * точна рівність; допуск тут лише проти похибки float на масштабі карти.
     */
    private static final float REACH_EPS = 0.05f;

    /**
     * Зсув курсора (світові одиниці), після якого кільця перебудовуються. Те саме
     * число, що й у кеші віяла: обидва оверлеї центровані на курсорі й мають
     * оновлюватись разом, інакше межа й форма розходились би на кадр.
     */
    private static final float CURSOR_CACHE_EPS = 2f;
    /**
     * Різниця радіусів, за якої два кільця вважаються одним. Менша за товщину
     * лінії — злиті кільця й так лягли б одне на одне.
     */
    private static final float RADIUS_MERGE_EPS = 1f;
    /**
     * Скільки різних радіусів узагалі малювати. Практично їх один-два (типів
     * юнітів стільки ж); стеля існує лише щоб чудернацький вибір не з'їв кадр.
     */
    private static final int   MAX_RINGS = 4;

    private final SightCaster caster;

    /** Пораховані кільця для поточної позиції курсора. */
    private final Array<Ring> rings = new Array<>(MAX_RINGS);
    /** Радіуси, зібрані з виділення в цьому кадрі, до злиття однакових. */
    private final FloatArray radii = new FloatArray(MAX_RINGS);

    private boolean cacheValid = false;
    private float   cacheX = 0f, cacheY = 0f;

    private Team viewer = Team.PLAYER;

    /** Скретч під один перерахунок: «дійшов / не дійшов» по кожному променю. */
    private final boolean[] clear = new boolean[RING_RAYS];

    /** Одне кільце: радіус плюс маска розривів на ньому. */
    private static final class Ring {
        float radius;
        /** Пари (початок, кінець) ЧИСТИХ секторів, радіани, зростаючі. */
        final FloatArray arcs = new FloatArray(32);
    }

    public SightRingRenderer(float mapWidth, float mapHeight, TerrainQuery terrain) {
        this.caster = new SightCaster(mapWidth, mapHeight, terrain);
    }

    /** Сторона, чиї кільця малюються. Дзеркалить {@code FogOfWarRenderer.setViewer}. */
    public void setViewer(Team viewer) { this.viewer = viewer; }

    /**
     * Малює кільця навколо курсора для радіусів вибраних юнітів. Викликати у
     * СВІТОВИХ координатах ({@code shapes.setProjectionMatrix(camera.combined)}),
     * після туману й після курсорного віяла; {@code shapes} не має бути в
     * {@code begin()}.
     *
     * <p>Трафарет тут не потрібен — кільце це лінія, а не заливка, — але виклик
     * стоїть після віяла, яке трафарет вимикає за собою.
     */
    public void render(ShapeRenderer shapes, OrthographicCamera camera,
                       Array<Unit> selected, float cursorX, float cursorY) {
        if (selected == null || selected.size == 0) return;

        collectRadii(selected, cursorX, cursorY);
        if (radii.size == 0) return;

        updateCache(cursorX, cursorY);
        if (rings.size == 0) return;

        float width = RING_WIDTH_PX * FogOfWarRenderer.worldPerPixel(camera);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(RING_R, RING_G, RING_B, RING_ALPHA);
        for (int i = 0; i < rings.size; i++) drawRing(shapes, rings.get(i), cacheX, cacheY, width);
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    /**
     * Збирає РІЗНІ радіуси вибраних юнітів для точки під курсором. Однакові
     * зливаються: рота піхоти — це одне коло, а не дванадцять на одному місці.
     */
    private void collectRadii(Array<Unit> selected, float cursorX, float cursorY) {
        radii.clear();
        for (int i = 0; i < selected.size && radii.size < MAX_RINGS; i++) {
            Unit u = selected.get(i);
            if (u == null || !u.alive || u.team != viewer) continue;

            float r = caster.effectiveSightAt(u, cursorX, cursorY);
            if (r <= 0f) continue;

            boolean dup = false;
            for (int j = 0; j < radii.size; j++)
                if (Math.abs(radii.get(j) - r) <= RADIUS_MERGE_EPS) { dup = true; break; }
            if (!dup) radii.add(r);
        }
    }

    /**
     * Перебудовує кільця, якщо курсор зсунувся або набір радіусів змінився.
     *
     * <p>На відміну від першої версії тут немає бюджету перебудов на кадр: центр
     * один і той самий для всіх кілець, різних радіусів практично один-два, і
     * перерахунок коштує ~380 променів на кільце проти ~1042 у віяла, яке й так
     * оновлюється на цьому ж русі курсора.
     */
    private void updateCache(float cursorX, float cursorY) {
        boolean moved = !cacheValid
            || (cursorX - cacheX) * (cursorX - cacheX)
             + (cursorY - cacheY) * (cursorY - cacheY) > CURSOR_CACHE_EPS * CURSOR_CACHE_EPS;

        if (!moved && sameRadii()) return;

        while (rings.size < radii.size) rings.add(new Ring());
        rings.truncate(radii.size);

        float hOrigin = caster.heightAt(cursorX, cursorY);
        for (int i = 0; i < radii.size; i++) {
            Ring ring = rings.get(i);
            ring.radius = radii.get(i);
            build(ring, cursorX, cursorY, hOrigin);
        }

        cacheX     = cursorX;
        cacheY     = cursorY;
        cacheValid = true;
    }

    /** Чи збігається кешований набір радіусів із зібраним цього кадру. */
    private boolean sameRadii() {
        if (rings.size != radii.size) return false;
        for (int i = 0; i < radii.size; i++)
            if (Math.abs(rings.get(i).radius - radii.get(i)) > RADIUS_MERGE_EPS) return false;
        return true;
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    /**
     * Рахує маску розривів: {@link #RING_RAYS} рівномірних променів, обмежених
     * радіусом кільця, і з них — чисті кутові інтервали.
     *
     * <p>Промінь вважається чистим, коли дійшов до самого радіуса. Зупинка на
     * краю карти рахується розривом навмисно: дуга там лягла б за межі карти,
     * тобто малювати її нема де й нема сенсу.
     */
    private void build(Ring ring, float cx, float cy, float hOrigin) {
        final float radius = ring.radius;
        final float step = (float) (2.0 * Math.PI / RING_RAYS);

        int clearCount = 0;
        for (int i = 0; i < RING_RAYS; i++) {
            clear[i] = reaches(cx, cy, hOrigin, i * step, radius);
            if (clear[i]) clearCount++;
        }

        ring.arcs.clear();
        if (clearCount == 0) return;                       // усе перекрито
        if (clearCount == RING_RAYS) {                     // суцільне коло
            ring.arcs.add(0f);
            ring.arcs.add((float) (2.0 * Math.PI));
            return;
        }

        // Стартуємо з першого променя, перед яким стоїть перекритий, — тоді обхід
        // ніколи не розрізає чистий сектор навпіл на стику 0/2π.
        int start = -1;
        for (int i = 0; i < RING_RAYS; i++) {
            if (clear[i] && !clear[(i + RING_RAYS - 1) % RING_RAYS]) { start = i; break; }
        }
        if (start < 0) return;   // недосяжно: змішаний випадок завжди має перехід

        // Кути НЕ згортаються по модулю: індекс обертається, а кут росте далі за
        // 2π, тож інтервал, що перетинає нуль, лишається одним відрізком.
        int k = 0;
        while (k < RING_RAYS) {
            int idx = (start + k) % RING_RAYS;
            if (!clear[idx]) { k++; continue; }

            float aFirst = (start + k) * step;
            // Точний початок: між останнім перекритим і першим чистим.
            float a0 = bisectEdge(cx, cy, hOrigin, radius, aFirst - step, aFirst);

            int run = 0;
            while (k + run < RING_RAYS && clear[(start + k + run) % RING_RAYS]) run++;

            float aLast = (start + k + run - 1) * step;
            // Точний кінець: між останнім чистим і першим перекритим після нього.
            float a1 = bisectEdge(cx, cy, hOrigin, radius, aLast + step, aLast);

            ring.arcs.add(a0);
            ring.arcs.add(a1);
            k += run;
        }
    }

    /** Чи дістає промінь під цим кутом до самого радіуса. */
    private boolean reaches(float cx, float cy, float hOrigin, float angle, float radius) {
        float d = caster.castRay(cx, cy, hOrigin,
                                 (float) Math.cos(angle), (float) Math.sin(angle), radius);
        return d >= radius - REACH_EPS;
    }

    /**
     * Уточнює кут переходу між перекритим і чистим напрямком.
     *
     * <p>Повертає ЧИСТИЙ бік: межа зсувається тільки всередину дуги, тож кільце
     * ніколи не малюється там, де промінь не перевірений як чистий. Розрив від
     * цього ширший щонайбільше на крок бісекції, а це дешевша помилка, ніж біла
     * дуга поперек лісу.
     */
    private float bisectEdge(float cx, float cy, float hOrigin, float radius,
                             float aBlocked, float aClear) {
        for (int i = 0; i < EDGE_BISECT_DEPTH; i++) {
            float am = 0.5f * (aBlocked + aClear);
            if (reaches(cx, cy, hOrigin, am, radius)) aClear = am;
            else                                      aBlocked = am;
        }
        return aClear;
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    /**
     * Малює чисті дуги одного кільця.
     *
     * <p>Центр береться з КЕШУ, а не з живого курсора: поки курсор не вийшов за
     * {@link #CURSOR_CACHE_EPS}, дуги належать саме кешованій точці, і мішати
     * одне з одним означало б перекосити всі дуги. Так само робить віяло.
     */
    private void drawRing(ShapeRenderer shapes, Ring ring, float cx, float cy, float width) {
        final float step = (float) (2.0 * Math.PI / RING_RAYS);
        final float half = width * 0.5f;
        final float radius = ring.radius;

        for (int i = 0; i + 1 < ring.arcs.size; i += 2) {
            float a0 = ring.arcs.get(i);
            float a1 = ring.arcs.get(i + 1);
            if (a1 <= a0) continue;

            int segments = Math.max(1, (int) Math.ceil((a1 - a0) / step));
            float prevX = cx + (float) Math.cos(a0) * radius;
            float prevY = cy + (float) Math.sin(a0) * radius;
            // Крапка на початку дуги: rectLine — це квад, тож на стиках і на
            // кінцях лишалися б зрізи.
            shapes.circle(prevX, prevY, half, 8);

            for (int s = 1; s <= segments; s++) {
                float a = a0 + (a1 - a0) * s / segments;
                float x = cx + (float) Math.cos(a) * radius;
                float y = cy + (float) Math.sin(a) * radius;
                shapes.rectLine(prevX, prevY, x, y, width);
                shapes.circle(x, y, half, 8);
                prevX = x;
                prevY = y;
            }
        }
    }
}
