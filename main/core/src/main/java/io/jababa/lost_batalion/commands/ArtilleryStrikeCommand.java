package io.jababa.lost_batalion.commands;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.screens.effects.ArtilleryStrikeEffect;
import io.jababa.lost_batalion.units.Artillery;
import io.jababa.lost_batalion.units.Unit;
import io.jababa.lost_batalion.units.UnitManager;

/**
 * Команда "Артилерійський вогонь".
 *
 * Зміни відносно попередньої версії:
 *  - Урон наноситься у колбеку onImpact() (момент прильоту снаряду).
 *  - Range обмежений: клік далі STRIKE_RANGE від гармати ігнорується.
 *  - Прицільний індикатор — білий, з більш красивим дизайном.
 *  - Снаряд у польоті та вибух — PNG/GIF через ArtilleryStrikeEffect.
 *  - Жодного ShapeRenderer-вибуху: всі ефекти через спрайти.
 *
 * Стан-машина:
 *   IDLE      → TARGETING (beginTargeting)
 *   TARGETING → AIMING    (setTarget — клік у межах range)
 *   AIMING    → FIRING    (aimTimer >= aimRequired)
 *   FIRING    → IDLE      (всі ефекти завершені)
 */
public class ArtilleryStrikeCommand {

    public enum State { IDLE, TARGETING, AIMING, FIRING }

    private State state      = State.IDLE;
    private float aimTimer   = 0f;
    private float aimRequired= 0f;

    // Курсор (TARGETING)
    private float cursorX, cursorY;
    // Ціль (AIMING / FIRING)
    private float targetX, targetY;

    private final Array<Artillery>           shooters = new Array<>();
    private final Array<ArtilleryStrikeEffect> effects= new Array<>();

    // ── Публічне API ─────────────────────────────────────────────────────

    public void beginTargeting(Array<Artillery> cannons) {
        if (cannons.size == 0) return;
        shooters.clear();
        for (Artillery a : cannons) {
            if (a.alive && a.isReady()) shooters.add(a);
        }
        if (shooters.size == 0) return;
        state = State.TARGETING;
    }

    public void updateCursor(float worldX, float worldY) {
        cursorX = worldX;
        cursorY = worldY;
    }

    /**
     * Гравець клікнув на карті.
     * Перевіряємо що хоча б одна гармата може дістати цю точку.
     * @return true якщо ціль прийнята, false якщо поза межами range.
     */
    public boolean setTarget(float worldX, float worldY) {
        if (state != State.TARGETING) return false;

        // Перевірка дальності: хоча б одна гармата має дістати
        boolean inRange = false;
        for (int i = 0; i < shooters.size; i++) {
            float dist = shooters.get(i).position.dst(worldX, worldY);
            if (dist <= Artillery.STRIKE_RANGE) { inRange = true; break; }
        }
        if (!inRange) return false;

        targetX     = worldX;
        targetY     = worldY;
        aimTimer    = 0f;
        aimRequired = Artillery.STRIKE_AIM_TIME;
        state       = State.AIMING;
        return true;
    }

    public void cancel() {
        state = State.IDLE;
        shooters.clear();
    }

    public boolean isTargeting() { return state == State.TARGETING; }
    public boolean isAiming()    { return state == State.AIMING;    }
    public boolean isFiring()    { return state == State.FIRING;    }
    public boolean isIdle()      { return state == State.IDLE;      }
    public boolean isActive()    { return state != State.IDLE;      }
    public State   getState()    { return state; }

    public void update(float delta, UnitManager unitManager) {
        // Оновлення ефектів — урон вже нанесено через колбек
        for (int i = effects.size - 1; i >= 0; i--) {
            ArtilleryStrikeEffect eff = effects.get(i);
            eff.update(delta);
            if (!eff.active) effects.removeIndex(i);
        }

        if (state == State.AIMING) {
            aimTimer += delta;
            if (aimTimer >= aimRequired) {
                fire(unitManager);
            }
        }

        if (state == State.FIRING) {
            boolean allDone = effects.size == 0;
            if (!allDone) {
                allDone = true;
                for (int i = 0; i < effects.size; i++) {
                    if (effects.get(i).active) { allDone = false; break; }
                }
            }
            if (allDone) {
                state = State.IDLE;
                shooters.clear();
            }
        }
    }

    /**
     * Рендер прицілу та ефектів.
     * batch і shapes мають мати camera.combined як projection matrix.
     */
    public void draw(SpriteBatch batch, ShapeRenderer shapes) {
        switch (state) {
            case TARGETING:
                drawTargetingUI(shapes, cursorX, cursorY, true);
                drawRangeCircles(shapes); // коло досяжності навколо гармат
                break;
            case AIMING:
                drawTargetingUI(shapes, targetX, targetY, false);
                drawAimProgress(shapes);
                break;
            case FIRING:
                drawFiringEffects(batch, shapes);
                break;
            default: break;
        }
    }

    public void dispose() {
        for (ArtilleryStrikeEffect e : effects) { /* no dispose needed per-effect for now */ }
        effects.clear();
        ArtilleryStrikeEffect.disposeAssets();
    }

    // ── Вогонь ───────────────────────────────────────────────────────────

