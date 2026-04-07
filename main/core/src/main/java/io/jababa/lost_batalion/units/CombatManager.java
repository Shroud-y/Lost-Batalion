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

public class CombatManager {

    private static final int   MAX_SHOTS    = 64;
    private static final float LINE_SPACING  = 12f;
    private static final float SPREAD_THRESHOLD = 3f;

    private TargetPopupManager popupManager;

    private final UnitManager unitManager;
    private final Array<ShotEffect>   shots  = new Array<>();
    private final Array<AttackOrder>  orders = new Array<>();
    private final Array<AttackGroup>  groups = new Array<>();

    private Sound shotSound;
    private final float soundVolume = 0.35f;

    public CombatManager(UnitManager unitManager) {
        this.unitManager = unitManager;
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

        // Дозволяємо ручний наказ атаки лише на видимого ворога
        if (enemy != null && !enemy.visibleToPlayer) return;

        if (enemy != null && enemy.alive) {
            popupManager.spawn(enemy.position.x, enemy.position.y + enemy.getSize() / 2f);
        }

        for (int i = orders.size - 1; i >= 0; i--) {
            Unit att = orders.get(i).attacker;
            for (int j = 0; j < selected.size; j++) {
                if (att == selected.get(j)) { orders.removeIndex(i); break; }
            }
        }

        for (int i = groups.size - 1; i >= 0; i--) {
            if (groups.get(i).isEmpty()) groups.removeIndex(i);
        }

        formLineAndOrder(selected, enemy);
    }

