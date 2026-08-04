package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.capture.CaptureManager;
import io.jababa.lost_batalion.capture.CapturePoint;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.ui.UIScale;

/**
 * Позначки точок захоплення: плашка з літерою на полі й на мінікарті.
 *
 * <h3>Навіщо</h3>
 * Літери точок з'явились спершу лише в HUD угорі, а зони на землі лишались
 * безіменними. Виходило, що гравець бачить «B червона», але не знає, ЯКА з плям
 * на полі — це B. Тут ця прив'язка й замикається: та сама літера, той самий
 * колір і те саме мигання стоять просто над зоною.
 *
 * <h3>Чому екранні координати, а не світові</h3>
 * Підпис малюється не в світі, а на екрані, у точці, куди спроєктувався центр
 * зони. Світовий текст масштабувався б разом із камерою: на віддаленій він
 * ставав би нечитабельним саме тоді, коли потрібен найбільше — коли гравець
 * дивиться на всю карту й вирішує, куди йти. Тут розмір сталий при будь-якому
 * зумі.
 *
 * <p>Координати ЛОГІЧНІ (піксель, поділений на {@link UIScale}) — ті самі, у
 * яких живе решта HUD, тож позначка росте разом з інтерфейсом.
 *
 * <p>Чистий рендер: стан точок належить {@link CaptureManager}, тут він лише
 * читається.
 */
public class CaptureMarkerRenderer {

    // ── Розміри плашки в логічних одиницях ────────────────────────────────
    private static final float BADGE_W   = 26f;
    private static final float BADGE_H   = 22f;
    /** Товщина рамки. */
    private static final float EDGE      = 1.5f;
    /** Висота смужки прогресу під літерою. */
    private static final float BAR_H     = 4f;
    /** Відступ смужки від країв плашки. */
    private static final float BAR_INSET = 3f;

    /**
     * Наскільки плашка піднята над центром зони.
     *
     * <p>Не по центру: там стоять хати, дороги й самі юніти, і підпис лягав би
     * просто на них. Трохи вище — і зв'язок із зоною лишається очевидним, і
     * нічого не затуляється.
     */
    private static final float LIFT = 18f;

    /** Запас за краєм екрана, поза яким позначку вже не малюємо. */
    private static final float CULL_MARGIN = 40f;

    /** Наскільки гасне позначка в нижній точці мигання. */
    private static final float BLINK_DEPTH = 0.55f;

    // ── Мінікарта ─────────────────────────────────────────────────────────
    /** Півсторони ромба точки на мінікарті, у логічних одиницях. */
    private static final float MINI_R = 4f;

    /** Час для мигання. Візуал, до симуляції не належить. */
    private float time;

    /**
     * Шрифт підписів. Лінивий, бо {@code UIFactory.disposeAll()} знищує всі
     * шрифти при перескладанні HUD — тоді {@link #invalidateFont()} обнуляє
     * посилання, і наступний кадр бере свіжий.
     */
    private BitmapFont font;

    private final GlyphLayout layout = new GlyphLayout();
    /** Буфер проєкції. Поле, а не локальна змінна — рендер щокадровий. */
    private final Vector3 projected = new Vector3();

    public void update(float delta) { time += delta; }

    /** Шрифти знищено разом із HUD — узяти новий наступного кадру. */
    public void invalidateFont() { font = null; }

    private BitmapFont font() {
        if (font == null) font = UIFactory.createMapLabelFont();
        return font;
    }

