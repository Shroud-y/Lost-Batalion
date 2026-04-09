package io.jababa.lost_batalion.units;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.Vector2;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainType;

public abstract class Unit {

    public final Team team;
    public float maxHp;
    public float hp;
    public float speed;
    public float damage;
    public float attackRange;
    public float attackCooldown;
    public float attackTimer = 0f;

    /**
     * Базовий захист юніта — віднімається від вхідного damage перед множником.
     * Значення 0..damage атакуючого (не може зробити damage від'ємним).
     */
    public float defense = 0f;

    public float sightRange   = 520f;
    public float stealthRating = 0f;
    public boolean visibleToPlayer = true;

    public  final Vector2 position = new Vector2();
    private final Vector2 target   = new Vector2();
    private boolean moving = false;

    public boolean selected = false;
    public boolean alive    = true;

    protected Unit(Team team) { this.team = team; }

    public void moveTo(float worldX, float worldY) {
        target.set(worldX, worldY);
        moving = true;
    }

    public void update(float delta) {

        if (!alive) return;
        if (attackTimer > 0) {
            attackTimer -= delta;
            // Тимчасово:
            if (attackTimer <= 0) {
                Gdx.app.log("UNIT", team + " ready to attack!");
            }
        }
        if (!alive) return;

        if (moving) {
            float dist = position.dst(target);
            if (dist < 2f) {
                position.set(target);
                moving = false;
            } else {
                float step = speed * delta;
                float nx = position.x + (target.x - position.x) / dist * step;
                float ny = position.y + (target.y - position.y) / dist * step;
                position.set(nx, ny);
            }
        }

        if (attackTimer > 0) attackTimer -= delta;
    }

    public boolean isMoving() { return moving; }

    /**
     * Проста атака без урахування місцевості.
     * Використовується всередині attack(Unit) та для авто-бою без TerrainMaskManager.
     */
    public boolean takeDamage(float rawDamage) {
        float effective = Math.max(0f, rawDamage - defense);
        hp -= effective;
        if (hp <= 0) { hp = 0; alive = false; }
        return !alive;
    }

    /**
     * Атака з урахуванням місцевості.
     *
     * @param rawDamage       базовий damage атакуючого
     * @param defenseMultiplier множник захисту від TerrainCombatModifier
     *                          (> 1 = захищений, < 1 = вразливий)
     */
    public boolean takeDamageWithTerrain(float rawDamage, float defenseMultiplier) {
        // 1. Віднімаємо armor
        float afterArmor = Math.max(0f, rawDamage - defense);
        // 2. Застосовуємо множник місцевості
        //    defenseMultiplier > 1 → ділимо на нього (захист збільшується)
        //    defenseMultiplier < 1 → ціль вразлива (damage збільшується)
        float effective = afterArmor / defenseMultiplier;
        hp -= effective;
        if (hp <= 0) { hp = 0; alive = false; }
        return !alive;
    }

    /**
     * Базова атака (без місцевості) — використовується якщо TerrainMaskManager недоступний.
     */
    public boolean attack(Unit target) {
        if (attackTimer > 0) return false;
        target.takeDamage(damage);
        attackTimer = attackCooldown;
        return true;
    }

    /**
     * Атака з урахуванням місцевості.
     * Викликається з CombatManager який знає TerrainType обох юнітів.
     */
    public boolean attackWithTerrain(Unit target, float defenseMultiplier) {
        if (attackTimer > 0) return false;
        target.takeDamageWithTerrain(damage, defenseMultiplier);
        attackTimer = attackCooldown;
        return true;
    }

    public void stopMoving() {
        target.set(position);
        moving = false;
    }

    public boolean canAttack() { return attackTimer <= 0; }

    public abstract float  getSize();
    public float getHitRadius() { return getSize() * 0.5f; }
    public abstract String getTexturePath();
}
