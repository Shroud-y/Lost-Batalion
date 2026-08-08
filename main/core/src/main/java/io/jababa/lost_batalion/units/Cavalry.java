package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;

/**
 * Легка кіннота — чистий ближній бій.
 *
 * <p>Найшвидший юніт у грі і єдиний, хто б'є впритул: {@link #CAV_RANGE} трохи
 * більший за суму хітбоксів двох юнітів, тобто вдарити можна лише того, кого
 * фактично торкнувся. Стріляти вона не вміє взагалі — ні трасера, ні звуку
 * пострілу (див. {@link #usesRangedFx()}).
 *
 * <p>Головна риса — удар зі штовханням: ціль відлітає в той бік, куди
 * кіннота бігла. Це не косметика, а стан симуляції: збита позиція міняє і
 * дальність, і місцевість під ногами. Тому зсув рахується у {@link Fixed} і
 * застосовується в {@code CombatManager} на тіку удару, а не в рендері.
 *
 * <p>Ціною швидкості йде все інше: менше здоров'я за піхоту, слабший удар і
 * майже ніякої броні. Кіннота виграє, поки біжить і б'є ззаду; у лобовій
 * перестрілці вона гине, не діставши до стрільця.
 */
public class Cavalry extends Unit {

    /** Було 80; подвоєно разом з усіма (див. {@code Infantry.INF_HP}). */
    private static final long CAV_HP      = Fixed.fromInt(160);
    /** Одиниць за секунду. Піхота ходить 20, гармата 17 — кіннота вдвічі швидша. */
    private static final long CAV_SPEED   = Fixed.fromInt(40);
    private static final long CAV_DAMAGE  = Fixed.fromInt(26);
    private static final long CAV_DEFENSE = Fixed.fromInt(2);

    /**
     * Дальність удару.
     *
     * <p>Сума хітбоксів кінноти й піхоти — 16, і розштовхування не дасть їм
     * зійтись ближче; {@code CombatManager.standoff} додає ще 2 запасу. Тому
     * 20: це «впритул», а не дистанція. З меншим числом кіннота підбігала б до
     * цілі й не могла вдарити взагалі — її тримав би на відстані, більшій за
     * власну дальність, той самий код, що не дає юнітам налазити.
     */
    private static final long CAV_RANGE   = Fixed.fromInt(20);

    /** 0.7 с при 40 Гц. Б'є частіше за піхоту, але слабше. */
    private static final int  CAV_COOLDOWN_TICKS = 28;

    /**
     * Розміри — рівно піхотні: та сама фігура на полі, той самий хітбокс.
     * Кіннота відрізняється швидкістю й ударом, а не габаритом.
     */
    public static final long  CAV_SIZE_FIXED = Infantry.INF_SIZE_FIXED;
    public static final float CAV_SIZE       = Infantry.INF_SIZE;
    private static final long CAV_HIT_RADIUS = Infantry.INF_HIT_RADIUS;

    /**
     * Скільки одиниць ціль проїжджає від одного удару (Q47.16).
     *
     * <p>Зсув не миттєвий: він розтягується на весь проміжок до наступного
     * удару (див. {@code Unit.applyKnockback}), тож ціль повзе рівно, поки
     * кіннота на ній висить. Миттєвий стрибок навіть на кілька одиниць
     * читався як смикання — юніт то стояв, то підскакував раз на 0.7 с.
     */
    private static final long CAV_KNOCKBACK = Fixed.fromInt(5);

    /** Частка загального розмаху тремтіння для ударів кінноти. */
    private static final float CAV_SHAKE_SCALE = 0.65f;

    /** Мораль піхотна: вершник не хоробріший за стрільця, він лише швидший. */
    private static final long CAV_MORALE = Fixed.fromInt(100);

    /**
     * Удар з розгону лякає в півтора раза сильніше за постріл.
     *
     * <p>Те саме число, що й {@link #CAV_KNOCKBACK}, тільки для нервів: кіннота
     * дістає лише того, кого фактично збила конем, і це видно як з боку
     * шеренги, так і з боку того, кого збили. Саме цим вона й корисна проти
     * строю, який лобовою перестрілкою не пробити.
     */
    private static final long CAV_MORALE_SHOCK = Fixed.fromFloat(1.5f);

    public Cavalry(Team team, int owner, long rawX, long rawY) {
        super(team, owner);
        this.maxHp               = CAV_HP;
        this.hp                  = CAV_HP;
        this.maxMorale           = CAV_MORALE;
        this.morale              = CAV_MORALE;
        this.speedPerTick        = Fixed.divInt(CAV_SPEED, TickRate.TICKS_PER_SECOND);
        this.damage              = CAV_DAMAGE;
        this.defense             = CAV_DEFENSE;
        this.attackRange         = CAV_RANGE;
        this.attackCooldownTicks = CAV_COOLDOWN_TICKS;
        // Верхи не сховаєшся: ліс ховає кінноту гірше, ніж піхоту.
        this.stealthRating       = Fixed.fromFloat(0.10f);
        setPosition(rawX, rawY);
    }

    @Override public long  knockbackForce() { return CAV_KNOCKBACK; }
    @Override public long  moraleShock()    { return CAV_MORALE_SHOCK; }
    @Override public boolean usesRangedFx() { return false; }

    /** Верхи — тобто важка кіннота її не зносить (див. {@code Unit.mounted}). */
    @Override public boolean mounted()      { return true; }

    /**
     * Єдиний тип, чий бій тремтить: у сутичці впритул рух доречний, у
     * перестрілці — ні (див. {@code Unit.combatShakeScale}).
     */
    @Override public float combatShakeScale() { return CAV_SHAKE_SCALE; }

    @Override public long  sizeFixed()      { return CAV_SIZE_FIXED; }
    @Override public long  hitRadiusFixed() { return CAV_HIT_RADIUS; }

    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/light_cavalry_player.png"
            : "units/light_cavalry_enemy.png";
    }

    @Override public UnitType type() { return UnitType.CAVALRY; }
}