    /**
     * Плашки над зонами на полі.
     *
     * <p>{@code batch} і {@code shapes} мають бути закриті: метод сам їх
     * відкриває і ставить ЕКРАННУ матрицю.
     *
     * @param viewer сторона, чиїми очима дивимось — від неї залежить, який
     *               колір «свій», а який «ворожий»
     */
    public void draw(SpriteBatch batch, ShapeRenderer shapes, OrthographicCamera camera,
                     CaptureManager points, Team viewer, float logicalW, float logicalH) {
        if (points == null || camera == null) return;
        Array<CapturePoint> all = points.getPoints();
        if (all.size == 0) return;

        float blink = blink();

        for (int i = 0; i < all.size; i++) {
            CapturePoint p = all.get(i);

            // Світова точка → пікселі екрана (нуль унизу зліва) → логічні
            // одиниці HUD. Обидва перетворення обов'язкові: camera.project
            // нічого не знає про масштаб інтерфейсу.
            projected.set(Fixed.toFloat(p.x), Fixed.toFloat(p.y), 0f);
            camera.project(projected);
            float sx = UIScale.toLogical(projected.x);
            float sy = UIScale.toLogical(projected.y) + LIFT;

            if (sx < -CULL_MARGIN || sx > logicalW + CULL_MARGIN
             || sy < -CULL_MARGIN || sy > logicalH + CULL_MARGIN) continue;

            drawBadge(batch, shapes, p, sx, sy, viewer, blink, logicalW, logicalH);
        }
    }

    private void drawBadge(SpriteBatch batch, ShapeRenderer shapes, CapturePoint p,
                           float cx, float cy, Team viewer, float blink,
                           float logicalW, float logicalH) {
        Color side  = sideColor(p, viewer);
        boolean changing = isChanging(p);
        float   a     = changing ? blink : 1f;

        boolean bar = changing;               // смужка лише поки точку тягнуть
        float   h   = BADGE_H + (bar ? BAR_H + BAR_INSET : 0f);
        float   x   = cx - BADGE_W / 2f;
        float   y   = cy - h / 2f;

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        // Підкладка — та сама, що в решти HUD: карта світла й ряба, і голий
        // текст на ній губиться (це вже підтвердилось на рахунку й на панелі
        // економіки).
        Color bg = UIFactory.COLOR_HUD_PANEL;
        shapes.setColor(bg.r, bg.g, bg.b, bg.a * a);
        shapes.rect(x, y, BADGE_W, h);

        // Рамка кольором сторони — вона ж головний сигнал «чиє це».
        shapes.setColor(side.r, side.g, side.b, a);
        shapes.rect(x,                 y,                  BADGE_W, EDGE);
        shapes.rect(x,                 y + h - EDGE,       BADGE_W, EDGE);
        shapes.rect(x,                 y,                  EDGE,    h);
        shapes.rect(x + BADGE_W - EDGE, y,                 EDGE,    h);

        if (bar) {
            float bx = x + BAR_INSET;
            float by = y + BAR_INSET;
            float bw = BADGE_W - BAR_INSET * 2f;

            // Жолоб, щоб було видно не лише набране, а й скільки лишилось.
            shapes.setColor(0f, 0f, 0f, 0.45f * a);
            shapes.rect(bx, by, bw, BAR_H);

            shapes.setColor(side.r, side.g, side.b, a);
            shapes.rect(bx, by, bw * MathUtils.clamp(p.fill(), 0f, 1f), BAR_H);
        }
        shapes.end();

        // Літера — тим самим кольором, що й рамка.
        //
        // Колір ставиться ДО setText, і це не стиль: GlyphLayout запікає колір
        // шрифту в момент розкладки, а не малювання. З оберненим порядком кожна
        // літера виходила кольором ПОПЕРЕДНЬОЇ точки — один спільний layout на
        // всі позначки, тож помилка зсувалась по колу й ловилась лише оком.
        BitmapFont f = font();
        f.setColor(side.r, side.g, side.b, a);
        layout.setText(f, p.name);

        batch.begin();
        // Літера центрується у ВЕРХНІЙ частині плашки, тобто над смужкою:
        // інакше поява смужки зсувала б текст, і позначка сіпалась би рівно
        // в момент, коли на неї дивляться.
        f.draw(batch, layout,
               cx - layout.width / 2f,
               y + h - (BADGE_H - layout.height) / 2f);
        batch.end();
        f.setColor(Color.WHITE);
    }

