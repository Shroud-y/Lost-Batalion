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
    private static final long INF_RANGE    = Fixed.fromInt(40);
    /** 1.2 с при 40 Гц — рівно 48 тіків. */
    private static final int  INF_COOLDOWN_TICKS = 48;

    /** Розмір у світових одиницях (Q47.16). */
    public static final long INF_SIZE_FIXED = Fixed.fromInt(10);
    /** Той самий розмір у пікселях — для розкладки й рендеру. */
    public static final float INF_SIZE       = 10f;
    private static final long INF_HIT_RADIUS = Fixed.fromInt(8);

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

    @Override public long sizeFixed()      { return INF_SIZE_FIXED; }
    @Override public long hitRadiusFixed() { return INF_HIT_RADIUS; }

    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/infantry_player.png"
            : "units/infantry_enemy.png";
    }
}
