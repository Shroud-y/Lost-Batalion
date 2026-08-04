package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;

public class Infantry extends Unit {

    private static final long INF_HP       = Fixed.fromInt(100);
    /** Одиниць за секунду; у полі юніта зберігається вже перерахованим на тік. */
    private static final long INF_SPEED    = Fixed.fromInt(20);
    private static final long INF_DAMAGE   = Fixed.fromInt(15);
    /** Базовий armor — зменшує кожен удар на 3. */
    private static final long INF_DEFENSE  = Fixed.fromInt(3);

    /**
     * Дальність пострілу.
     *
     * <p>Було 40 при огляді 520 — тобто ворога видно за пів карти, а стріляти
     * можна лише впритул, і бій вироджувався в дві злиплі купки, де ні висота,
     * ні фланг не важать, бо на 40 одиницях це той самий піксель.
     *
     * <p>Саме число невелике навмисно: це мушкет, а не гвинтівка. Далекий
     * постріл дозволений, але майже нічого не дає — за це відповідає
     * {@link #hitChanceAt}, а не межа дальності.
     */
    private static final long INF_RANGE    = Fixed.fromInt(90);
    /** 1.2 с при 40 Гц — рівно 48 тіків. */
    private static final int  INF_COOLDOWN_TICKS = 48;

    /** Розмір у світових одиницях (Q47.16). */
    public static final long INF_SIZE_FIXED = Fixed.fromInt(15);
    /** Той самий розмір у пікселях — для розкладки й рендеру. */
    public static final float INF_SIZE       = 10f;
    /** Публічний, бо кіннота свідомо повторює піхотний габарит. */
    public static final long INF_HIT_RADIUS = Fixed.fromInt(8);

    public Infantry(Team team, long rawX, long rawY) {
        super(team);
        this.maxHp               = INF_HP;
        this.hp                  = INF_HP;
        this.speedPerTick        = Fixed.divInt(INF_SPEED, TickRate.TICKS_PER_SECOND);
        this.damage              = INF_DAMAGE;
        this.defense             = INF_DEFENSE;
        this.attackRange         = INF_RANGE;
        this.attackCooldownTicks = INF_COOLDOWN_TICKS;
        // terrain multipliers (forest/lowlands) now matter
        this.stealthRating       = Fixed.fromFloat(0.20f);
        setPosition(rawX, rawY);
    }

    // ── Влучність ─────────────────────────────────────────────────────────

    /**
     * Доки постріл б'є напевно.
     *
     * <p>Впритул мушкет не мажe, і сутичка на цій дистанції має лишитись такою
     * самою, якою була до появи розкиду, — інакше зміна дальності тихо
     * послабила б увесь ближній бій разом із далеким.
     */
    private static final long INF_POINT_BLANK   = Fixed.fromInt(30);

    /** Шанс влучити на самій межі дальності. */
    private static final long INF_MIN_HIT_CHANCE = Fixed.fromFloat(0.35f);

    /**
     * Шанс влучити: {@link Fixed#ONE} до {@link #INF_POINT_BLANK}, далі лінійно
     * вниз до {@link #INF_MIN_HIT_CHANCE} на межі {@link #INF_RANGE}.
     *
     * <p>Лінійно, а не по кривій: гравець мусить уміти оцінити «підійти ще
     * трохи» на око, і крива, яку не видно, тут нічого б не додала.
     *
     * <p>Спад рахується від {@code dist - INF_POINT_BLANK}, тож ділення завжди
     * на сталий ненульовий проміжок — окремої перевірки на нуль не треба.
     */
    @Override
    public long hitChanceAt(long dist) {
        if (dist <= INF_POINT_BLANK) return Fixed.ONE;
        if (dist >= INF_RANGE)       return INF_MIN_HIT_CHANCE;

        long span = INF_RANGE - INF_POINT_BLANK;
        long over = dist - INF_POINT_BLANK;
        long drop = Fixed.mul(Fixed.div(over, span), Fixed.ONE - INF_MIN_HIT_CHANCE);
        return Fixed.ONE - drop;
    }

    @Override public long sizeFixed()      { return INF_SIZE_FIXED; }
    @Override public long hitRadiusFixed() { return INF_HIT_RADIUS; }

    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/infantry_player.png"
            : "units/infantry_enemy.png";
    }
}
