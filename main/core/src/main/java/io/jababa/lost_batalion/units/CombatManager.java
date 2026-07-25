package io.jababa.lost_batalion.units;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.screens.effects.ArtilleryStrikeEffect;
import io.jababa.lost_batalion.screens.effects.ShotEffect;
import io.jababa.lost_batalion.screens.effects.TargetPopupManager;
import io.jababa.lost_batalion.terrain.TerrainCombatModifier;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.terrain.TerrainType;

public class CombatManager {

    private static final int   MAX_SHOTS        = 64;
    private static final float LINE_SPACING     = 12f;
    private static final float SPREAD_THRESHOLD =  3f;

    private final UnitManager  unitManager;
    private final TerrainQuery terrain;

    private final Array<ShotEffect>  shots  = new Array<>();
    private final Array<AttackOrder> orders = new Array<>();
    private final Array<AttackGroup> groups = new Array<>();

    /** Активні артснаряди/вибухи (візуал + AoE-урон при прильоті). */
    private final Array<ArtilleryStrikeEffect> artEffects = new Array<>();

    private TargetPopupManager popupManager;
    private Sound              shotSound;
    private final float        soundVolume = 0.35f;

    public CombatManager(UnitManager unitManager) {
        this(unitManager, null);
    }

    public CombatManager(UnitManager unitManager, TerrainQuery terrain) {
        this.unitManager = unitManager;
        this.terrain     = terrain;

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

        // Артилерія не шикується в лінію — їй виставляємо ручну ціль (ПКМ по ворогу).
        Array<Unit> attackers = new Array<>();
        for (int i = 0; i < selected.size; i++) {
            Unit u = selected.get(i);
            if (u instanceof Artillery) {
                if (enemy != null && enemy.alive && enemy.team != u.team)
                    ((Artillery) u).manualTarget = enemy;
            } else {
                attackers.add(u);
            }
        }

        if (attackers.size > 0) formLineAndOrder(attackers, enemy);
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

        // Артснаряди у польоті / вибухи (урон наноситься колбеком при прильоті)
        for (int i = artEffects.size - 1; i >= 0; i--) {
            ArtilleryStrikeEffect eff = artEffects.get(i);
            eff.update(delta);
            if (!eff.active) artEffects.removeIndex(i);
        }

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
            if (!u.alive) continue;

            // Артилерія має власну логіку: авто-обстріл + ручна ціль
            if (u instanceof Artillery) { updateArtillery((Artillery) u, all, delta); continue; }

            if (hasManualOrder(u)) continue;
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
        artEffects.clear();
        ArtilleryStrikeEffect.disposeAssets();
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

        // [НОВЕ] Артилерія ніколи не отримує processOrder (захист від edge-case)
        if (attacker instanceof Artillery) return;

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
     * [НОВЕ] Артилерія не може атакувати через цей метод.
     */
    private void tryAttack(Unit attacker, Unit target) {
        // [НОВЕ] Артилерія не стріляє напряму
        if (attacker instanceof Artillery) return;

        if (!attacker.canAttack()) return;
        float dist = attacker.position.dst(target.position);
        if (dist > attacker.attackRange) return;

        if (terrain != null) {
            TerrainType atkElev = terrain.elevation(attacker.position.x, attacker.position.y);
            TerrainType defElev = terrain.elevation(target.position.x, target.position.y);
            boolean targetInForest = terrain.isForest(target.position.x, target.position.y);
            float defMult = TerrainCombatModifier.getDefenseMultiplier(atkElev, defElev);
            if (targetInForest) defMult *= 1.5f;

            Gdx.app.log("COMBAT",
                "ATK elev=" + atkElev +
                    " DEF elev=" + defElev +
                    " IN_FOREST=" + targetInForest +
                    " TOTAL_MULT=" + defMult);

            attacker.attackWithTerrain(target, defMult);
        } else {
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
            float d = unit.position.dst(other.position);
            if (d <= minDist) { minDist = d; nearest = other; }
        }
        return nearest;
    }

    // ── Артилерія ────────────────────────────────────────────────────────

    /**
     * Одна артустановка за кадр: обирає ціль (ручну або авто), прицілюється
     * STRIKE_AIM_TIME секунд, стріляє AoE-снарядом, іде на перезарядку.
     */
    private void updateArtillery(Artillery art, Array<Unit> all, float delta) {
        Unit target = null;

        // 1. Ручна ціль має пріоритет — поки жива й у радіусі
        Unit manual = art.manualTarget;
        if (manual != null && manual.alive
            && art.position.dst(manual.position) <= Artillery.STRIKE_RANGE) {
            target = manual;
        } else {
            art.manualTarget = null; // ціль мертва/поза радіусом — скидаємо
            target = findNearestArtilleryTarget(art, all);
        }

        if (target == null || !art.isReady()) { art.aimTimer = 0f; return; }

        art.aimTimer += delta;
        if (art.aimTimer >= Artillery.STRIKE_AIM_TIME) {
            fireArtillery(art, target);
            art.aimTimer = 0f;
        }
    }

    /** Найближчий живий ворог у радіусі обстрілу. */
    private Unit findNearestArtilleryTarget(Artillery art, Array<Unit> all) {
        Unit nearest = null;
        float minDist = Artillery.STRIKE_RANGE;
        for (int i = 0; i < all.size; i++) {
            Unit other = all.get(i);
            if (!other.alive || other.team == art.team) continue;
            float d = art.position.dst(other.position);
            if (d <= minDist) { minDist = d; nearest = other; }
        }
        return nearest;
    }

    private void fireArtillery(Artillery art, Unit target) {
        ArtilleryStrikeEffect.loadAssets();

        float angle  = MathUtils.random(0f, MathUtils.PI2);
        float spread = MathUtils.random(0f, Artillery.STRIKE_SPREAD);
        final float impX = target.position.x + MathUtils.cos(angle) * spread;
        final float impY = target.position.y + MathUtils.sin(angle) * spread;

        ArtilleryStrikeEffect eff = new ArtilleryStrikeEffect();
        eff.show(
            art.position.x, art.position.y,
            impX, impY,
            Artillery.STRIKE_SPLASH_RADIUS,
            (cx, cy, splashR) -> applyArtilleryAoe(cx, cy, splashR, Artillery.STRIKE_DAMAGE)
        );
        artEffects.add(eff);

        art.startReload();
        playShot();
    }

    /** AoE-урон у момент імпакту. Б'є всіх у радіусі (включно з союзниками). */
    private void applyArtilleryAoe(float cx, float cy, float radius, float baseDamage) {
        Array<Unit> all = unitManager.getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!u.alive) continue;
            float dist = u.position.dst(cx, cy);
            if (dist > radius) continue;
            float falloff = 1f - (dist / radius);
            u.takeDamage(baseDamage * falloff);
        }
    }

    /** Рендер снарядів/вибухів. batch і shapes мають camera.combined як projection. */
    public void drawArtilleryEffects(SpriteBatch batch, ShapeRenderer shapes) {
        for (int i = 0; i < artEffects.size; i++) {
            ArtilleryStrikeEffect eff = artEffects.get(i);
            if (eff.active) eff.draw(batch, shapes);
        }
    }

    /**
     * Білий індикатор заряджання пострілу над кожною артилерією що прицілюється.
     * Заповнюється від 0 до 1 за STRIKE_AIM_TIME, наприкінці мигає.
     * shapes має camera.combined як projection.
     */
    public void drawArtilleryAim(ShapeRenderer shapes) {
        Array<Unit> all = unitManager.getAllUnits();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!(u instanceof Artillery) || !u.alive) continue;
            Artillery art = (Artillery) u;
            if (art.aimTimer <= 0f) continue;

            float progress = Math.min(art.aimTimer / Artillery.STRIKE_AIM_TIME, 1f);
            float pulse    = (float) Math.abs(Math.sin(art.aimTimer * 7f));

            float cx   = art.position.x;
            float cy   = art.position.y + art.getSize() / 2f + 6f;
            float barW = art.getSize() * 1.2f;
            float barH = 5f;
            float bx   = cx - barW / 2f;

            shapes.begin(ShapeRenderer.ShapeType.Filled);
            // Фон
            shapes.setColor(0.1f, 0.1f, 0.1f, 0.80f);
            shapes.rect(bx, cy, barW, barH);
            // Заповнення — біле, в кінці мигає
            float fillA = progress < 1f ? 0.95f : 0.95f * pulse;
            shapes.setColor(1f, 1f, 1f, fillA);
            shapes.rect(bx, cy, barW * progress, barH);
            shapes.end();

            // Рамка
            shapes.begin(ShapeRenderer.ShapeType.Line);
            shapes.setColor(1f, 1f, 1f, 0.45f);
            shapes.rect(bx, cy, barW, barH);
            shapes.end();
        }

        Gdx.gl.glDisable(GL20.GL_BLEND);
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
    }
}
