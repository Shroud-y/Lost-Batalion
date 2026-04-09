package io.jababa.lost_batalion.units;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.screens.effects.ShotEffect;
import io.jababa.lost_batalion.screens.effects.TargetPopupManager;
import io.jababa.lost_batalion.terrain.TerrainCombatModifier;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.terrain.TerrainType;

public class CombatManager {

    private static final int   MAX_SHOTS        = 64;
    private static final float LINE_SPACING     = 12f;
    private static final float SPREAD_THRESHOLD =  3f;

    private final UnitManager        unitManager;
    private final TerrainMaskManager terrainMask;

    private final Array<ShotEffect>  shots  = new Array<>();
    private final Array<AttackOrder> orders = new Array<>();
    private final Array<AttackGroup> groups = new Array<>();

    private TargetPopupManager popupManager;
    private Sound              shotSound;
    private final float        soundVolume = 0.35f;

    public CombatManager(UnitManager unitManager) {
        this(unitManager, null);
    }

    public CombatManager(UnitManager unitManager, TerrainMaskManager terrainMask) {
        this.unitManager = unitManager;
        this.terrainMask = terrainMask;

        for (int i = 0; i < MAX_SHOTS; i++) shots.add(new ShotEffect());
        try {
            if (Gdx.files.internal("sounds/shot.wav").exists())
                shotSound = Gdx.audio.newSound(Gdx.files.internal("sounds/shot.wav"));
        } catch (Exception ignored) {}
        popupManager = new TargetPopupManager("ui/target_icon.png");
    }

    public void orderAttack(Unit enemy) {
        Array<Unit> selected = unitManager.getSelectedUnits();
        if (selected.size == 0) return;
        // if (enemy != null && !enemy.visibleToPlayer) return; // тимчасово вимкнути

        if (enemy != null && enemy.alive)
            popupManager.spawn(enemy.position.x, enemy.position.y + enemy.getSize() / 2f);

        for (int i = orders.size - 1; i >= 0; i--) {
            Unit att = orders.get(i).att;
            for (int j = 0; j < selected.size; j++) {
                if (att == selected.get(j)) { orders.removeIndex(i); break; }
            }
        }
        for (int i = groups.size - 1; i >= 0; i--)
            if (groups.get(i).isEmpty()) groups.removeIndex(i);

        formLineAndOrder(selected, enemy);
    }

    public void cancelAttackOrders(Array<Unit> units) {
        for (int i = orders.size - 1; i >= 0; i--) {
            Unit att = orders.get(i).att;
            for (int j = 0; j < units.size; j++) {
                if (att == units.get(j)) { orders.removeIndex(i); break; }
            }
        }
        for (int i = groups.size - 1; i >= 0; i--)
            if (groups.get(i).isEmpty()) groups.removeIndex(i);
    }

