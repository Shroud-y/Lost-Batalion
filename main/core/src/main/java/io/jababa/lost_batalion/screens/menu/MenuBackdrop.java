package io.jababa.lost_batalion.screens.menu;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Disposable;

/**
 * Тло головного меню: карта сценарію, що поволі пливе під затемненням.
 *
 * <p>Замість вигаданої ілюстрації тут та сама мапа, на якій іде бій. Так меню з
 * першого кадру каже, про що гра, і не потребує жодного нового ассета.
 *
 * <p>Шар за шаром: карта → холодне затемнення → градієнт ліворуч (щоб текст
 * читався на будь-якій ділянці карти) → віньєтка. Усі три накладки —
 * згенеровані пікcмапи в кілька байтів, розтягнуті на весь екран; окремих
 * файлів вони не потребують.
 *
 * <p>Рух — не декорація: нерухома карта під нерухомим меню виглядає як
 * скріншот. Дрейф повільний і по колу, тож не притягує погляд і ніколи не
 * доходить до краю текстури.
 */
public class MenuBackdrop extends Actor implements Disposable {

    /** Яку частку висоти карти видно. Менше — ближче, більше видно деталей. */
    private static final float VIEW_FRACTION = 0.55f;

    /** Період повного оберту дрейфу, секунди. */
    private static final float DRIFT_PERIOD = 90f;
    /** Розмах дрейфу — частка вільного запасу карти по кожній осі. */
    private static final float DRIFT_AMOUNT = 0.85f;

    /** Множник яскравості самої карти. */
    private static final Color MAP_TINT = new Color(0.62f, 0.62f, 0.62f, 1f);

    /**
     * Холодна заливка поверх карти. Гасить зелень: множення кольору саму по собі
     * лишало б карту яскраво-трав'яною, а меню має читатись як нічна зйомка.
     */
    private static final Color COLD_WASH = new Color(0.043f, 0.055f, 0.075f, 0.55f);

    /** Куди сягає лівий градієнт (частка ширини екрана). */
    private static final float SCRIM_REACH = 0.62f;
    /** Непрозорість градієнта біля лівого краю. */
    private static final float SCRIM_ALPHA = 0.84f;

    private final Texture map;      // не наш — власник передає ззовні
    private final boolean mapOwned; // ...крім випадку, коли ми його й завантажили
    private final Texture wash;
    private final Texture scrim;
    private final Texture vignette;

    private float time;

    /**
     * @param mapPath шлях до карти сценарію; якщо файлу немає, тло лишиться
     *                самими накладками — меню від цього не зламається
     */
    public MenuBackdrop(String mapPath) {
        Texture loaded = null;
        if (Gdx.files.internal(mapPath).exists()) {
            loaded = new Texture(Gdx.files.internal(mapPath));
            loaded.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }
        this.map      = loaded;
        this.mapOwned = loaded != null;

        this.wash     = flat(COLD_WASH);
        this.scrim    = buildScrim();
        this.vignette = buildVignette();
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        time += delta;

        // Розмір беремо зі сцени щокадрово, а не один раз при створенні:
        // setFillParent() є лише в груп, а світ ExtendViewport змінює ширину
        // при кожній зміні пропорцій вікна.
        Stage stage = getStage();
        if (stage != null) setBounds(0f, 0f, stage.getWidth(), stage.getHeight());
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        float x = getX(), y = getY(), w = getWidth(), h = getHeight();
        if (w <= 0f || h <= 0f) return;

        float a = getColor().a * parentAlpha;

        if (map != null) drawMap(batch, x, y, w, h, a);

        batch.setColor(1f, 1f, 1f, a);
        batch.draw(wash,     x, y, w, h);
        batch.draw(scrim,    x, y, w, h);
        batch.draw(vignette, x, y, w, h);
        batch.setColor(Color.WHITE);
    }

    // ── Приватні методи ───────────────────────────────────────────────────

    /**
     * Малює вирізку з карти, розтягнуту на весь екран.
     *
     * <p>Вирізка береться під пропорції екрана, тому карта ніколи не сплющується
     * — при іншому співвідношенні сторін змінюється те, СКІЛЬКИ карти видно, а
     * не її форма.
     */
    private void drawMap(Batch batch, float x, float y, float w, float h, float alpha) {
        int texW = map.getWidth(), texH = map.getHeight();

        int srcH = Math.round(texH * VIEW_FRACTION);
        int srcW = Math.round(srcH * (w / h));
        // Екран може виявитись ширшим за карту — тоді впираємось у ширину і
        // показуємо менше по висоті.
        if (srcW > texW) {
            srcW = texW;
            srcH = Math.round(srcW * (h / w));
        }

        int slackX = texW - srcW, slackY = texH - srcH;
        float phase = MathUtils.PI2 * (time / DRIFT_PERIOD);

        // Коло, а не маятник: у маятника є дві точки зупинки, і на них рух стає
        // помітним саме тим, що припиняється.
        int srcX = Math.round(slackX * (0.5f + 0.5f * DRIFT_AMOUNT * MathUtils.cos(phase)));
        int srcY = Math.round(slackY * (0.5f + 0.5f * DRIFT_AMOUNT * MathUtils.sin(phase)));

        batch.setColor(MAP_TINT.r, MAP_TINT.g, MAP_TINT.b, alpha);
        batch.draw(map, x, y, w, h, srcX, srcY, srcW, srcH, false, false);
    }

    /** Градієнт зліва направо — підкладка під колонку меню. */
    private static Texture buildScrim() {
        final int n = 256;
        Pixmap pm = new Pixmap(n, 1, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);

        for (int i = 0; i < n; i++) {
            float t = (i / (float) (n - 1)) / SCRIM_REACH;
            // Показник > 1 тримає ліву частину щільною і збирає весь спад у
            // хвіст: лінійний градієнт помітно сірив би центр екрана.
            float a = t >= 1f ? 0f : SCRIM_ALPHA * (float) Math.pow(1f - t, 1.35);
            pm.setColor(0.024f, 0.031f, 0.043f, a);
            pm.drawPixel(i, 0);
        }
        return finish(pm);
    }

    /** Віньєтка. Квадратна заготовка розтягується в овал за пропорціями екрана. */
    private static Texture buildVignette() {
        final int n = 128;
        Pixmap pm = new Pixmap(n, n, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);

        final float diagonal = (float) Math.sqrt(2.0);   // від центру до кута

        float c = (n - 1) / 2f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float dx = (x - c) / c, dy = (y - c) / c;
                float d  = (float) Math.sqrt(dx * dx + dy * dy) / diagonal;

                // Центр лишається чистим, темніє лише зовнішня третина.
                float t = MathUtils.clamp((d - 0.55f) / 0.45f, 0f, 1f);
                pm.setColor(0.016f, 0.024f, 0.031f, 0.80f * t * t);
                pm.drawPixel(x, y);
            }
        }
        return finish(pm);
    }

    private static Texture flat(Color color) {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(color);
        pm.fill();
        return finish(pm);
    }

    private static Texture finish(Pixmap pm) {
        Texture tex = new Texture(pm);
        // Linear: усі три накладки — плавні переходи, розтягнуті в десятки разів.
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        // ClampToEdge, бо інакше на краях градієнта проступає протилежний бік.
        tex.setWrap(Texture.TextureWrap.ClampToEdge, Texture.TextureWrap.ClampToEdge);
        pm.dispose();
        return tex;
    }

    @Override
    public void dispose() {
        if (mapOwned && map != null) map.dispose();
        wash.dispose();
        scrim.dispose();
        vignette.dispose();
    }
}
