package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;

/**
 * Ефект одного артилерійського пострілу — цілком процедурний.
 *
 * Фази:
 *   INCOMING — снаряд летить: розжарене ядро з ореолом і димний слід.
 *   EXPLODE  — приліт: спалах, ударне кільце (решту — вогонь, уламки, дим —
 *              висипає {@link Fx} у спільний пул).
 *   DONE     — активність скинута.
 *
 * <p>PNG тут більше немає: раніше снаряд був {@code effects/shell.png}, а вибух
 * — покадровим листом 16×16, який на екрані розтягувався в кольорову пляму й
 * не масштабувався під радіус ураження. Тепер усе малюється кодом, тож розмір
 * вибуху прив'язаний до splashRadius, а не до розкладки листа.
 *
 * <p>Клас СУТО ВІЗУАЛЬНИЙ. Політ і момент влучання рахує ArtilleryShell у
 * симуляції, в тіках і у fixed-point; сюди позиція лише передається ззовні.
 * Раніше політ інтегрувався тут за часом кадру, і оскільки саме перехід
 * INCOMING→EXPLODE наносив AoE-урон, момент влучання залежав від FPS —
 * тобто був десинхроном.
 */
public class ArtilleryStrikeEffect {

    // ── Налаштування ─────────────────────────────────────────────────────
    /**
     * Ядро — це саме ядро: маленька темна кулька. Раніше воно було світляною
     * плямою на пів юніта, і на екрані читалось як ракета, а не як постріл
     * гармати.
     */
    private static final float SHELL_CORE = 2.6f;
    /** Ледь помітний теплий ореол — щоб ядро не губилось на темній карті. */
    private static final float SHELL_GLOW = 5.5f;
    /** Через скільки пройдених пікселів снаряд лишає новий клубок диму. */
    private static final float TRAIL_STEP = 4.5f;

    /** Спалах прильоту — короткий і дрібний; далі все доробляє дим. */
    private static final float FLASH_TIME = 0.09f;
    /** Ядро спалаху відносно радіуса ураження. */
    private static final float FLASH_MULT = 0.22f;
    /** Скільки ефект лишається живим після прильоту (дим живе у спільному пулі). */
    private static final float EXPLODE_TIME = 0.20f;

    // ── Стан ─────────────────────────────────────────────────────────────
    public enum Phase { INCOMING, EXPLODE, DONE }

    public  boolean active = false;
    private Phase   phase  = Phase.DONE;

    private float targetX, targetY;
    private float curX, curY;
    private float angleDeg; // кут польоту, градуси

    private float splashRadius;

    private float explodeTimer = 0f;
    /** Скільки лишилось «пройти» до наступного клубка диму. */
    private float trailBudget = 0f;
    private boolean trailStarted = false;

    // ── Публічне API ─────────────────────────────────────────────────────

    /**
     * Лишилось для сумісності з викликами: текстур-файлів ефект більше не
     * має, кисті створюються самі при першому малюванні.
     */
    public static void loadAssets() { /* нічого завантажувати */ }

    /** Звільнити процедурні кисті й погасити пул частинок. */
    public static void disposeAssets() { Fx.dispose(); }

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
        trailBudget  = 0f;
        trailStarted = false;
        phase        = Phase.INCOMING;
        active       = true;
    }

    /**
     * Позиція снаряду цього кадру — приходить із симуляції.
     *
     * <p>Тут же сиплеться димний слід: інтервал рахується по ПРОЙДЕНІЙ
     * відстані, а не по часу кадру, інакше на 144 Гц слід був би вдвічі
     * густішим, ніж на 60.
     */
    public void setShellPosition(float x, float y) {
        if (phase == Phase.INCOMING && trailStarted) {
            float dx = x - curX, dy = y - curY;
            trailBudget += (float) Math.sqrt(dx * dx + dy * dy);
            while (trailBudget >= TRAIL_STEP) {
                trailBudget -= TRAIL_STEP;
                Fx.shellTrail(x, y);
            }
        }
        trailStarted = true;
        curX = x;
        curY = y;
    }

    /** Снаряд влучив: далі показуємо вибух. Урон уже нанесла симуляція. */
    public void explode() {
        phase        = Phase.EXPLODE;
        explodeTimer = 0f;
        active       = true;
        Fx.impact(targetX, targetY, splashRadius);
    }

    /** Тільки анімація вибуху — політ рахує симуляція, а не цей таймер. */
    public void update(float delta) {
        if (!active || phase != Phase.EXPLODE) return;

        explodeTimer += delta;
        if (explodeTimer >= EXPLODE_TIME) {
            phase  = Phase.DONE;
            active = false;
        }
    }

    /**
     * Малювати ефект.
     *
     * <p>{@code batch} має мати camera.combined як projection і бути закритим:
     * метод сам відкриває його у premultiplied-режимі (див. {@link Fx}).
     * {@code shapes} лишився в сигнатурі для сумісності і не використовується.
     */
    public void draw(SpriteBatch batch, ShapeRenderer shapes) {
        if (!active) return;

        batch.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA,
                                       GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.begin();
        switch (phase) {
            case INCOMING: drawShell(batch);     break;
            case EXPLODE:  drawExplosion(batch); break;
            default: break;
        }
        batch.end();
        batch.setColor(1f, 1f, 1f, 1f);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    public Phase getPhase() { return phase; }

    // ── Приватні ────────────────────────────────────────────────────────

    private void drawShell(SpriteBatch batch) {
        // Слабкий теплий ореол додається (альфа 0) — світло нічого не затуляє.
        additive(batch, 0.85f, 0.62f, 0.40f, 0.30f);
        blob(batch, curX, curY, SHELL_GLOW, SHELL_GLOW * 0.7f, angleDeg);

        // Саме ядро — темна металева кулька, звичайним перекриттям.
        batch.setColor(0.16f, 0.15f, 0.14f, 1f);
        blob(batch, curX, curY, SHELL_CORE * 1.35f, SHELL_CORE, angleDeg);
    }

    private void drawExplosion(SpriteBatch batch) {
        // Уся «графіка» прильоту — коротка теплувата іскра. Хмару диму,
        // уламки й пил сипле Fx у спільний пул.
        if (explodeTimer >= FLASH_TIME) return;

        float t = explodeTimer / FLASH_TIME;
        float k = 1f - t;
        float size = splashRadius * FLASH_MULT * (0.5f + t * 0.7f);
        additive(batch, 1f, 0.88f, 0.68f, k * 0.75f);
        blob(batch, targetX, targetY, size, size, 0f);
    }

    /** Колір у premultiplied-вигляді з нульовою альфою — чисте додавання. */
    private void additive(SpriteBatch batch, float r, float g, float b, float a) {
        batch.setColor(r * a, g * a, b * a, 0f);
    }

    /** Витягнута вздовж польоту пляма. */
    private void blob(SpriteBatch batch, float x, float y, float w, float h, float rotDeg) {
        Texture tex = Fx.dot();
        batch.draw(tex, x - w / 2f, y - h / 2f, w / 2f, h / 2f, w, h, 1f, 1f, rotDeg,
                   0, 0, tex.getWidth(), tex.getHeight(), false, false);
    }
}