    public void update(float delta) {
        Array<Unit> all = unitManager.getAllUnits();

        for (int i = 0; i < shots.size; i++) shots.get(i).update(delta);

        for (int i = groups.size - 1; i >= 0; i--) {
            AttackGroup g = groups.get(i);
            g.cleanup();
            if (g.isEmpty()) { groups.removeIndex(i); continue; }
            g.update();
        }

        for (int i = orders.size - 1; i >= 0; i--) {
            AttackOrder o = orders.get(i);
            if (!o.att.alive || !o.target.alive || !o.target.visibleToPlayer) {
                orders.removeIndex(i); continue;
            }
            processOrder(o);
        }

        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!u.alive || hasManualOrder(u)) continue;
            Unit nearest = findNearestVisibleEnemy(u, all);
            if (nearest != null) tryAttack(u, nearest);
        }

        for (int i = orders.size - 1; i >= 0; i--) {
            AttackOrder o = orders.get(i);
            if (!o.att.alive || !o.target.alive) orders.removeIndex(i);
        }
    }

    public void drawShots(ShapeRenderer shapes) {
        for (int i = 0; i < shots.size; i++) {
            ShotEffect s = shots.get(i);
            if (s.active) s.draw(shapes);
        }
    }

    public void updatePopups(float delta) { popupManager.update(delta); }
    public void drawPopups(SpriteBatch batch) { popupManager.draw(batch); }

    public Unit tryGetEnemyAtPoint(float worldX, float worldY) {
        if (!unitManager.hasSelection()) return null;
        Array<Unit> all = unitManager.getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (u.team == Team.PLAYER || !u.alive || !u.visibleToPlayer) continue;
            if (Vector2.dst(worldX, worldY, u.position.x, u.position.y) <= u.getHitRadius())
                return u;
        }
        return null;
    }

    public void dispose() {
        if (shotSound    != null) shotSound.dispose();
        if (popupManager != null) popupManager.dispose();
    }

    // ── Приватне ─────────────────────────────────────────────────────────

    private void formLineAndOrder(Array<Unit> units, Unit enemy) {
        int count = units.size;
        if (count == 0) return;

        float cx = 0, cy = 0;
        for (int i = 0; i < count; i++) { cx += units.get(i).position.x; cy += units.get(i).position.y; }
        cx /= count; cy /= count;

        float vx = enemy.position.x - cx, vy = enemy.position.y - cy;
        float len = (float) Math.sqrt(vx*vx + vy*vy);
        if (len < 0.01f) len = 1f;
        float nx = vx/len, ny = vy/len, px = -ny, py = nx;

        Array<Unit> sorted = new Array<>(units);
        sorted.sort((u1, u2) -> Float.compare(
            u1.position.x*px + u1.position.y*py,
            u2.position.x*px + u2.position.y*py));

        float stopDist = sorted.get(0).attackRange * 0.85f;
        AttackGroup group = new AttackGroup(enemy);

        for (int i = 0; i < count; i++) {
            Unit u = sorted.get(i);
            float offset = (i - (count-1)/2f) * LINE_SPACING;
            float sx = cx + px*offset, sy = cy + py*offset;
            float fx = enemy.position.x - nx*stopDist + px*offset;
            float fy = enemy.position.y - ny*stopDist + py*offset;
            u.moveTo(sx, sy);
            AttackOrder order = new AttackOrder(u, enemy, sx, sy, fx, fy, group);
            orders.add(order);
            group.addOrder(order);
        }
        groups.add(group);
    }

    private void processOrder(AttackOrder order) {
        Unit attacker = order.att, target = order.target;
        if (attacker.position.dst(target.position) <= attacker.attackRange) {
            attacker.stopMoving();
            tryAttack(attacker, target);
            return;
        }
        switch (order.group.phase) {
            case SPREAD: {
                if (attacker.position.dst(order.spreadX, order.spreadY) > SPREAD_THRESHOLD)
                    attacker.moveTo(order.spreadX, order.spreadY);
                else attacker.stopMoving();
                break;
            }
            case ADVANCE: {
                float vx = target.position.x - attacker.position.x;
                float vy = target.position.y - attacker.position.y;
                float l  = (float) Math.sqrt(vx*vx + vy*vy);
                if (l > 0.01f) {
                    float fnx = vx/l, fny = vy/l, fpx = -fny, fpy = fnx;
                    attacker.moveTo(
                        target.position.x - fnx*attacker.attackRange*0.85f + fpx*order.perpOffset,
                        target.position.y - fny*attacker.attackRange*0.85f + fpy*order.perpOffset);
                }
                break;
            }
        }
    }

    /**
     * Атака з урахуванням місцевості.
     * Якщо terrainMask є — рахуємо TerrainCombatModifier і застосовуємо.
     * Якщо немає — звичайна атака.
     */
    private void tryAttack(Unit attacker, Unit target) {
        if (!attacker.canAttack()) {
            Gdx.app.log("COMBAT", "canAttack=false, timer=" + attacker.attackTimer);
            return;
        }
        float dist = attacker.position.dst(target.position);
        if (dist > attacker.attackRange) {
            Gdx.app.log("COMBAT", "too far: dist=" + dist + " range=" + attacker.attackRange);
            return;
        }

        if (terrainMask != null) {
            TerrainType atkTerrain = terrainMask.getTerrainAt(
                attacker.position.x, attacker.position.y);
            TerrainType defTerrain = terrainMask.getTerrainAt(
                target.position.x, target.position.y);
            float defMult = TerrainCombatModifier.getDefenseMultiplier(atkTerrain, defTerrain);

            Gdx.app.log("COMBAT",
                "ATK terrain=" + atkTerrain +
                    " DEF terrain=" + defTerrain +
                    " mult=" + defMult +
                    " hp_before=" + target.hp);

            attacker.attackWithTerrain(target, defMult);

            Gdx.app.log("COMBAT", "hp_after=" + target.hp);
        } else {
            Gdx.app.log("COMBAT", "terrainMask is NULL — plain attack");
            attacker.attack(target);
        }

        spawnShot(attacker, target);
        playShot();
    }

    private Unit findNearestVisibleEnemy(Unit unit, Array<Unit> all) {
        Unit nearest = null;
        float minDist = unit.attackRange;
        for (int i = 0; i < all.size; i++) {
            Unit other = all.get(i);
            if (!other.alive || other.team == unit.team) continue;
            // Тимчасово вимкнути фільтр видимості для тестування:
            // if (unit.team == Team.PLAYER && !other.visibleToPlayer) continue;
            float d = unit.position.dst(other.position);
            if (d <= minDist) { minDist = d; nearest = other; }
        }
        return nearest;
    }

    private boolean hasManualOrder(Unit u) {
        for (int i = 0; i < orders.size; i++)
            if (orders.get(i).att == u) return true;
        return false;
    }

    private void spawnShot(Unit from, Unit to) {
        for (int i = 0; i < shots.size; i++) {
            ShotEffect s = shots.get(i);
            if (!s.active) { s.show(from.position.x, from.position.y, to.position.x, to.position.y); return; }
        }
        shots.get(0).show(from.position.x, from.position.y, to.position.x, to.position.y);
    }

    private void playShot() { if (shotSound != null) shotSound.play(soundVolume); }

    private static class AttackGroup {
        enum Phase { SPREAD, ADVANCE }
        final Unit target; final Array<AttackOrder> orders = new Array<>();
        Phase phase = Phase.SPREAD;
        AttackGroup(Unit t) { target = t; }
        void addOrder(AttackOrder o) { orders.add(o); }
        void cleanup() {
            for (int i = orders.size-1; i >= 0; i--) {
                AttackOrder o = orders.get(i);
                if (!o.att.alive || !o.target.alive) orders.removeIndex(i);
            }
        }
        boolean isEmpty() { return orders.size == 0; }
        void update() {
            if (phase == Phase.ADVANCE) return;
            for (int i = 0; i < orders.size; i++) {
                AttackOrder o = orders.get(i);
                if (!o.att.alive) continue;
                if (o.att.position.dst(o.spreadX, o.spreadY) > 3f) return;
            }
            phase = Phase.ADVANCE;
        }
    }

    private static class AttackOrder {
        final Unit att, target; final float spreadX, spreadY, finalX, finalY, perpOffset;
        final AttackGroup group;
        AttackOrder(Unit a, Unit t, float sx, float sy, float fx, float fy, AttackGroup g) {
            att = a; target = t; spreadX = sx; spreadY = sy; finalX = fx; finalY = fy; group = g;
            float vx = t.position.x-a.position.x, vy = t.position.y-a.position.y;
            float len = (float)Math.sqrt(vx*vx+vy*vy); if (len<0.01f) len=1f;
            float nx=vx/len,ny=vy/len,px=-ny,py=nx;
            perpOffset = (fx-t.position.x)*px + (fy-t.position.y)*py;
        }
        // att використовується напряму через поле
    }
}
