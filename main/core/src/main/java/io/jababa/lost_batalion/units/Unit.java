package io.jababa.lost_batalion.units;

import com.badlogic.gdx.math.Vector2;
import io.jababa.lost_batalion.Team;

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
     * Дальність зору в пікселях світу.
     * Скільки може «бачити» цей юніт навколо себе.
     */
    public float sightRange = 120f;

    /**
     * Рейтинг скритності [0..1]: 0 = повністю видимий, 1 = майже невидимий.
     * Ворог бачить цього юніта лише якщо:
     *   distToEnemy < enemy.sightRange * (1 - this.stealthRating)
     */
    public float stealthRating = 0f;

    /**
     * Кешований результат: чи видимий цей юніт для PLAYER прямо зараз.
     * Виставляє VisibilitySystem кожен кадр.
     */
    public boolean visibleToPlayer = true;

    public  final Vector2 position = new Vector2();
    private final Vector2 target   = new Vector2();
    private boolean moving = false;

    public boolean selected = false;
    public boolean alive = true;

    protected Unit(Team team) { this.team = team; }

    public void moveTo(float worldX, float worldY) {
        target.set(worldX, worldY);
        moving = true;
    }

    public void update(float delta) {
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

    public boolean takeDamage(float amount) {
        hp -= amount;
        if (hp <= 0) { hp = 0; alive = false; }
        return !alive;
    }

    public boolean attack(Unit target) {
        if (attackTimer > 0) return false;
        target.takeDamage(damage);
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
