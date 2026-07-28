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
 * Кола стратегічних точок: біле світіння на початку матчу, колір сторони —
 * у міру захоплення.
 *
 * <h3>Як це малюється</h3>
 * Усе йде всередині {@code BloomEffect}, тобто в буфер із
 * <b>premultiplied alpha</b>: RGB тут множаться на альфу вручну
 * ({@link #tint}), інакше ореол вийшов би темнішим за саму фігуру. Той самий
 * буфер дає й «блендинг»: обід із альфою, близькою до нуля, і ненульовим
 * кольором додається до фону як чисте світло, а не затуляє його.
 *
 * <p>Заповнення — сектор від 12-ї години за годинниковою стрілкою: це читається
 * як шкала, а не як «щось підсвітилось». Поки точка нейтральна, сектора немає
 * взагалі — тільки біле коло.
 *
 * <p>Клас нічого не змінює в грі: прогрес і власник живуть у
 * {@link CaptureManager}, тут вони лише читаються.
 */
public class CapturePointRenderer {

    /** Скільки відрізків на повне коло. 72 — межа, за якою на зумі ×2 ще не видно граней. */
    private static final int SEGMENTS = 72;

    /** Товщина обода у світових одиницях. */
    private static final float RING_THICKNESS = 3f;

    /**
     * Прозорість обода — вона ж сила світіння.
     *
     * <p>Окремої ручки яскравості в {@code BloomEffect} немає і бути не повинно:
     * він спільний із позначкою наказу, яка навпаки має бити в очі. Тому яскравість
     * тут регулюється єдиним доступним важелем — альфою: ореол будується з того
     * самого, що намальовано, тож слабший обід дає і слабше світіння.
     *
     * <p>На 0.90 обід світився як неонова вивіска і випадав зі стилю карти.
     */
    private static final float RING_ALPHA = 0.55f;

    /** Захоплена частина обода — трохи щільніша: це вже стан, а не заклик. */
    private static final float RING_ALPHA_OWNED = 0.65f;

    /**
     * Прозорість заливки всередині кола.
     *
     * <p>Здається мізерною, але це вже після bloom: заливка йде в той самий
     * буфер, що й обід, і ореол додає її кольору ще раз. На 0.26 захоплене село
     * ставало непрозорою кольоровою плямою, з якої не видно ані хат, ані того,
     * хто в ній стоїть.
     */
    private static final float DISC_ALPHA = 0.11f;
    /** Прозорість білої «підкладки» нейтральної точки. */
    private static final float HALO_ALPHA = 0.07f;

    /** Період пульсації обода, секунди. */
    private static final float PULSE_PERIOD = 2.4f;

    private static final float[] COLOR_NEUTRAL = { 1.00f, 1.00f, 1.00f };
    private static final float[] COLOR_PLAYER  = { 0.35f, 0.60f, 1.00f };
    private static final float[] COLOR_ENEMY   = { 1.00f, 0.35f, 0.30f };

    /** Час від початку матчу — тільки для пульсації, на стан гри не впливає. */
    private float time;

    public void update(float delta) { time += delta; }

    public void draw(ShapeRenderer shapes, CaptureManager points) {
        if (points == null) return;
        Array<CapturePoint> all = points.getPoints();
        if (all.size == 0) return;

        float radius = Fixed.toFloat(CaptureManager.RADIUS);
        float pulse  = 0.90f + 0.10f * MathUtils.sin(time * MathUtils.PI2 / PULSE_PERIOD);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFuncSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA,
                                   GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);

        for (int i = 0; i < all.size; i++) drawPoint(shapes, all.get(i), radius, pulse);

        shapes.end();
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void drawPoint(ShapeRenderer shapes, CapturePoint p, float radius, float pulse) {
        float cx = Fixed.toFloat(p.x);
        float cy = Fixed.toFloat(p.y);
        float fill = p.fill();
        float[] team = colorOf(p.holder);

        // Біла підкладка — вона ж «коло світиться» до будь-якого захоплення.
        tint(shapes, COLOR_NEUTRAL, HALO_ALPHA);
        band(shapes, cx, cy, 0f, radius, 90f, -360f);

        // Заливка кольором сторони: сектор від 12-ї години за годинниковою.
        if (fill > 0f && team != null) {
            tint(shapes, team, DISC_ALPHA);
            band(shapes, cx, cy, 0f, radius, 90f, -360f * fill);
        }

        // Обід: біла частина — те, що ще не захоплено, кольорова — те, що вже.
        float ringInner = radius - RING_THICKNESS;
        if (fill < 1f) {
            tint(shapes, COLOR_NEUTRAL, RING_ALPHA * pulse);
            band(shapes, cx, cy, ringInner, radius, 90f - 360f * fill, -360f * (1f - fill));
        }
        if (fill > 0f && team != null) {
            // Захоплена частина не пульсує: це вже не заклик «прийди сюди», а стан.
            tint(shapes, team, p.owner != null ? RING_ALPHA_OWNED : RING_ALPHA);
            band(shapes, cx, cy, ringInner, radius, 90f, -360f * fill);
        }
    }

    /**
     * Сектор смуги між двома радіусами. {@code rInner == 0} дає звичайний
     * сектор круга, більший — обід.
     *
     * <p>Своя реалізація замість {@link ShapeRenderer#arc} і {@code circle} з
     * двох причин. Обід із {@code ShapeType.Line} мав би товщину в один піксель
     * ЕКРАНА і на зумі ×0.3 зникав би зовсім. А {@code arc} малює сектор своїм
     * кроком по кутах, не тим самим, що обід, — і на межі заповнення між ними
     * лишалась смужка фону завширшки в один сегмент.
     *
     * <p>Тому і заливка, і обід ідуть звідси: крок по кутах у них однаковий, а
     * кут кожної вершини рахується від {@code startDeg} наново
     * ({@code startDeg + step * i}), а не накопиченням повороту — інакше похибка
     * за 72 кроки зсунула б останню вершину з початкової і замикання дало б
     * видимий шов.
     *
     * @param sweepDeg розмах у градусах; від'ємний — за годинниковою стрілкою
     */
    private void band(ShapeRenderer shapes, float cx, float cy,
                      float rInner, float rOuter, float startDeg, float sweepDeg) {
        int steps = Math.max(2, Math.round(SEGMENTS * Math.abs(sweepDeg) / 360f));
        float step = sweepDeg / steps;

        float a0 = startDeg * MathUtils.degRad;
        float c0 = MathUtils.cos(a0), s0 = MathUtils.sin(a0);

        for (int i = 1; i <= steps; i++) {
            float a1 = (startDeg + step * i) * MathUtils.degRad;
            float c1 = MathUtils.cos(a1), s1 = MathUtils.sin(a1);

            float x0o = cx + c0 * rOuter, y0o = cy + s0 * rOuter;
            float x1o = cx + c1 * rOuter, y1o = cy + s1 * rOuter;

            if (rInner <= 0f) {
                shapes.triangle(cx, cy, x0o, y0o, x1o, y1o);
            } else {
                float x0i = cx + c0 * rInner, y0i = cy + s0 * rInner;
                float x1i = cx + c1 * rInner, y1i = cy + s1 * rInner;
                shapes.triangle(x0i, y0i, x0o, y0o, x1o, y1o);
                shapes.triangle(x0i, y0i, x1o, y1o, x1i, y1i);
            }

            c0 = c1; s0 = s1;
        }
    }

    /** Колір із домноженим на альфу RGB — вимога premultiplied-буфера bloom. */
    private void tint(ShapeRenderer shapes, float[] rgb, float alpha) {
        shapes.setColor(rgb[0] * alpha, rgb[1] * alpha, rgb[2] * alpha, alpha);
    }

    private static float[] colorOf(Team team) {
        if (team == Team.PLAYER) return COLOR_PLAYER;
        if (team == Team.ENEMY)  return COLOR_ENEMY;
        return null;
    }
}
