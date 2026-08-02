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

    private static final long CAV_HP      = Fixed.fromInt(80);
    /** Одиниць за секунду. Піхота ходить 20, гармата 17 — кіннота вдвічі швидша. */
    private static final long CAV_SPEED   = Fixed.fromInt(40);
    private static final long CAV_DAMAGE  = Fixed.fromInt(26);
    private static final long CAV_DEFENSE = Fixed.fromInt(2);

    /**
     * Дальність удару.
     *
     * <p>Сума хітбоксів кінноти й піхоти — 15, і розштовхування не дасть їм
     * зійтись ближче. Тому 18: це «впритул плюс запас на тремтіння», а не
     * дистанція. З меншим числом кіннота підбігала б до цілі й не могла вдарити
     * взагалі — її відпихав би той самий код, що не дає юнітам налазити.
     */
    private static final long CAV_RANGE   = Fixed.fromInt(18);

    /** 0.7 с при 40 Гц. Б'є частіше за піхоту, але слабше. */
    private static final int  CAV_COOLDOWN_TICKS = 28;

    /** Розмір у світових одиницях (Q47.16) і в пікселях. */
    public static final long  CAV_SIZE_FIXED = Fixed.fromInt(15);
    public static final float CAV_SIZE       = 14f;
    private static final long CAV_HIT_RADIUS = Fixed.fromInt(7);
    /** Спрайт трохи більший за хітбокс: кінь із вершником довший за піхотинця. */
    private static final float CAV_RENDER_SIZE = 18f;

    /**
     * Скільки одиниць ціль проїжджає від одного удару (Q47.16).
     *
     * <p>Зсув не миттєвий: він розтягується на весь проміжок до наступного
     * удару (див. {@code Unit.applyKnockback}), тож ціль повзе рівно, поки
     * кіннота на ній висить. Миттєвий стрибок навіть на кілька одиниць
     * читався як смикання — юніт то стояв, то підскакував раз на 0.7 с.
     */
    private static final long CAV_KNOCKBACK = Fixed.fromInt(5);

    public Cavalry(Team team, long rawX, long rawY) {
        super(team);
        this.maxHp               = CAV_HP;
        this.hp                  = CAV_HP;
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
    @Override public boolean usesRangedFx() { return false; }

    @Override public long  sizeFixed()      { return CAV_SIZE_FIXED; }
    @Override public float renderSizePx()   { return CAV_RENDER_SIZE; }
    @Override public long  hitRadiusFixed() { return CAV_HIT_RADIUS; }

    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/cavalry_player.png"
            : "units/cavalry_enemy.png";
    }
}
