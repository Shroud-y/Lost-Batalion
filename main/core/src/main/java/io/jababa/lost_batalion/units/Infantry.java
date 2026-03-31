package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;

public class Infantry extends Unit {

    private static final float INF_HP = 100f;
    private static final float INF_SPEED = 10f;
    private static final float INF_DAMAGE = 15f;
    private static final float INF_RANGE = 40f;
    private static final float INF_COOLDOWN = 1.2f;

    public static final float INF_SIZE = 8f;
    public static final float INF_HIT_RADIUS = 8f;

    public Infantry(Team team, float x, float y) {
        super(team);
        this.maxHp = INF_HP;
        this.hp = INF_HP;
        this.speed = INF_SPEED;
        this.damage = INF_DAMAGE;
        this.attackRange = INF_RANGE;
        this.attackCooldown = INF_COOLDOWN;
        this.position.set(x, y);
    }

    @Override public float  getSize() { return INF_SIZE; }
    @Override public float  getHitRadius() { return INF_HIT_RADIUS; }

    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/infantry_player.png"
            : "units/infantry_enemy.png";
    }
}
