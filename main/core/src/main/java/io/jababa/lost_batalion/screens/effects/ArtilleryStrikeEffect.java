package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Ефект одного артилерійського пострілу.
 *
 * Фази:
 *   INCOMING — снаряд летить (PNG: effects/shell.png).
 *              Обертається у напрямку польоту.
 *   EXPLODE  — анімація вибуху (spritesheet або окремі PNG).
 *   DONE     — активність скинута.
 *
 * Клас СУТО ВІЗУАЛЬНИЙ. Політ і момент влучання рахує ArtilleryShell у
 * симуляції, в тіках і у fixed-point; сюди позиція лише передається ззовні.
 * Раніше політ інтегрувався тут за часом кадру, і оскільки саме перехід
 * INCOMING→EXPLODE наносив AoE-урон, момент влучання залежав від FPS —
 * тобто був десинхроном.
 *
 * ── Asset-файли ────────────────────────────────────────────────────────────
 *   effects/shell.png              — PNG снаряду (~16x8 px, вістрям вправо)
 *
 *   Варіант A (spritesheet):
 *     effects/explosion_sheet.png  — всі кадри в один рядок
 *     ExplosionAnimation.fromSheet("effects/explosion_sheet.png", COLS, 1, FPS)
 *
 *   Варіант B (окремі файли):
 *     effects/explosion_0.png, effects/explosion_1.png, ...
 *     ExplosionAnimation.fromFrames("effects/explosion_", COUNT, ".png", FPS)
 *
 *   Якщо файлів немає — ефект просто не малюється (без fallback-коду).
 */
public class ArtilleryStrikeEffect {

    // ── Налаштування ─────────────────────────────────────────────────────
    /** Розмір спрайту снаряду на екрані. */
    private static final float SHELL_W        = 20f;
    private static final float SHELL_H        = 10f;

    /** Розмір анімації вибуху (px). Злегка більший від splashRadius. */
    private static final float EXPLOSION_SIZE_MULT = 3.2f;

    // ── Spritesheet налаштування (відредагуй під свій файл) ──────────────
    private static final int   SHEET_COLS = 8;   // кількість кадрів у рядку (640px кожен)
    private static final int   SHEET_ROWS = 10;  // кількість рядків (426px кожен)
    private static final float ANIM_FPS   = 16f; // кадрів/сек

    // ── Стан ─────────────────────────────────────────────────────────────
    public enum Phase { INCOMING, EXPLODE, DONE }

    public  boolean active = false;
    private Phase   phase  = Phase.DONE;

    private float targetX, targetY;
    private float curX, curY;
    private float angleDeg; // кут польоту, градуси

    private float splashRadius;

    private float explodeTimer = 0f;

    // ── Текстури / анімація (спільні для всіх екземплярів) ───────────────
    private static Texture           shellTex       = null;
    private static ExplosionAnimation explosionAnim = null;
    private static boolean            assetsLoaded  = false;

    // ── Публічне API ─────────────────────────────────────────────────────

    public static void loadAssets() {
        if (assetsLoaded) return;
        assetsLoaded = true;

        // Shell PNG
        if (Gdx.files.internal("effects/shell.png").exists()) {
            shellTex = new Texture(Gdx.files.internal("effects/shell.png"));
            shellTex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        }

        // Explosion: спробуємо spritesheet
        explosionAnim = ExplosionAnimation.fromSheet(
            "effects/explosion_sheet.png", SHEET_COLS, SHEET_ROWS, ANIM_FPS);

        // Якщо немає spritesheet — спробуємо окремі файли
        if (explosionAnim == null) {
            explosionAnim = ExplosionAnimation.fromFrames(
                "effects/explosion_", 8, ".png", ANIM_FPS);
        }
    }

    public static void disposeAssets() {
        if (shellTex != null)      { shellTex.dispose();       shellTex = null; }
        if (explosionAnim != null) { explosionAnim.dispose();  explosionAnim = null; }
        assetsLoaded = false;
    }

    /** Почати показ снаряду в польоті. Позицію далі задає симуляція. */
    public void showIncoming(float fromX, float fromY,
                             float targetX, float targetY,
                             float splashRadius, float angleDeg) {
        this.targetX      = targetX;
        this.targetY      = targetY;
        this.splashRadius = splashRadius;
        this.curX         = fromX;
        this.curY         = fromY;
        this.angleDeg     = angleDeg;

        explodeTimer = 0f;
        phase        = Phase.INCOMING;
        active       = true;
    }

    /** Позиція снаряду цього кадру — приходить із симуляції. */
    public void setShellPosition(float x, float y) {
        curX = x;
        curY = y;
    }

    /** Снаряд влучив: далі показуємо вибух. Урон уже нанесла симуляція. */
    public void explode() {
        phase        = Phase.EXPLODE;
        explodeTimer = 0f;
        active       = true;
    }

    /** Тільки анімація вибуху — політ рахує симуляція, а не цей таймер. */
    public void update(float delta) {
        if (!active || phase != Phase.EXPLODE) return;

        explodeTimer += delta;
        float duration = explosionAnim != null ? explosionAnim.getDuration() : 0f;
        if (explosionAnim == null || explodeTimer >= duration) {
            phase  = Phase.DONE;
            active = false;
        }
    }

    /**
     * Малювати ефект.
     * batch та shapes мають мати camera.combined як projection matrix.
     */
    public void draw(SpriteBatch batch, ShapeRenderer shapes) {
        if (!active) return;

        switch (phase) {
            case INCOMING: drawShell(batch);     break;
            case EXPLODE:  drawExplosion(batch); break;
            default: break;
        }
    }

    public Phase getPhase() { return phase; }

    // ── Приватні ────────────────────────────────────────────────────────

    private void drawShell(SpriteBatch batch) {
        if (shellTex == null) return;

        batch.begin();
        // Малюємо з обертанням у напрямку польоту
        float originX = SHELL_W / 2f;
        float originY = SHELL_H / 2f;

        batch.draw(
            shellTex,
            curX - originX, curY - originY,  // position
            originX, originY,                // origin (центр обертання)
            SHELL_W, SHELL_H,               // size
            1f, 1f,                          // scale
            angleDeg,                        // rotation
            0, 0,                            // srcX, srcY
            shellTex.getWidth(), shellTex.getHeight(),
            false, false
        );
        batch.end();
    }

    private void drawExplosion(SpriteBatch batch) {
        if (explosionAnim == null) return;

        float size = splashRadius * EXPLOSION_SIZE_MULT;

        // Fade out в кінці анімації
        float duration = explosionAnim.getDuration();
        float alpha    = duration > 0f
            ? Math.max(0f, 1f - (explodeTimer / duration) * 0.4f)  // легкий fade лише наприкінці
            : 1f;

        batch.begin();
        explosionAnim.draw(batch, targetX, targetY, size, explodeTimer, alpha);
        batch.end();
    }
}
