package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;

public class Infantry extends Unit {

    private static final float INF_HP       = 100f;
    private static final float INF_SPEED    = 20f;
    private static final float INF_DAMAGE   = 15f;
    private static final float INF_RANGE    = 40f;
    private static final float INF_COOLDOWN = 1.2f;

    /** Дальність зору піхоти на відкритій місцевості */
    private static final float INF_SIGHT    = 130f;

    /**
     * Стандартна скритність піхоти.
     * В лісі множиться на FOREST_STEALTH_MULTIPLIER у VisibilitySystem.
     * 0.15 → ворог бачить піхоту коли ближче ніж sightRange × 0.85
     */
    private static final float INF_STEALTH  = 0.15f;

    public static final float INF_SIZE       = 10f;
    public static final float INF_HIT_RADIUS = 8f;

    public Infantry(Team team, float x, float y) {
        super(team);
        this.maxHp        = INF_HP;
        this.hp           = INF_HP;
        this.speed        = INF_SPEED;
        this.damage       = INF_DAMAGE;
        this.attackRange  = INF_RANGE;
        this.attackCooldown = INF_COOLDOWN;
        this.sightRange   = INF_SIGHT;
        this.stealthRating = INF_STEALTH;
        this.position.set(x, y);
    }

    @Override public float  getSize()      { return INF_SIZE; }
    @Override public float  getHitRadius() { return INF_HIT_RADIUS; }

    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/infantry_player.png"
            : "units/infantry_enemy.png";
    }
}
