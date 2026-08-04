package io.jababa.lost_batalion.screens.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Disposable;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.ui.UIScale;
import io.jababa.lost_batalion.units.Artillery;
import io.jababa.lost_batalion.units.Unit;

/**
 * Мінікарта в правому нижньому куті.
 *
 * <p>Показує всю карту цілком і білою рамкою — ділянку, яку зараз видно на
 * екрані. Клік (або протяг) по мінікарті переносить камеру в те місце.
 *
 * <p>Координати тут ЛОГІЧНІ (піксель, поділений на {@link UIScale}), з нулем
 * унизу зліва. Обробник вводу отримує від libGDX ФІЗИЧНІ пікселі з нулем
 * угорі, тому їх треба проганяти через {@link UIScale#inputXToLogical} і
 * {@link UIScale#inputYToLogical} — обидва перетворення разом, інакше влучання
 * розійдеться з намальованим.
 *
 * <p>Текстура карти НЕ належить мінікарті — вона приходить з екрану гри й
 * там же звільняється. Своя тут лише запасна однопіксельна заливка на випадок,
 * коли карта не завантажилась.
 */
public class Minimap implements Disposable {

    /**
     * Найбільший розмір рамки в ЛОГІЧНИХ одиницях; пропорції карти зберігаються.
     *
     * <p>Логічних, а не піксельних: розміри тут ділить на себе {@link UIScale},
     * тому в повний екран мінікарта росте разом із рештою HUD, а не лишається
     * поштовою маркою в кутку.
     */
    private static final float MAX_W  = 320f;
    private static final float MAX_H  = 230f;
    /**
     * Стеля відносно вікна. На вузькому вікні 320 логічних одиниць — це вже
     * третина ширини, і мінікарта наїжджає на панель виділення. Частка тримає
     * її в межах кутка на будь-якому розмірі.
     */
    private static final float MAX_W_FRACTION = 0.26f;
    private static final float MAX_H_FRACTION = 0.30f;
    /**
     * Відступ від краю вікна. Нуль: мінікарта лягає впритул у куток —
     * вільна смужка між нею і краєм екрана виглядала як недомальований UI.
     */
    private static final float MARGIN = 0f;
    /** Товщина темної підкладки навколо карти. */
    private static final float PAD    = 3f;
    /** Радіуси позначок юнітів у пікселях екрана. */
    private static final float UNIT_DOT = 2f;
    private static final float ART_DOT  = 3.2f;

    private final Texture mapTexture;
    private final float   mapWidth, mapHeight;

    /** Запасна заливка, якщо текстури карти немає. */
    private Texture fallback;

    /** Рамка карти на екрані: лівий нижній кут і розмір. */
    private float x, y, w, h;

    private final Matrix4 screenProj = new Matrix4();

    public Minimap(Texture mapTexture, float mapWidth, float mapHeight) {
        this.mapTexture = mapTexture;
        this.mapWidth   = mapWidth  > 0f ? mapWidth  : 1f;
        this.mapHeight  = mapHeight > 0f ? mapHeight : 1f;

        if (mapTexture == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(0.12f, 0.14f, 0.12f, 1f);
            pm.fill();
            fallback = new Texture(pm);
            pm.dispose();
        }
    }

    /**
     * Перерахувати місце рамки під поточний розмір вікна.
     *
     * @param logicalW ширина HUD у ЛОГІЧНИХ одиницях ({@link UIScale#logicalWidth()}),
     *                 не в пікселях кадру
     * @param logicalH висота HUD у логічних одиницях
     */
    public void layout(float logicalW, float logicalH) {
        float capW = Math.min(MAX_W, logicalW * MAX_W_FRACTION);
        float capH = Math.min(MAX_H, logicalH * MAX_H_FRACTION);

        // Пропорції карти зберігаються: інакше рамка видимої ділянки на
        // мінікарті не збігалася б з тим, що насправді видно.
        float scale = Math.min(capW / mapWidth, capH / mapHeight);
        w = mapWidth  * scale;
        h = mapHeight * scale;
        x = logicalW - w - MARGIN;
        y = MARGIN;
    }

    /**
     * Ширина зайнятого мінікартою кутка разом із підкладкою, у логічних
     * одиницях. Панель виділення впирається в це число, щоб не залізти під неї.
     */
    public float frameWidth() { return w + PAD * 2f; }

    // Рамка карти в логічних одиницях. Потрібна тому, хто малює поверх
    // мінікарти власні позначки — зараз це {@code CaptureMarkerRenderer}:
    // позначки точок мусять збігатися кольором і миганням із тими, що на полі,
    // тож малює їх один клас, а мінікарта лише каже, де вона лежить.
    public float mapX() { return x; }
    public float mapY() { return y; }
    public float mapW() { return w; }
    public float mapH() { return h; }