    private void formLineAndOrder(Array<Unit> units, Unit enemy) {
        int count = units.size;
        if (count == 0) return;

        float cx = 0, cy = 0;
        for (int i = 0; i < count; i++) {
            cx += units.get(i).position.x;
            cy += units.get(i).position.y;
        }
        cx /= count; cy /= count;

        float vx = enemy.position.x - cx;
        float vy = enemy.position.y - cy;
        float len = (float) Math.sqrt(vx * vx + vy * vy);
        if (len < 0.01f) len = 1f;
        float nx = vx / len, ny = vy / len;
        float px = -ny,      py =  nx;

        Array<Unit> sortedUnits = new Array<>(units);
        sortedUnits.sort((u1, u2) -> {
            float proj1 = u1.position.x * px + u1.position.y * py;
            float proj2 = u2.position.x * px + u2.position.y * py;
            return Float.compare(proj1, proj2);
        });

        float stopDist = sortedUnits.get(0).attackRange * 0.85f;
        AttackGroup group = new AttackGroup(enemy);

        for (int i = 0; i < count; i++) {
            Unit u = sortedUnits.get(i);
            float offset = (i - (count - 1) / 2f) * LINE_SPACING;

            float spreadX = cx + px * offset;
            float spreadY = cy + py * offset;
            float finalX  = enemy.position.x - nx * stopDist + px * offset;
            float finalY  = enemy.position.y - ny * stopDist + py * offset;

            u.moveTo(spreadX, spreadY);

            AttackOrder order = new AttackOrder(u, enemy, spreadX, spreadY, finalX, finalY, group);
            orders.add(order);
            group.addOrder(order);
        }

        groups.add(group);
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
            AttackOrder order = orders.get(i);
            if (!order.attacker.alive || !order.target.alive) {
                orders.removeIndex(i); continue;
            }
            // Якщо ціль стала невидимою — скасовуємо наказ
            if (!order.target.visibleToPlayer) {
                orders.removeIndex(i); continue;
            }
            processOrder(order);
        }

        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!u.alive || hasManualOrder(u)) continue;
            // Автоатака лише видимих ворогів
            Unit nearest = findNearestVisibleEnemy(u, all);
            if (nearest != null) tryAttack(u, nearest);
        }

        for (int i = orders.size - 1; i >= 0; i--) {
            AttackOrder o = orders.get(i);
            if (!o.attacker.alive || !o.target.alive) orders.removeIndex(i);
        }
    }

    public void drawShots(ShapeRenderer shapes) {
        for (int i = 0; i < shots.size; i++) {
            ShotEffect s = shots.get(i);
            if (s.active) s.draw(shapes);
        }
    }

    /**
     * Повертає ворожий юніт під курсором, але лише якщо він видимий гравцеві.
     */
    public Unit tryGetEnemyAtPoint(float worldX, float worldY) {
        if (!unitManager.hasSelection()) return null;
        Array<Unit> all = unitManager.getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (u.team == Team.PLAYER || !u.alive) continue;
            if (!u.visibleToPlayer) continue;  // приховано туманом
            float dist = Vector2.dst(worldX, worldY, u.position.x, u.position.y);
            if (dist <= u.getHitRadius()) return u;
        }
        return null;
    }

    public void dispose() {
        if (shotSound != null) shotSound.dispose();
        if (popupManager != null) popupManager.dispose();
    }

    private void processOrder(AttackOrder order) {
        Unit attacker = order.attacker;
        Unit target   = order.target;
        float distToEnemy = attacker.position.dst(target.position);

        if (distToEnemy <= attacker.attackRange) {
            attacker.stopMoving();
            tryAttack(attacker, target);
            return;
        }

        switch (order.group.phase) {
            case SPREAD: {
                float dSpread = attacker.position.dst(order.spreadX, order.spreadY);
                if (dSpread > SPREAD_THRESHOLD) attacker.moveTo(order.spreadX, order.spreadY);
                else                            attacker.stopMoving();
                break;
            }
            case ADVANCE: {
                float vx = target.position.x - attacker.position.x;
                float vy = target.position.y - attacker.position.y;
                float len = (float) Math.sqrt(vx * vx + vy * vy);
                if (len > 0.01f) {
                    float fnx = vx / len, fny = vy / len;
                    float fpx = -fny,     fpy =  fnx;
                    float targetX = target.position.x - fnx * attacker.attackRange * 0.85f + fpx * order.perpOffset;
                    float targetY = target.position.y - fny * attacker.attackRange * 0.85f + fpy * order.perpOffset;
                    attacker.moveTo(targetX, targetY);
                }
                break;
            }
        }
    }

    private void tryAttack(Unit attacker, Unit target) {
        if (!attacker.canAttack()) return;
        if (attacker.position.dst(target.position) > attacker.attackRange) return;
        attacker.attack(target);
        spawnShot(attacker, target);
        playShot();
    }

    /** Шукає найближчого ВИДИМОГО ворога в радіусі атаки */
    private Unit findNearestVisibleEnemy(Unit unit, Array<Unit> all) {
        Unit  nearest = null;
        float minDist = unit.attackRange;
        for (int i = 0; i < all.size; i++) {
            Unit other = all.get(i);
            if (!other.alive || other.team == unit.team) continue;
            // Юніти ворога не «знають» про гравцевих юнітів через туман —
            // але ворог може бачити гравця через власний sightRange.
            // Проста реалізація: ворог атакує гравця якщо в радіусі атаки.
            // Туман впливає лише на видимість для ГРАВЦЯ, ворог завжди «бачить» в attackRange.
            if (unit.team != Team.PLAYER && other.team == Team.PLAYER) {
                float d = unit.position.dst(other.position);
                if (d <= minDist) { minDist = d; nearest = other; }
                continue;
            }
            // Гравець атакує лише ВИДИМИХ ворогів
            if (unit.team == Team.PLAYER && !other.visibleToPlayer) continue;
            float d = unit.position.dst(other.position);
            if (d <= minDist) { minDist = d; nearest = other; }
        }
        return nearest;
    }

    private boolean hasManualOrder(Unit u) {
        for (int i = 0; i < orders.size; i++)
            if (orders.get(i).attacker == u) return true;
        return false;
    }

    private void spawnShot(Unit from, Unit to) {
        for (int i = 0; i < shots.size; i++) {
            ShotEffect s = shots.get(i);
            if (!s.active) { s.show(from.position.x, from.position.y, to.position.x, to.position.y); return; }
        }
        shots.get(0).show(from.position.x, from.position.y, to.position.x, to.position.y);
    }

    private void playShot() {
        if (shotSound != null) shotSound.play(soundVolume);
    }

    private static class AttackGroup {
        enum Phase { SPREAD, ADVANCE }
        final Unit target;
        final Array<AttackOrder> orders = new Array<>();
        Phase phase = Phase.SPREAD;

        AttackGroup(Unit target) { this.target = target; }
        void addOrder(AttackOrder o) { orders.add(o); }
        void cleanup() {
            for (int i = orders.size - 1; i >= 0; i--) {
                AttackOrder o = orders.get(i);
                if (!o.attacker.alive || !o.target.alive) orders.removeIndex(i);
            }
        }
        boolean isEmpty() { return orders.size == 0; }
        void update() {
            if (phase == Phase.ADVANCE) return;
            boolean allReady = true;
            for (int i = 0; i < orders.size; i++) {
                AttackOrder o = orders.get(i);
                if (!o.attacker.alive) continue;
                float d = o.attacker.position.dst(o.spreadX, o.spreadY);
                if (d > 3f) { allReady = false; break; }
            }
            if (allReady) phase = Phase.ADVANCE;
        }
    }

    public void cancelAttackOrders(Array<Unit> units) {
        for (int i = orders.size - 1; i >= 0; i--) {
            Unit attacker = orders.get(i).attacker;
            for (Unit u : units) {
                if (attacker == u) { orders.removeIndex(i); break; }
            }
        }
        for (int i = groups.size - 1; i >= 0; i--) {
            if (groups.get(i).isEmpty()) groups.removeIndex(i);
        }
    }

    private static class AttackOrder {
        final Unit attacker, target;
        final float spreadX, spreadY, finalX, finalY, perpOffset;
        final AttackGroup group;

        AttackOrder(Unit a, Unit t, float sx, float sy, float fx, float fy, AttackGroup group) {
            this.attacker = a; this.target = t;
            this.spreadX = sx; this.spreadY = sy;
            this.finalX  = fx; this.finalY  = fy;
            this.group = group;

            float vx = t.position.x - a.position.x;
            float vy = t.position.y - a.position.y;
            float len = (float) Math.sqrt(vx * vx + vy * vy);
            if (len < 0.01f) len = 1f;
            float nx = vx/len, ny = vy/len;
            float px = -ny,    py =  nx;
            float dfx = fx - t.position.x;
            float dfy = fy - t.position.y;
            this.perpOffset = dfx * px + dfy * py;
        }
    }

    public void updatePopups(float delta) { popupManager.update(delta); }
    public void drawPopups(SpriteBatch batch) { popupManager.draw(batch); }
}
