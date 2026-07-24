package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;

/**
 * Артилерійський юніт.
 *
 * - Автоматично обстрілює всіх ворогів у радіусі STRIKE_RANGE (AoE-снаряд).
 * - Якщо гравець виділив артилерію і клікнув ПКМ по ворогу — б'є саме по ньому
 *   (manualTarget), поки той живий і в радіусі; інакше повертається до авто-цілі.
 * - Менший розмір спрайту та хітбоксу (32px).
 */
public class Artillery extends Unit {

    public static final float ART_SIZE            = 32f;   // менший спрайт / хітбокс
    public static final float ART_SPEED           = 45f;
    public static final float ART_HP              = 180f;
    public static final float ART_DEFENSE         = 5f;

    // ── Параметри пострілу ────────────────────────────────────────────────
    /** Максимальна дальність обстрілу (auto + manual). Зменшена. */
    public static final float STRIKE_RANGE         = 220f;
    /** Час прицілювання до пострілу (секунди). */
    public static final float STRIKE_AIM_TIME      = 3.0f;
    /** Радіус вибуху (AoE). */
    public static final float STRIKE_SPLASH_RADIUS = 45f;
    /** Базовий урон у центрі вибуху. */
    public static final float STRIKE_DAMAGE        = 120f;
    /** Максимальний розкид снаряду від точки прицілу (px). */
    public static final float STRIKE_SPREAD        = 18f;
    /** Час перезарядки між пострілами (секунди). */
    public static final float STRIKE_RELOAD_TIME   = 8f;

    /** Таймер перезарядки. Керується CombatManager. */
    public float reloadTimer = 0f;
    /** Таймер прицілювання по поточній цілі. Керується CombatManager. */
    public float aimTimer = 0f;
    /**
     * Ручна ціль (виставляється ПКМ по ворогу поки артилерія виділена).
     * Поки жива і в радіусі — має пріоритет над авто-ціллю.
     */
    public Unit manualTarget = null;

    public Artillery(Team team, float x, float y) {
        super(team);
        maxHp          = ART_HP;
        hp             = ART_HP;
        speed          = ART_SPEED;
        damage         = 0f;
        attackRange    = 0f;
        attackCooldown = Float.MAX_VALUE;
        defense        = ART_DEFENSE;
        sightRange     = 500f;
        position.set(x, y);
    }

    @Override
    public void update(float delta, float terrainSpeedMultiplier) {
        super.update(delta, terrainSpeedMultiplier);
        if (reloadTimer > 0f) reloadTimer -= delta;
    }

    public boolean isReady()     { return reloadTimer <= 0f; }
    public void    startReload() { reloadTimer = STRIKE_RELOAD_TIME; }

    @Override public float  getSize()        { return ART_SIZE; }
    @Override public String getTexturePath() { return "units/artillery.png"; }
}
