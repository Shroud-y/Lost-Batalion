package io.jababa.lost_batalion.units;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.DeterministicRandom;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.screens.effects.ArtilleryStrikeEffect;
import io.jababa.lost_batalion.sim.StateChecksum;
import io.jababa.lost_batalion.screens.effects.ShotEffect;
import io.jababa.lost_batalion.screens.effects.TargetPopupManager;
import io.jababa.lost_batalion.terrain.TerrainCombatModifier;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.terrain.TerrainType;

/**
 * Бій: накази атаки, автовогонь, артилерія.
 *
 * <p>Уся математика бою — цілочисельна ({@link Fixed}). Це не косметика:
 * відстань вирішує, чи постріл узагалі відбувся, множник місцевості вирішує
 * скільки зняти hp, а розкид снаряда вирішує, хто опиниться під вибухом.
 * Розбіжність в один ulp у будь-якому з цих місць — інший переможець бою.
 *
 * <p>Float лишився рівно там, де він нічого не вирішує: трасери, вибухи,
 * індикатор заряджання.
 */
public class CombatManager {

    private static final int  MAX_SHOTS        = 64;
    /**
     * Інтервал між юнітами в бойовій лінії (Q47.16).
     *
     * <p>Мусить перевищувати діаметр юніта: піхота має радіус хітбокса 8, тобто
     * двоє не можуть стояти ближче ніж за 16 одна від одної. Було 12 — і поки
     * юніти проходили крізь одне одного, це працювало. З появою колізій лінія
     * з інтервалом 12 стала нездійсненною: юніти штовхались, не доходили до
     * своїх місць, група вічно лишалась у фазі розходження й НІКОЛИ не
     * переходила в атаку.
     */
    private static final long LINE_SPACING     = Fixed.fromInt(20);

    /**
     * Наскільки близько до своєї точки в лінії треба підійти, щоб вважатись
     * на місці.
     *
     * <p>Теж збільшено разом із колізіями: сусіди штовхаються, і стати рівно
     * в призначену точку з точністю до трьох одиниць уже неможливо. Строю
     * досить бути приблизно там, де треба.
     */
    private static final long SPREAD_THRESHOLD = Fixed.fromInt(10);
    /** Частка дальності, на якій лінія зупиняється перед ціллю. */
    private static final long STOP_DIST_FACTOR = Fixed.fromFloat(0.85f);
    /** Множник захисту цілі, що стоїть у лісі. */
    private static final long FOREST_DEFENSE_BONUS = Fixed.fromFloat(1.5f);

    private final UnitManager  unitManager;
    private final TerrainQuery terrain;

    /**
     * Ігровий RNG матчу. Смикається лише звідси і лише в межах тіку —
     * розкид артснаряда визначає точку вибуху, тобто хто скільки отримає урону.
     * Глобальний MathUtils.random тут давав різні влучення на різних клієнтах.
     */
    private final DeterministicRandom random;

    private final Array<ShotEffect>  shots  = new Array<>();
    private final Array<AttackOrder> orders = new Array<>();
    private final Array<AttackGroup> groups = new Array<>();

    /** Снаряди в польоті — стан гри. Порядок обходу фіксований. */
    private final Array<ArtilleryShell> shells = new Array<>();
    /**
     * Візуал, СУВОРО паралельний {@link #shells} по індексу; елемент може бути
     * null — так буває після ресинку, коли снаряди відновлені зі знімка, а
     * малювати їх нема чим. Списки додаються й видаляються тільки разом:
     * розсинхронізовані індекси означали б вибух не там, де влучив снаряд.
     */
    private final Array<ArtilleryStrikeEffect> shellVisuals = new Array<>();
    private final Array<ArtilleryStrikeEffect> explosions   = new Array<>();

    /** Буфер під нормалізований напрямок — щоб не алокувати масив у бою щотіку. */
    private final long[] dir = new long[2];

    private TargetPopupManager popupManager;
    private Sound              shotSound;
    private final float        soundVolume = 0.35f;