    /**
     * Ті самі позначки на мінікарті: ромб кольором власника і літера поруч.
     *
     * <p>Живе тут, а не в {@code Minimap}, щоб усе про позначки точок лежало в
     * одному місці — колір, мигання й літера мусять збігатися на полі, на
     * мінікарті й у HUD, а рознесені по класах вони розійдуться.
     *
     * @param mx,my,mw,mh рамка мінікарти в логічних одиницях
     */
    public void drawOnMinimap(SpriteBatch batch, ShapeRenderer shapes,
                              CaptureManager points, Team viewer,
                              float mx, float my, float mw, float mh,
                              float mapW, float mapH) {
        if (points == null || mapW <= 0f || mapH <= 0f) return;
        Array<CapturePoint> all = points.getPoints();
        if (all.size == 0) return;

        float blink = blink();
        BitmapFont f = font();

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < all.size; i++) {
            CapturePoint p = all.get(i);
            Color side = sideColor(p, viewer);
            float a = isChanging(p) ? blink : 1f;

            float px = mx + clamp01(Fixed.toFloat(p.x) / mapW) * mw;
            float py = my + clamp01(Fixed.toFloat(p.y) / mapH) * mh;

            // Ромб, а не коло: юніти на мінікарті вже кружечки, і точку треба
            // відрізняти від них формою, а не самим лише розміром.
            shapes.setColor(0f, 0f, 0f, 0.55f * a);
            diamond(shapes, px, py, MINI_R + 1.5f);
            shapes.setColor(side.r, side.g, side.b, a);
            diamond(shapes, px, py, MINI_R);
        }
        shapes.end();

        batch.begin();
        for (int i = 0; i < all.size; i++) {
            CapturePoint p = all.get(i);
            Color side = sideColor(p, viewer);
            float a = isChanging(p) ? blink : 1f;

            float px = mx + clamp01(Fixed.toFloat(p.x) / mapW) * mw;
            float py = my + clamp01(Fixed.toFloat(p.y) / mapH) * mh;

            // Колір — ДО розкладки (див. drawBadge): layout запікає його в себе.
            f.setColor(side.r, side.g, side.b, a);
            layout.setText(f, p.name);

            // Праворуч-угору від ромба, щоб не накрити саму позначку. Але якщо
            // точка стоїть біля правого краю карти, підпис вилазив би за
            // мінікарту на чорне поле — тоді він переходить ліворуч.
            float lx = px + MINI_R + 2f;
            if (lx + layout.width > mx + mw) lx = px - MINI_R - 2f - layout.width;

            f.draw(batch, layout, lx, py + layout.height / 2f + MINI_R);
        }
        batch.end();
        f.setColor(Color.WHITE);
    }

    // ── Спільне ───────────────────────────────────────────────────────────

    /** 1 у піку, {@code 1 − BLINK_DEPTH} у западині. */
    private float blink() {
        return 1f - BLINK_DEPTH * (0.5f - 0.5f
             * MathUtils.cos(time * MathUtils.PI2 / CapturePointRenderer.BLINK_PERIOD));
    }

    /**
     * Точку зараз перетягують: прогрес рушив, але ще не дійшов кінця. Захоплена
     * й нікому не потрібна точка не мигає — інакше блимало б усе одразу і
     * мигання перестало б щось означати.
     */
    private static boolean isChanging(CapturePoint p) {
        return p.progress > 0 && p.progress < CaptureManager.FULL;
    }

    /**
     * Колір ВІДНОСНИЙ: синє — моє, червоне — чуже. Абсолютні кольори команд
     * означали б, що половина гравців бачить свою армію червоною.
     *
     * <p>Береться той, хто ТЯГНЕ, а не власник, — так само, як у зони на землі:
     * поки чужу точку зривають, важливо саме те, чий прогрес іде.
     */
    private static Color sideColor(CapturePoint p, Team viewer) {
        Team side = p.holder != null ? p.holder : p.owner;
        if (side == null)   return UIFactory.COLOR_TEAM_NEUTRAL;
        return side == viewer ? UIFactory.COLOR_TEAM_SELF : UIFactory.COLOR_TEAM_FOE;
    }

    private static void diamond(ShapeRenderer shapes, float cx, float cy, float r) {
        shapes.triangle(cx, cy + r, cx - r, cy, cx + r, cy);
        shapes.triangle(cx, cy - r, cx - r, cy, cx + r, cy);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
