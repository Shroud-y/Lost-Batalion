package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;

/**
 * Важка кіннота — таран, а не рейд.
 *
 * <p>Та сама сутичка впритул, що й у {@link Cavalry}, але з іншим призначенням:
 * легка кіннота живе швидкістю й заходить у тил, важка йде в лоб і ламає стрій
 * вагою. Звідси і числа, і швидкість {@link #HCAV_SPEED} = 30 одиниць/с —
 * посередині між піхотою (20) і легкою кіннотою (40), і це НЕ круглий компроміс,
 * а межа {@code Unit.outrunsLine()}: 30 у неї не входить, тож важка кіннота
 * рахується строєм і не відривається від піхоти на марші. Саме те, чого від
 * тарана й треба — прийти разом із усіма, а не самому.
 *
 * <p>Головна відмінність від легкої — <b>кого вона штовхає</b>. Штовхає всіх,
 * окрім верхових: вершника конем не зіб'єш, він теж на коні (див.
 * {@link #shovesMounted()}). Проти шеренги піхоти це найсильніший прийом у грі,
 * проти чужої кінноти — звичайний ближній бій без розриву строю.
 *
 * <p>Ціна ваги — темп: кулдаун {@link #HCAV_COOLDOWN_TICKS} ціла секунда проти
 * 0.7 у легкої, тобто урон за секунду росте не так, як число на іконці.
 */
public class HeavyCavalry extends Unit {

    /** Півтора корпуси легкої кінноти (160): таран мусить доїхати до строю. */
    private static final long HCAV_HP      = Fixed.fromInt(240);

    /**
     * Одиниць за секунду. Рівно на межі {@code outrunsLine()} (30) — див.
     * коментар класу; піднімати не можна, не переглянувши поведінку бота.
     */
    private static final long HCAV_SPEED   = Fixed.fromInt(30);

    private static final long HCAV_DAMAGE  = Fixed.fromInt(40);
    /** Броня важча за все, крім гарматного щита (5). */
    private static final long HCAV_DEFENSE = Fixed.fromInt(4);

    /** «Впритул» — те саме число й з тих самих причин, що {@code Cavalry.CAV_RANGE}. */
    private static final long HCAV_RANGE   = Fixed.fromInt(20);

    /** 1.0 с при 40 Гц. Б'є рідше за легку (0.7 с), зате вдвічі важче. */
    private static final int  HCAV_COOLDOWN_TICKS = 40;

    /** Габарит піхотний, як і в легкої: різниця у вазі, а не в хітбоксі. */
    public static final long  HCAV_SIZE_FIXED = Infantry.INF_SIZE_FIXED;
    public static final float HCAV_SIZE       = Infantry.INF_SIZE;
    private static final long HCAV_HIT_RADIUS = Infantry.INF_HIT_RADIUS;

    /** Збиває далі за легку кінноту (5) — на те й таран. */
    private static final long HCAV_KNOCKBACK = Fixed.fromInt(8);

    /** Той самий розмах тремтіння, що в легкої: сутичка одна й та сама. */
    private static final float HCAV_SHAKE_SCALE = 0.65f;

    /** Латник тримається краще за стрільця — єдиний, у кого моралі більше за 100. */
    private static final long HCAV_MORALE = Fixed.fromInt(120);

    /** Лякає сильніше за легку (1.5): на тебе їде те, що вже зім'яло сусіда. */
    private static final long HCAV_MORALE_SHOCK = Fixed.fromFloat(1.8f);

    public HeavyCavalry(Team team, long rawX, long rawY) {
        super(team);
        this.maxHp               = HCAV_HP;
        this.hp                  = HCAV_HP;
        this.maxMorale           = HCAV_MORALE;
        this.morale              = HCAV_MORALE;
        this.speedPerTick        = Fixed.divInt(HCAV_SPEED, TickRate.TICKS_PER_SECOND);
        this.damage              = HCAV_DAMAGE;
        this.defense             = HCAV_DEFENSE;
        this.attackRange         = HCAV_RANGE;
        this.attackCooldownTicks = HCAV_COOLDOWN_TICKS;
        // Верхи не сховаєшся, а в латах — тим паче: гірше за легку кінноту.
        this.stealthRating       = Fixed.fromFloat(0.05f);
        setPosition(rawX, rawY);
    }

    @Override public long  knockbackForce() { return HCAV_KNOCKBACK; }
    @Override public long  moraleShock()    { return HCAV_MORALE_SHOCK; }
    @Override public boolean usesRangedFx() { return false; }

    @Override public boolean mounted()      { return true; }

    /**
     * Верхових не штовхає: удар важкої кінноти зносить піхоту, а вершник
     * зустрічає його конем і лишається на місці.
     */
    @Override public boolean shovesMounted() { return false; }

    @Override public float combatShakeScale() { return HCAV_SHAKE_SCALE; }

    @Override public long  sizeFixed()      { return HCAV_SIZE_FIXED; }
    @Override public long  hitRadiusFixed() { return HCAV_HIT_RADIUS; }

    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/heavy_cavalry_player.png"
            : "units/heavy_cavalry_enemy.png";
    }

    @Override public UnitType type() { return UnitType.HEAVY_CAVALRY; }
}