    public CombatManager(UnitManager unitManager, TerrainQuery terrain, DeterministicRandom random) {
        this.unitManager = unitManager;
        this.terrain     = terrain;
        this.random      = random;

        for (int i = 0; i < MAX_SHOTS; i++) shots.add(new ShotEffect());
        try {
            if (Gdx.files.internal("sounds/shot.wav").exists())
                shotSound = Gdx.audio.newSound(Gdx.files.internal("sounds/shot.wav"));
        } catch (Exception ignored) {}
        popupManager = new TargetPopupManager("ui/target_icon.png");
    }

    /**
     * Наказ атаки для явного списку юнітів.
     *
     * <p>Список приходить ззовні, а не з виділення: наказ виконується на тіку
     * {@code N + INPUT_DELAY} однаково в усіх, а виділення до того моменту вже
     * могло змінитись — та й у суперника воно взагалі своє.
     */
    public void orderAttack(Array<Unit> units, Unit enemy) {
        if (units.size == 0 || enemy == null || !enemy.alive) return;

        for (int i = orders.size - 1; i >= 0; i--) {
            Unit att = orders.get(i).att;
            for (int j = 0; j < units.size; j++) {
                if (att == units.get(j)) { orders.removeIndex(i); break; }
            }
        }
        for (int i = groups.size - 1; i >= 0; i--)
            if (groups.get(i).isEmpty()) groups.removeIndex(i);

        // Артилерія не шикується в лінію — їй виставляємо ручну ціль (ПКМ по ворогу).
        Array<Unit> attackers = new Array<>();
        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            if (u instanceof Artillery) {
                if (enemy.team != u.team) ((Artillery) u).manualTarget = enemy;
            } else {
                attackers.add(u);
            }
        }

