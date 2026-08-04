package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.capture.CaptureManager;
import io.jababa.lost_batalion.capture.CapturePoint;
import io.jababa.lost_batalion.math.Fixed;

/**
 * Зони стратегічних точок: чотирикутник на землі, колір сторони — власник.
 *
 * <h3>Що тут змінилось і чому</h3>
 * Було коло сталого радіуса, намальоване ВСЕРЕДИНІ {@code BloomEffect} із
 * сектором-шкалою, що заповнювався за годинниковою. На екрані це давало
 * найяскравіший об'єкт кадру вдесятеро більший за юніта — мітка читалась як
 * баг рендера, а не як село.
 *
 * <p>Тепер: форма береться з зони сценарію, світіння прибране (звичайний
 * альфа-блендинг, жодного bloom), а замість сектора — МИГАННЯ, поки точку
 * захоплюють. Мигання чесніше показує подію: сектор повз повільно й вимагав
 * дивитись на нього, а спалах видно бічним зором.
 *
 * <p>Клас нічого не змінює в грі: прогрес і власник живуть у
 * {@link CaptureManager}, тут вони лише читаються.
 */
public class CapturePointRenderer {

    /** Товщина межі у світових одиницях. */
    private static final float EDGE_THICKNESS = 2.5f;

    /** Прозорість заливки всередині зони. */
    private static final float FILL_ALPHA         = 0.14f;
    /** Захоплена зона — трохи щільніша: це вже стан, а не заклик. */
    private static final float FILL_ALPHA_OWNED   = 0.20f;
    /** Прозорість межі. */
    private static final float EDGE_ALPHA         = 0.75f;

    /**
     * Період мигання, секунди.
     *
     * <p>Помітно коротший за 12 секунд повного захоплення: за час одного
     * заходу гравець мусить побачити кілька спалахів, інакше це читається не як
     * «триває», а як «блимнуло щось одне».
     */
    private static final float BLINK_PERIOD = 0.7f;

    /** Наскільки гасне зона в нижній точці мигання. */
    private static final float BLINK_DEPTH = 0.55f;

    private static final float[] COLOR_NEUTRAL = { 0.85f, 0.85f, 0.85f };
    private static final float[] COLOR_PLAYER  = { 0.35f, 0.60f, 1.00f };
    private static final float[] COLOR_ENEMY   = { 1.00f, 0.35f, 0.30f };

    /** Час від початку матчу — тільки для мигання, на стан гри не впливає. */
    private float time;

    public void update(float delta) { time += delta; }

    public void draw(ShapeRenderer shapes, CaptureManager points) {
        if (points == null) return;
        Array<CapturePoint> all = points.getPoints();
        if (all.size == 0) return;

        // Звичайний альфа-блендинг: колір більше НЕ premultiplied, бо малюємо
        // прямо в кадр, а не в буфер bloom.
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Мигання: 1 у піку, (1 - BLINK_DEPTH) у западині.
        float blink = 1f - BLINK_DEPTH
            * (0.5f - 0.5f * MathUtils.cos(time * MathUtils.PI2 / BLINK_PERIOD));

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < all.size; i++) drawZone(shapes, all.get(i), blink);
        shapes.end();
    }

    private void drawZone(ShapeRenderer shapes, CapturePoint p, float blink) {
        // Колір показує, ЧИЙ прогрес зараз накопичений: поки чужу точку
        // зривають, вона ще належить старому власнику, але тягне її вже інший,
        // і бачити треба саме це.
        float[] rgb = colorOf(p.holder != null ? p.holder : p.owner);
        if (rgb == null) rgb = COLOR_NEUTRAL;

        // Мигає лише те, що ЗАРАЗ міняється: захоплена й нікому не потрібна
        // точка має стояти спокійно, інакше блимає пів карти й мигання
        // перестає щось означати.
        boolean changing = p.progress > 0 && p.progress < CaptureManager.FULL;
        float   pulse    = changing ? blink : 1f;

        float fillAlpha = (p.owner != null ? FILL_ALPHA_OWNED : FILL_ALPHA) * pulse;
        float edgeAlpha = EDGE_ALPHA * pulse;

        float[] c = corners(p);

        shapes.setColor(rgb[0], rgb[1], rgb[2], fillAlpha);
        // Опуклий чотирикутник — два трикутники по спільній діагоналі 0–2.
        shapes.triangle(c[0], c[1], c[2], c[3], c[4], c[5]);
        shapes.triangle(c[0], c[1], c[4], c[5], c[6], c[7]);

        shapes.setColor(rgb[0], rgb[1], rgb[2], edgeAlpha);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            edge(shapes, c[i * 2], c[i * 2 + 1], c[j * 2], c[j * 2 + 1]);
        }
    }

    /**
     * Відрізок межі як витягнутий чотирикутник.
     *
     * <p>Не {@code ShapeType.Line}: та має товщину в один піксель ЕКРАНА, тобто
     * на віддаленій камері межа зникала б, а на наближеній лишалась ниткою.
     * Тут товщина у світових одиницях і масштабується разом із картою.
     */
    private void edge(ShapeRenderer shapes, float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 0.0001f) return;

        float h = EDGE_THICKNESS / 2f;
        float px = -dy / len * h, py = dx / len * h;

        shapes.triangle(x0 + px, y0 + py, x0 - px, y0 - py, x1 - px, y1 - py);
        shapes.triangle(x0 + px, y0 + py, x1 - px, y1 - py, x1 + px, y1 + py);
    }

    /** Буфер вершин у пікселях. Поле, а не локальна змінна — рендер щокадровий. */
    private final float[] cornerBuf = new float[8];

    private float[] corners(CapturePoint p) {
        for (int i = 0; i < 8; i++) cornerBuf[i] = Fixed.toFloat(p.corners[i]);
        return cornerBuf;
    }

    private static float[] colorOf(Team team) {
        if (team == Team.PLAYER) return COLOR_PLAYER;
        if (team == Team.ENEMY)  return COLOR_ENEMY;
        return null;
    }
}
