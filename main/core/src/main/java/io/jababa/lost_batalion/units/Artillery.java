package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;

/**
 * Артилерійський юніт.
 *
 * - Автоматично обстрілює всіх ворогів у радіусі STRIKE_RANGE (AoE-снаряд).
 * - Якщо гравець виділив артилерію і клікнув ПКМ по ворогу — б'є саме по ньому
 *   (manualTarget), поки той живий і в радіусі; інакше повертається до авто-цілі.
 * - Менший розмір спрайту та хітбоксу (32px).
 *
 * <p>Усі таймери — в тіках. Секунди тут були найгіршим місцем для float: час
 * прицілювання визначає МОМЕНТ пострілу, а момент пострілу визначає, куди
 * поїде снаряд і хто під ним опиниться.
 */
public class Artillery extends Unit {

    /** Розмір у світових одиницях (Q47.16) і в пікселях — для рендеру. */
    public static final long  ART_SIZE_FIXED = Fixed.fromInt(16);
    public static final float ART_SIZE       = 32f;

    private static final long ART_SPEED   = Fixed.fromInt(45);
    private static final long ART_HP      = Fixed.fromInt(180);
    private static final long ART_DEFENSE = Fixed.fromInt(5);

    // ── Параметри пострілу ────────────────────────────────────────────────
    /** Максимальна дальність обстрілу (auto + manual), Q47.16. */
    public static final long STRIKE_RANGE         = Fixed.fromInt(220);
    /** Час прицілювання до пострілу — 3 с при 40 Гц. */
    public static final int  STRIKE_AIM_TICKS     = 3 * TickRate.TICKS_PER_SECOND;
    /** Радіус вибуху (AoE), Q47.16. */
    public static final long STRIKE_SPLASH_RADIUS = Fixed.fromInt(45);
    /** Базовий урон у центрі вибуху, Q47.16. */
    public static final long STRIKE_DAMAGE        = Fixed.fromInt(120);
    /** Максимальний розкид снаряду від точки прицілу, Q47.16. */
    public static final long STRIKE_SPREAD        = Fixed.fromInt(18);
    /** Час перезарядки між пострілами — 8 с при 40 Гц. */
    public static final int  STRIKE_RELOAD_TICKS  = 8 * TickRate.TICKS_PER_SECOND;

    /** Швидкість снаряду в світових одиницях за секунду — використовує і сим, і рендер. */
    public static final float SHELL_SPEED_PX = 700f;

    /** Таймер перезарядки в тіках. Керується CombatManager. */
    public int reloadTicks = 0;
    /** Таймер прицілювання по поточній цілі, в тіках. Керується CombatManager. */
    public int aimTicks = 0;
    /**
     * Ручна ціль (виставляється ПКМ по ворогу поки артилерія виділена).
     * Поки жива і в радіусі — має пріоритет над авто-ціллю.
     */
    public Unit manualTarget = null;

    public Artillery(Team team, long rawX, long rawY) {
        super(team);
        maxHp               = ART_HP;
        hp                  = ART_HP;
        speedPerTick        = Fixed.divInt(ART_SPEED, TickRate.TICKS_PER_SECOND);
        damage              = 0;
        attackRange         = 0;
        // Артилерія ніколи не стріляє через звичайну атаку — кулдаун недосяжний.
        attackCooldownTicks = Integer.MAX_VALUE;
        defense             = ART_DEFENSE;
        sightRange          = Fixed.fromInt(500);
        setPosition(rawX, rawY);
    }

    @Override
    public void tick(long terrainSpeedMultiplier) {
        super.tick(terrainSpeedMultiplier);
        if (reloadTicks > 0) reloadTicks--;
    }

    /**
     * Id ручної цілі, прочитаний зі знімка і ще не перетворений на посилання.
     * Юніти створюються по черзі, тож у момент читання ціль може ще не існувати —
     * розв'язується другим проходом у {@code SimulationSnapshot}.
     */
    public int pendingManualTargetId = -1;

    @Override
    public void writeSnapshot(java.io.DataOutputStream out) throws java.io.IOException {
        super.writeSnapshot(out);
        out.writeInt(reloadTicks);
        out.writeInt(aimTicks);
        out.writeInt(manualTarget == null ? -1 : manualTarget.id);
    }

    @Override
    public void readSnapshot(java.io.DataInputStream in) throws java.io.IOException {
        super.readSnapshot(in);
        reloadTicks = in.readInt();
        aimTicks    = in.readInt();
        manualTarget = null;
        pendingManualTargetId = in.readInt();
    }

    public boolean isReady()     { return reloadTicks <= 0; }
    public void    startReload() { reloadTicks = STRIKE_RELOAD_TICKS; }

    /** Прогрес прицілювання 0..1 — тільки для індикатора. */
    public float aimProgress() {
        return Math.min(1f, aimTicks / (float) STRIKE_AIM_TICKS);
    }

    @Override public long   sizeFixed()      { return ART_SIZE_FIXED; }
    @Override public String getTexturePath() { return "units/artillery.png"; }
}