        if (attackers.size > 0) formLineAndOrder(attackers, enemy);
    }

    /**
     * Мітка над ціллю. Суто візуальна річ і викликається одразу на кліку, а не
     * з тіку виконання: підтвердження наказу має бути миттєвим, інакше при
     * input delay клік відчувається «залиплим».
     */
    public void showTargetPopup(Unit enemy) {
        if (enemy != null && enemy.alive)
            popupManager.spawn(enemy.worldX(), enemy.worldY() + enemy.getSizePx() / 2f);
    }

    /** Скасувати накази й зупинити юнітів на місці. Виконується з тіку команди. */
    public void stopUnits(Array<Unit> units) {
        cancelAttackOrders(units);
        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            u.stopMoving();
            if (u instanceof Artillery) ((Artillery) u).manualTarget = null;
        }
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

    /**
     * Один крок бойової симуляції.
     *
     * <p>Порядок підетапів зафіксований: снаряди → групи → накази → автовогонь.
     * Переставити їх — змінити результат, бо приліт снаряда може вбити юніта,
     * чий наказ інакше встиг би виконатись.
     */
    public void tick() {
        Array<Unit> all = unitManager.getAllUnits();

        tickShells();

        for (int i = groups.size - 1; i >= 0; i--) {
            AttackGroup g = groups.get(i);
            g.cleanup();
            if (g.isEmpty()) { groups.removeIndex(i); continue; }
            g.update();
        }

        for (int i = orders.size - 1; i >= 0; i--) {
            AttackOrder o = orders.get(i);
            // Видимість перевіряється очима атакуючого, а не «гравця»: у 1v1
            // сторін дві, і ціль, невидиму для одного, інший бачить чудово.
            if (!o.att.alive || !o.target.alive || !o.target.isVisibleTo(o.att.team)) {
                orders.removeIndex(i); continue;
            }
            processOrder(o);
        }

        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!u.alive) continue;

            // Артилерія має власну логіку: авто-обстріл + ручна ціль
            if (u instanceof Artillery) { updateArtillery((Artillery) u, all); continue; }

            if (hasManualOrder(u)) continue;
            Unit nearest = findNearestEnemyInRange(u, all);
            if (nearest != null) tryAttack(u, nearest);
        }

        for (int i = orders.size - 1; i >= 0; i--) {
            AttackOrder o = orders.get(i);
            if (!o.att.alive || !o.target.alive) orders.removeIndex(i);
        }
    }

    /**
     * Суто візуальна частина: трасери й вибухи. Йде за часом кадру, бо на стан
     * гри не впливає — урон уже нанесено в момент пострілу/прильоту.
     */
    public void updateVisuals(float delta) {
        for (int i = 0; i < shots.size; i++) shots.get(i).update(delta);

        // Позиція снаряду на екрані береться з симуляції, а не інтегрується тут.
        for (int i = 0; i < shells.size && i < shellVisuals.size; i++) {
            ArtilleryStrikeEffect eff = shellVisuals.get(i);
            if (eff == null) continue;
            ArtilleryShell shell = shells.get(i);
            eff.setShellPosition(Fixed.toFloat(shell.currentX()),
                                 Fixed.toFloat(shell.currentY()));
        }
        for (int i = explosions.size - 1; i >= 0; i--) {
            ArtilleryStrikeEffect eff = explosions.get(i);
            eff.update(delta);
            if (!eff.active) explosions.removeIndex(i);
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

    /**
     * Ворог під курсором очима сторони {@code viewer}.
     *
     * <p>Це запит від UI, а не частина симуляції: він відповідає на питання
     * «по кому я щойно клікнув», щоб зібрати команду. Невидимі цілі не
     * повертаються — інакше можна було б наказати атакувати те, чого не видно.
     */
    public Unit tryGetEnemyAtPoint(float worldX, float worldY, Team viewer) {
        Array<Unit> all = unitManager.getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (u.team == viewer || !u.alive || !u.isVisibleTo(viewer)) continue;
            float dx = worldX - u.worldX(), dy = worldY - u.worldY();
            float r = Fixed.toFloat(u.hitRadiusFixed());
            if (dx * dx + dy * dy <= r * r)
                return u;
        }
        return null;
    }

    /**
     * Домішати бойовий стан у хеш: накази, фази груп, снаряди в польоті.
     *
     * <p>Живе тут, а не в {@code StateChecksum}, бо все це приватне і має таким
     * лишитись — інакше кожен, хто захоче зазирнути в накази, зможе їх ще й
     * поміняти. Порядок обходу — індекси масивів, тобто той самий, у якому
     * накази створювались.
     *
     * <p>Навіщо взагалі хешувати накази, якщо вони й так відображаються в
     * позиціях: розбіжність у наказах видно на кілька секунд РАНІШЕ, ніж
     * юніти встигнуть розійтись помітно. Що раніше спіймано, то ближче до
     * причини.
     */
    public long stateDigest(long h) {
        h = StateChecksum.fold(h, orders.size);
        for (int i = 0; i < orders.size; i++) {
            AttackOrder o = orders.get(i);
            h = StateChecksum.fold(h, o.att.id);
            h = StateChecksum.fold(h, o.target.id);
            h = StateChecksum.fold(h, o.spreadX);
            h = StateChecksum.fold(h, o.spreadY);
            h = StateChecksum.fold(h, o.perpOffset);
        }

        h = StateChecksum.fold(h, groups.size);
        for (int i = 0; i < groups.size; i++) {
            AttackGroup g = groups.get(i);
            h = StateChecksum.fold(h, g.target.id);
            h = StateChecksum.fold(h, g.phase.ordinal());
            h = StateChecksum.fold(h, g.orders.size);
        }

        h = StateChecksum.fold(h, shells.size);
        for (int i = 0; i < shells.size; i++) {
            ArtilleryShell s = shells.get(i);
            h = StateChecksum.fold(h, s.targetX);
            h = StateChecksum.fold(h, s.targetY);
            h = StateChecksum.fold(h, s.ticksLeft());
        }
        return h;
    }

    // ── Знімок стану (ресинк) ─────────────────────────────────────────────

    /**
     * Записати бойовий стан: накази, групи, снаряди.
     *
     * <p>Серіалізація живе тут, а не в {@code SimulationSnapshot}, бо
     * {@code AttackOrder} і {@code AttackGroup} приватні й мають такими
     * лишитись: віддати їх назовні заради знімка означало б дозволити
     * будь-кому міняти накази в обхід {@link #orderAttack}.
     *
     * <p>Посилання на юнітів пишуться як id — після відновлення це вже інші
     * об'єкти. Групи пишуться перед наказами, а наказ несе індекс своєї групи:
     * так відновлення проходить в один прохід.
     */
    public void writeSnapshot(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(groups.size);
        for (int i = 0; i < groups.size; i++) {
            AttackGroup g = groups.get(i);
            out.writeInt(g.target.id);
            out.writeInt(g.phase.ordinal());
        }

        out.writeInt(orders.size);
        for (int i = 0; i < orders.size; i++) {
            AttackOrder o = orders.get(i);
            out.writeInt(o.att.id);
            out.writeInt(o.target.id);
            out.writeLong(o.spreadX);
            out.writeLong(o.spreadY);
            out.writeLong(o.finalX);
            out.writeLong(o.finalY);
            out.writeLong(o.perpOffset);
            out.writeInt(groups.indexOf(o.group, true));
        }

        out.writeInt(shells.size);
        for (int i = 0; i < shells.size; i++) {
            ArtilleryShell s = shells.get(i);
            out.writeLong(s.fromX);
            out.writeLong(s.fromY);
            out.writeLong(s.targetX);
            out.writeLong(s.targetY);
            out.writeLong(s.splashRadius);
            out.writeLong(s.damage);
            out.writeInt(s.totalTicks);
            out.writeInt(s.ticksLeft());
        }
    }

    /**
     * Відновити бойовий стан зі знімка.
     *
     * <p>Візуал (трасери, снаряди в польоті, вибухи) не відновлюється, а
     * скидається: він і так живе один кадр, а плутати гравця «привидами»
     * пострілів зі стану, якого вже немає, гірше за коротку порожнечу.
     */
    public void readSnapshot(java.io.DataInputStream in) throws java.io.IOException {
        orders.clear();
        groups.clear();
        shells.clear();
        shellVisuals.clear();
        explosions.clear();
        for (int i = 0; i < shots.size; i++) shots.get(i).active = false;

        int groupCount = in.readInt();
        AttackGroup[] restored = new AttackGroup[groupCount];
        for (int i = 0; i < groupCount; i++) {
            Unit target = unitManager.findById(in.readInt());
            int phase   = in.readInt();
            AttackGroup g = new AttackGroup(target);
            g.phase = AttackGroup.Phase.values()[phase];
            restored[i] = g;
            groups.add(g);
        }

        int orderCount = in.readInt();
        for (int i = 0; i < orderCount; i++) {
            Unit att    = unitManager.findById(in.readInt());
            Unit target = unitManager.findById(in.readInt());
            long sx = in.readLong(), sy = in.readLong();
            long fx = in.readLong(), fy = in.readLong();
            long perp = in.readLong();
            int  gi   = in.readInt();

            AttackGroup g = gi >= 0 && gi < groupCount ? restored[gi] : null;
            // Юніт або ціль могли не доїхати в знімку лише через пошкодження —
            // тоді наказ просто не відновлюється, а не валить весь ресинк.
            if (att == null || target == null || g == null) continue;

            AttackOrder o = new AttackOrder(att, target, sx, sy, fx, fy, perp, g);
            orders.add(o);
            g.addOrder(o);
        }

        // Групи, що лишились без наказів (через пропущені вище), нічого не
        // роблять і лише смітили б у checksum.
        for (int i = groups.size - 1; i >= 0; i--)
            if (groups.get(i).isEmpty() || groups.get(i).target == null) groups.removeIndex(i);

        int shellCount = in.readInt();
        for (int i = 0; i < shellCount; i++) {
            shells.add(new ArtilleryShell(
                in.readLong(), in.readLong(), in.readLong(), in.readLong(),
                in.readLong(), in.readLong(), in.readInt(), in.readInt()));
            shellVisuals.add(null);   // індекси мусять лишатись парними
        }
    }

    public void dispose() {
        if (shotSound    != null) shotSound.dispose();
        if (popupManager != null) popupManager.dispose();
        shells.clear();
        shellVisuals.clear();
        explosions.clear();
        ArtilleryStrikeEffect.disposeAssets();
    }

    // ── Приватне ─────────────────────────────────────────────────────────

    private void formLineAndOrder(Array<Unit> units, Unit enemy) {
        int count = units.size;
        if (count == 0) return;

        long cx = 0, cy = 0;
        for (int i = 0; i < count; i++) { cx += units.get(i).x; cy += units.get(i).y; }
        cx /= count; cy /= count;

        // Напрямок на ціль і перпендикуляр до нього — уздовж нього шикується лінія.
        long len = Fixed.normalize(enemy.x - cx, enemy.y - cy, dir);
        long nx = len == 0 ? Fixed.ONE : dir[0];
        long ny = len == 0 ? 0         : dir[1];
        long px = -ny, py = nx;

        // Сортування за проєкцією на перпендикуляр: хто лівіше — той лівіше й
        // лишається. Ключ цілочисельний, тож порядок однаковий на всіх клієнтах;
        // Array.sort стабільний (merge sort), тож рівні ключі теж не переставляються.
        Array<Unit> sorted = new Array<>(units);
        final long fpx = px, fpy = py;
        sorted.sort((u1, u2) -> Long.compare(
            Fixed.mul(u1.x, fpx) + Fixed.mul(u1.y, fpy),
            Fixed.mul(u2.x, fpx) + Fixed.mul(u2.y, fpy)));

        long stopDist = Fixed.mul(sorted.get(0).attackRange, STOP_DIST_FACTOR);
        AttackGroup group = new AttackGroup(enemy);

        for (int i = 0; i < count; i++) {
            Unit u = sorted.get(i);
            // (i - (count-1)/2) з половинкою — лінія центрується навколо центроїда.
            long offset = Fixed.mul(Fixed.fromInt(i * 2 - (count - 1)) >> 1, LINE_SPACING);
            long sx = cx + Fixed.mul(px, offset), sy = cy + Fixed.mul(py, offset);
            long fx = enemy.x - Fixed.mul(nx, stopDist) + Fixed.mul(px, offset);
            long fy = enemy.y - Fixed.mul(ny, stopDist) + Fixed.mul(py, offset);
            u.moveTo(sx, sy);
            AttackOrder order = new AttackOrder(u, enemy, sx, sy, fx, fy, group);
            orders.add(order);
            group.addOrder(order);
        }
        groups.add(group);
    }

    private void processOrder(AttackOrder order) {
        Unit attacker = order.att, target = order.target;

        // Артилерія ніколи не отримує processOrder (захист від edge-case)
        if (attacker instanceof Artillery) return;

        if (Fixed.dstSq(attacker.x, attacker.y, target.x, target.y)
                <= Fixed.mul(attacker.attackRange, attacker.attackRange)) {
            attacker.stopMoving();
            tryAttack(attacker, target);
            return;
        }
        switch (order.group.phase) {
            case SPREAD: {
                if (Fixed.dstSq(attacker.x, attacker.y, order.spreadX, order.spreadY)
                        > Fixed.mul(SPREAD_THRESHOLD, SPREAD_THRESHOLD))
                    attacker.moveTo(order.spreadX, order.spreadY);
                else attacker.stopMoving();
                break;
            }
            case ADVANCE: {
                long len = Fixed.normalize(target.x - attacker.x, target.y - attacker.y, dir);
                if (len != 0) {
                    long fnx = dir[0], fny = dir[1];
                    long fpx = -fny, fpy = fnx;
                    long reach = Fixed.mul(attacker.attackRange, STOP_DIST_FACTOR);
                    attacker.moveTo(
                        target.x - Fixed.mul(fnx, reach) + Fixed.mul(fpx, order.perpOffset),
                        target.y - Fixed.mul(fny, reach) + Fixed.mul(fpy, order.perpOffset));
                }
                break;
            }
        }
    }

    /** Атака з урахуванням місцевості. Артилерія не може атакувати через цей метод. */
    private void tryAttack(Unit attacker, Unit target) {
        if (attacker instanceof Artillery) return;

        if (!attacker.canAttack()) return;
        if (Fixed.dstSq(attacker.x, attacker.y, target.x, target.y)
                > Fixed.mul(attacker.attackRange, attacker.attackRange)) return;

        if (terrain != null) {
            TerrainType atkElev = terrain.elevationF(attacker.x, attacker.y);
            TerrainType defElev = terrain.elevationF(target.x, target.y);
            boolean targetInForest = terrain.isForestF(target.x, target.y);
            long defMult = TerrainCombatModifier.getDefenseMultiplier(atkElev, defElev);
            if (targetInForest) defMult = Fixed.mul(defMult, FOREST_DEFENSE_BONUS);

            attacker.attackWithTerrain(target, defMult);
        } else {
            attacker.attack(target);
        }

        spawnShot(attacker, target);
        playShot();
    }

    /**
     * Найближчий живий ворог у межах дальності.
     *
     * <p>Видимість тут навмисно НЕ перевіряється: автовогонь по тому, хто
     * підійшов упритул, не повинен залежати від туману. Порівняння йде по
     * квадратах відстані — корінь тут не потрібен і коштував би даремно.
     */
    private Unit findNearestEnemyInRange(Unit unit, Array<Unit> all) {
        Unit nearest = null;
        long minDistSq = Fixed.mul(unit.attackRange, unit.attackRange);
        for (int i = 0; i < all.size; i++) {
            Unit other = all.get(i);
            if (!other.alive || other.team == unit.team) continue;
            long d = Fixed.dstSq(unit.x, unit.y, other.x, other.y);
            if (d <= minDistSq) { minDistSq = d; nearest = other; }
        }
        return nearest;
    }

    // ── Артилерія ────────────────────────────────────────────────────────

    /** Снаряди в польоті: крок, приліт, AoE-урон. */
    private void tickShells() {
        for (int i = shells.size - 1; i >= 0; i--) {
            ArtilleryShell shell = shells.get(i);
            if (!shell.tick()) continue;

            applyArtilleryAoe(shell.targetX, shell.targetY, shell.splashRadius, shell.damage);

            if (i < shellVisuals.size) {
                ArtilleryStrikeEffect eff = shellVisuals.removeIndex(i);
                if (eff != null) { eff.explode(); explosions.add(eff); }
            }
            shells.removeIndex(i);
        }
    }

    /**
     * Одна артустановка за тік: обирає ціль (ручну або авто), прицілюється
     * STRIKE_AIM_TICKS тіків, стріляє AoE-снарядом, іде на перезарядку.
     */
    private void updateArtillery(Artillery art, Array<Unit> all) {
        Unit target;

        // 1. Ручна ціль має пріоритет — поки жива й у радіусі
        Unit manual = art.manualTarget;
        long rangeSq = Fixed.mul(Artillery.STRIKE_RANGE, Artillery.STRIKE_RANGE);
        if (manual != null && manual.alive
            && Fixed.dstSq(art.x, art.y, manual.x, manual.y) <= rangeSq) {
            target = manual;
        } else {
            art.manualTarget = null; // ціль мертва/поза радіусом — скидаємо
            target = findNearestArtilleryTarget(art, all);
        }

        if (target == null || !art.isReady()) { art.aimTicks = 0; return; }

        art.aimTicks++;
        if (art.aimTicks >= Artillery.STRIKE_AIM_TICKS) {
            fireArtillery(art, target);
            art.aimTicks = 0;
        }
    }

    /** Найближчий живий ворог у радіусі обстрілу. */
    private Unit findNearestArtilleryTarget(Artillery art, Array<Unit> all) {
        Unit nearest = null;
        long minDistSq = Fixed.mul(Artillery.STRIKE_RANGE, Artillery.STRIKE_RANGE);
        for (int i = 0; i < all.size; i++) {
            Unit other = all.get(i);
            if (!other.alive || other.team == art.team) continue;
            long d = Fixed.dstSq(art.x, art.y, other.x, other.y);
            if (d <= minDistSq) { minDistSq = d; nearest = other; }
        }
        return nearest;
    }

    private void fireArtillery(Artillery art, Unit target) {
        // Два смикання RNG рівно на кожен постріл, у сталому порядку —
        // саме це тримає послідовність однаковою на всіх клієнтах.
        long angle  = random.nextAngle();
        long spread = random.nextFixed(Artillery.STRIKE_SPREAD);
        long impX = target.x + Fixed.mul(Fixed.cos(angle), spread);
        long impY = target.y + Fixed.mul(Fixed.sin(angle), spread);

        ArtilleryShell shell = new ArtilleryShell(art.x, art.y, impX, impY,
                                                  Artillery.STRIKE_SPLASH_RADIUS,
                                                  Artillery.STRIKE_DAMAGE);
        shells.add(shell);

        ArtilleryStrikeEffect.loadAssets();
        ArtilleryStrikeEffect eff = new ArtilleryStrikeEffect();
        eff.showIncoming(Fixed.toFloat(art.x), Fixed.toFloat(art.y),
                         Fixed.toFloat(impX), Fixed.toFloat(impY),
                         Fixed.toFloat(Artillery.STRIKE_SPLASH_RADIUS),
                         shell.angleDegrees());
        shellVisuals.add(eff);

        art.startReload();
        playShot();
    }

    /** AoE-урон у момент імпакту. Б'є всіх у радіусі (включно з союзниками). */
    private void applyArtilleryAoe(long cx, long cy, long radius, long baseDamage) {
        Array<Unit> all = unitManager.getAllUnits();
        long radiusSq = Fixed.mul(radius, radius);
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!u.alive) continue;
            long distSq = Fixed.dstSq(u.x, u.y, cx, cy);
            if (distSq > radiusSq) continue;
            long falloff = Fixed.ONE - Fixed.div(Fixed.sqrt(distSq), radius);
            u.takeDamage(Fixed.mul(baseDamage, falloff));
        }
    }

    /** Рендер снарядів/вибухів. batch і shapes мають camera.combined як projection. */
    public void drawArtilleryEffects(SpriteBatch batch, ShapeRenderer shapes) {
        for (int i = 0; i < shellVisuals.size; i++) {
            ArtilleryStrikeEffect eff = shellVisuals.get(i);
            if (eff != null && eff.active) eff.draw(batch, shapes);
        }
        for (int i = 0; i < explosions.size; i++) {
            ArtilleryStrikeEffect eff = explosions.get(i);
            if (eff.active) eff.draw(batch, shapes);
        }
    }

    /**
     * Білий індикатор заряджання пострілу над кожною артилерією що прицілюється.
     * Заповнюється від 0 до 1 за STRIKE_AIM_TICKS, наприкінці мигає.
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
            if (art.aimTicks <= 0) continue;

            float progress = art.aimProgress();
            float pulse    = (float) Math.abs(Math.sin(art.aimTicks * 0.175f));

            float cx   = art.worldX();
            float cy   = art.worldY() + art.getSizePx() / 2f + 6f;
            float barW = art.getSizePx() * 1.2f;
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
            if (!s.active) { s.show(from.worldX(), from.worldY(), to.worldX(), to.worldY()); return; }
        }
        shots.get(0).show(from.worldX(), from.worldY(), to.worldX(), to.worldY());
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
                if (Fixed.dstSq(o.att.x, o.att.y, o.spreadX, o.spreadY)
                        > Fixed.mul(SPREAD_THRESHOLD, SPREAD_THRESHOLD)) return;
            }
            phase = Phase.ADVANCE;
        }
    }

    private static class AttackOrder {
        final Unit att, target;
        final long spreadX, spreadY, finalX, finalY, perpOffset;
        final AttackGroup group;

        /** Відновлення зі знімка: perpOffset уже пораховано, рахувати його вдруге не можна. */
        AttackOrder(Unit a, Unit t, long sx, long sy, long fx, long fy, long perp, AttackGroup g) {
            att = a; target = t; spreadX = sx; spreadY = sy; finalX = fx; finalY = fy; group = g;
            perpOffset = perp;
        }

        AttackOrder(Unit a, Unit t, long sx, long sy, long fx, long fy, AttackGroup g) {
            att = a; target = t; spreadX = sx; spreadY = sy; finalX = fx; finalY = fy; group = g;

            long[] d = new long[2];
            long len = Fixed.normalize(t.x - a.x, t.y - a.y, d);
            long nx = len == 0 ? Fixed.ONE : d[0];
            long ny = len == 0 ? 0         : d[1];
            long px = -ny, py = nx;
            perpOffset = Fixed.mul(fx - t.x, px) + Fixed.mul(fy - t.y, py);
        }
    }
}