    /** Чи потрапляє екранна точка (нуль унизу) в мінікарту. */
    public boolean containsScreenPoint(float sx, float syFromBottom) {
        return sx >= x - PAD && sx <= x + w + PAD
            && syFromBottom >= y - PAD && syFromBottom <= y + h + PAD;
    }

    /**
     * Світова точка під екранною. Викликати лише після
     * {@link #containsScreenPoint}; за межами карти результат обрізається до
     * її країв, щоб протяг за рамку не викидав камеру в порожнечу.
     */
    public void worldAt(float sx, float syFromBottom, Vector2 out) {
        float u = clamp01((sx - x) / w);
        float v = clamp01((syFromBottom - y) / h);
        out.set(u * mapWidth, v * mapHeight);
    }

    /**
     * Намалювати мінікарту.
     *
     * <p>{@code batch} і {@code shapes} мають бути закриті: метод сам відкриває
     * їх і ставить ЕКРАННУ матрицю. Назад він її не повертає — мінікарта
     * малюється останньою зі світової графіки, і наступний, хто візьме ці
     * об'єкти, все одно виставляє свою матрицю сам.
     */
    public void draw(SpriteBatch batch, ShapeRenderer shapes, OrthographicCamera camera,
                     Iterable<Unit> units, Team viewer, float logicalW, float logicalH) {
        layout(logicalW, logicalH);
        screenProj.setToOrtho2D(0, 0, logicalW, logicalH);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Підкладка — щоб карта не зливалась із тим, що під нею. Виступає
        // лише всередину екрана: із зовнішнього боку її край і так за межами
        // вікна.
        shapes.setProjectionMatrix(screenProj);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(UIFactory.COLOR_HUD_PANEL);
        shapes.rect(x - PAD, y - PAD, w + PAD * 2f, h + PAD * 2f);
        shapes.end();

        batch.setProjectionMatrix(screenProj);
        batch.begin();
        batch.setColor(1f, 1f, 1f, 0.92f);
        batch.draw(mapTexture != null ? mapTexture : fallback, x, y, w, h);
        batch.setColor(1f, 1f, 1f, 1f);
        batch.end();

        // Юніти: свої завжди, чужі — лише поки їх видно. Мінікарта не має
        // права показувати те, чого не показує сама карта, інакше вона стає
        // обходом туману війни.
        if (units != null) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            for (Unit u : units) {
                if (u == null || !u.alive) continue;
                boolean own = u.team == viewer;
                if (!own && !u.isVisibleTo(viewer)) continue;

                if (own) shapes.setColor(0.35f, 0.80f, 1f, 0.95f);
                else     shapes.setColor(1f, 0.30f, 0.25f, 0.95f);

                // Артилерія помітно більша за піхоту — на мінікарті це єдина
                // різниця між ними, і вона того варта: саме гармати вирішують,
                // куди дивитись.
                float r = (u instanceof Artillery ? ART_DOT : UNIT_DOT);
                shapes.circle(x + clamp01(u.worldX() / mapWidth)  * w,
                              y + clamp01(u.worldY() / mapHeight) * h,
                              r, 8);
            }
            shapes.end();
        }

        shapes.begin(ShapeRenderer.ShapeType.Line);
        // Рамка мінікарти — лише з боку екрана: праворуч і знизу вона впритул
        // до країв вікна, і замкнений прямокутник там просто не було б видно.
        shapes.setColor(UIFactory.COLOR_HUD_EDGE);
        shapes.line(x - PAD, y - PAD, x - PAD, y + h);
        shapes.line(x - PAD, y + h, x + w, y + h);

        // Біла рамка видимої ділянки. Свідомий виняток із палітри: акцентне
        // золото зливалося з жовтуватими ділянками карти, а ця рамка постійно
        // рухається і має читатись на будь-якому тлі під нею.
        //
        // Камера може виходити за край карти — тоді прямокутник обрізається по
        // мінікарті, а не малюється поза нею.
        if (camera != null) {
            float halfW = camera.viewportWidth  * camera.zoom / 2f;
            float halfH = camera.viewportHeight * camera.zoom / 2f;

            float l = clamp01((camera.position.x - halfW) / mapWidth);
            float r = clamp01((camera.position.x + halfW) / mapWidth);
            float b = clamp01((camera.position.y - halfH) / mapHeight);
            float t = clamp01((camera.position.y + halfH) / mapHeight);

            shapes.setColor(1f, 1f, 1f, 0.95f);
            shapes.rect(x + l * w, y + b * h, (r - l) * w, (t - b) * h);
        }
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    @Override
    public void dispose() {
        if (fallback != null) { fallback.dispose(); fallback = null; }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