    private void fire(UnitManager unitManager) {
        state = State.FIRING;
        ArtilleryStrikeEffect.loadAssets();

        for (int i = 0; i < shooters.size; i++) {
            Artillery art = shooters.get(i);
            if (!art.alive) continue;

            // Розкид
            float angle  = MathUtils.random(0f, MathUtils.PI2);
            float spread = MathUtils.random(0f, Artillery.STRIKE_SPREAD);
            final float impX = targetX + MathUtils.cos(angle) * spread;
            final float impY = targetY + MathUtils.sin(angle) * spread;

            final UnitManager um = unitManager;

            // Ефект зі вбудованим колбеком: урон ТІЛЬКИ при прильоті
            ArtilleryStrikeEffect eff = new ArtilleryStrikeEffect();
            eff.show(
                art.position.x, art.position.y,
                impX, impY,
                Artillery.STRIKE_SPLASH_RADIUS,
                // ── onImpact ─────────────────────────────────────────────
                (cx, cy, splashR) -> applyAoeDamage(um, cx, cy, splashR, Artillery.STRIKE_DAMAGE)
            );
            effects.add(eff);

            art.startReload();
        }
    }

    /** AoE урон у момент імпакту. Б'є всіх, включаючи союзників. */
    private void applyAoeDamage(UnitManager unitManager,
                                float cx, float cy,
                                float radius, float baseDamage) {
        Array<Unit> all = unitManager.getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!u.alive) continue;
            float dist = u.position.dst(cx, cy);
            if (dist > radius) continue;
            float falloff = 1f - (dist / radius);
            float dmg     = baseDamage * falloff;
            u.takeDamage(dmg);
            Gdx.app.log("ARTILLERY",
                "Impact hit team=" + u.team + " dist="+dist+" dmg="+dmg);
        }
    }

    // ── Рендер прицілу ───────────────────────────────────────────────────

    /**
     * Білий прицільний індикатор у вибраній точці.
     * @param pulsing true = мигає (в режимі TARGETING), false = статичний (AIMING)
     */
    private void drawTargetingUI(ShapeRenderer shapes, float wx, float wy, boolean pulsing) {
        float r      = Artillery.STRIKE_SPLASH_RADIUS;
        float pulse  = pulsing
            ? 0.65f + 0.35f * (float) Math.abs(Math.sin(Gdx.graphics.getDeltaTime() * 80f
            + (float)(System.currentTimeMillis() % 3000) * 0.004f))
            : 1f;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // ── Залита зона ───────────────────────────────────────────────────
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, 0.08f * pulse);
        shapes.circle(wx, wy, r);
        shapes.end();

        // ── Зовнішнє коло ─────────────────────────────────────────────────
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1f, 1f, 1f, 0.90f * pulse);
        shapes.circle(wx, wy, r);

        // ── Внутрішнє маленьке коло ───────────────────────────────────────
        shapes.setColor(1f, 1f, 1f, 0.55f * pulse);
        shapes.circle(wx, wy, r * 0.18f);

        // ── Хрест (4 промені від краю внутр. кола до зовн.) ──────────────
        float inner = r * 0.22f;
        float outer = r * 0.72f;
        shapes.setColor(1f, 1f, 1f, 0.80f * pulse);
        shapes.line(wx,         wy + inner, wx,         wy + outer); // вгору
        shapes.line(wx,         wy - outer, wx,         wy - inner); // вниз
        shapes.line(wx + inner, wy,         wx + outer, wy);         // праворуч
        shapes.line(wx - outer, wy,         wx - inner, wy);         // ліворуч

        // ── 4 діагональних коротких риски на зовнішньому колі ─────────────
        float tick = r * 0.12f;
        for (int i = 0; i < 4; i++) {
            float a = MathUtils.PI / 4f + i * MathUtils.PI / 2f;
            float cos = MathUtils.cos(a), sin = MathUtils.sin(a);
            shapes.setColor(1f, 1f, 1f, 0.60f * pulse);
            shapes.line(wx + cos * (r - tick), wy + sin * (r - tick),
                wx + cos * (r + tick), wy + sin * (r + tick));
        }

        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /**
     * Коло досяжності навколо кожної гармати (тонке, напівпрозоре).
     * Показується лише в режимі TARGETING.
     */
    private void drawRangeCircles(ShapeRenderer shapes) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1f, 1f, 1f, 0.20f);
        for (int i = 0; i < shooters.size; i++) {
            Artillery a = shooters.get(i);
            shapes.circle(a.position.x, a.position.y, Artillery.STRIKE_RANGE);
        }
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /**
     * Індикатор прицілювання над гарматою: прогрес-бар + мигаючий прицільник.
     */
    private void drawAimProgress(ShapeRenderer shapes) {
        float progress = aimRequired > 0f ? Math.min(aimTimer / aimRequired, 1f) : 0f;
        float pulse    = (float) Math.abs(Math.sin(aimTimer * 7f));

        // Прицільник мигає
        drawTargetingUI(shapes, targetX, targetY, false);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        for (int i = 0; i < shooters.size; i++) {
            Artillery art = shooters.get(i);
            if (!art.alive) continue;

            float cx  = art.position.x;
            float cy  = art.position.y + art.getSize() / 2f + 6f;
            float barW= art.getSize() * 1.2f;
            float barH= 5f;
            float bx  = cx - barW / 2f;

            shapes.begin(ShapeRenderer.ShapeType.Filled);

            // Фон
            shapes.setColor(0.1f, 0.1f, 0.1f, 0.80f);
            shapes.rect(bx, cy, barW, barH);

            // Заповнення — біле, в кінці мигає
            float fillA = progress < 1f ? 0.95f : 0.95f * pulse;
            shapes.setColor(1f, 1f, 1f, fillA);
            shapes.rect(bx, cy, barW * progress, barH);

            shapes.end();

            // Рамка
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(1f, 1f, 1f, 0.45f);
            shapes.rect(bx, cy, barW, barH);
            shapes.end();
        }

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void drawFiringEffects(SpriteBatch batch, ShapeRenderer shapes) {
        for (int i = 0; i < effects.size; i++) {
            ArtilleryStrikeEffect eff = effects.get(i);
            if (eff.active) eff.draw(batch, shapes);
        }
    }
}
