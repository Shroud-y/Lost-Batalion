package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.units.Unit;

/**
 * Рендеринг туману війни.
 *
 * Стратегія: «soft fog» без повного FBO.
 *  - Рисуємо темний напівпрозорий прямокутник на всю карту.
 *  - Вирізаємо кола зору ShapeRenderer-ом у режимі SUBTRACT (additive blend).
 *
 * Оскільки libGDX ShapeRenderer не підтримує стенсіл прямо,
 * ми використовуємо простіший, але ефективний підхід:
 *  - Рисуємо суцільний темний фон (glBlend DST_ALPHA trick або просто alpha rect).
 *  - Поверх — кола «прозорості» з поступовим зменшенням alpha до країв (soft edge).
 *
 * Для кожного спостерігача рисуємо концентричні кола із зменшенням alpha:
 *   від центру (alpha = 0, прозоро) до краю зони зору (alpha = FOG_ALPHA).
 *
 * Це дає плавний «ореол» навколо юнітів гравця.
 *
 * Виклик у GameScreen.render():
 *   fogRenderer.render(shapes, camera, unitManager.getAllUnits());
 */
public class FogOfWarRenderer {

    /** Прозорість туману у зонах поза видимістю [0..1] */
    private static final float FOG_ALPHA = 0f;

    /** Колір туману (темно-синій) */
    private static final float FOG_R = 0.04f;
    private static final float FOG_G = 0.04f;
    private static final float FOG_B = 0.10f;

    /** Кількість «шарів» м'якого переходу на краю кола зору */
    private static final int SOFT_STEPS = 6;

    /** Ширина зони м'якого переходу у пікселях */
    private static final float SOFT_ZONE = 30f;

    /** Кількість сегментів для кола зору */
    private static final int CIRCLE_SEGMENTS = 32;

    private final float mapWidth;
    private final float mapHeight;

    public FogOfWarRenderer(float mapWidth, float mapHeight) {
        this.mapWidth  = mapWidth;
        this.mapHeight = mapHeight;
    }

    /**
     * Рендерить туман.
     * Викликати ПІСЛЯ рендеру карти і юнітів, але ДО HUD.
     * shapes має бути НЕ у begin() стані.
     *
     * @param shapes  ShapeRenderer з виставленою projection matrix камери
     * @param camera  активна камера
     * @param allUnits усі юніти (фільтруємо по team == PLAYER)
     */
    public void render(ShapeRenderer shapes, OrthographicCamera camera, Array<Unit> allUnits) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // ── Крок 1: темний фон на всю карту ──────────────────────────────
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(FOG_R, FOG_G, FOG_B, FOG_ALPHA);
        shapes.rect(0, 0, mapWidth, mapHeight);
        shapes.end();

        // ── Крок 2: «вирізаємо» кола зору гравцевих юнітів ───────────────
        // Використовуємо SUBTRACT blending щоб зменшити alpha туману
        Gdx.gl.glBlendFunc(GL20.GL_ZERO, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u.team != Team.PLAYER || !u.alive) continue;

            drawSightCircle(shapes, u);
        }

        shapes.end();

        // ── Крок 3: М'який край ───────────────────────────────────────────
        // Повертаємо звичайний blending і рисуємо кільця переходу
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u.team != Team.PLAYER || !u.alive) continue;

            drawSoftEdge(shapes, u);
        }

        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // ── Приватні методи ───────────────────────────────────────────────────

    /**
     * Малює суцільне коло зору (alpha = FOG_ALPHA, subtracted).
     * У SUBTRACT режимі: чим більший src alpha → тим більше прибирається з фону.
     */
    private void drawSightCircle(ShapeRenderer shapes, Unit observer) {
        float sightRadius = computeEffectiveSight(observer);
        if (sightRadius <= 0f) return;

        // У SUBTRACT blend: alpha=1 → повністю прозоро, alpha=0 → нічого не змінюємо
        shapes.setColor(0f, 0f, 0f, FOG_ALPHA);
        shapes.circle(observer.position.x, observer.position.y,
            sightRadius - SOFT_ZONE, CIRCLE_SEGMENTS);
    }

    /**
     * М'який перехід на межі кола зору: набір концентричних кілець
     * із зменшенням alpha від FOG_ALPHA до 0.
     */
    private void drawSoftEdge(ShapeRenderer shapes, Unit observer) {
        float sightRadius = computeEffectiveSight(observer);
        float innerR = sightRadius - SOFT_ZONE;
        if (innerR < 0f) innerR = 0f;

        float cx = observer.position.x;
        float cy = observer.position.y;

        for (int s = 0; s < SOFT_STEPS; s++) {
            float t    = (float) s / SOFT_STEPS;
            float r    = innerR + (sightRadius - innerR) * t;
            float rNext= innerR + (sightRadius - innerR) * ((float)(s + 1) / SOFT_STEPS);

            // alpha зростає від 0 (центр) до FOG_ALPHA (край)
            float alpha = FOG_ALPHA * t * t;

            drawRing(shapes, cx, cy, r, rNext, alpha);
        }
    }

    /**
     * Рисує кільце між innerR та outerR як набір трикутників.
     */
    private void drawRing(ShapeRenderer shapes, float cx, float cy,
                          float innerR, float outerR, float alpha) {
        shapes.setColor(FOG_R, FOG_G, FOG_B, alpha);

        float angleDelta = (float)(2.0 * Math.PI / CIRCLE_SEGMENTS);
        for (int seg = 0; seg < CIRCLE_SEGMENTS; seg++) {
            float a0 = seg       * angleDelta;
            float a1 = (seg + 1) * angleDelta;

            float x0i = cx + innerR * (float)Math.cos(a0);
            float y0i = cy + innerR * (float)Math.sin(a0);
            float x1i = cx + innerR * (float)Math.cos(a1);
            float y1i = cy + innerR * (float)Math.sin(a1);

            float x0o = cx + outerR * (float)Math.cos(a0);
            float y0o = cy + outerR * (float)Math.sin(a0);
            float x1o = cx + outerR * (float)Math.cos(a1);
            float y1o = cy + outerR * (float)Math.sin(a1);

            shapes.triangle(x0i, y0i, x0o, y0o, x1i, y1i);
            shapes.triangle(x1i, y1i, x0o, y0o, x1o, y1o);
        }
    }

    /**
     * Ефективний радіус зору з урахуванням поточного terrain
     * (VisibilitySystem вже порахував — тут просто беремо sightRange з юніта).
     * Для рендеру туману достатньо базового sightRange,
     * щоб коло "підсвічення" збігалось із радіусом детекції.
     */
    private float computeEffectiveSight(Unit observer) {
        return observer.sightRange;
    }
}
