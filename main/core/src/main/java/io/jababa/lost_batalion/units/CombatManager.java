package io.jababa.lost_batalion.units;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.audio.AudioManager;
import io.jababa.lost_batalion.math.DeterministicRandom;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.screens.effects.Fx;
import io.jababa.lost_batalion.screens.effects.ArtilleryStrikeEffect;
import io.jababa.lost_batalion.sim.StateChecksum;
import io.jababa.lost_batalion.screens.effects.ShotEffect;
import io.jababa.lost_batalion.screens.effects.TargetPopupManager;
import io.jababa.lost_batalion.terrain.TerrainCombatModifier;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.visibility.VisibilitySystem;

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
     * Наскільки близько до точки розходження треба підійти, щоб вважатись на
     * місці.
     *
     * <p>Нові накази атаки шикування більше не роблять (див.
     * {@link #orderDirectCharge}), але фаза SPREAD лишається в коді заради
     * знімків: наказ, збережений старою версією, може прийти в цій фазі.
     */
    private static final long SPREAD_THRESHOLD = Fixed.fromInt(10);
    /** Частка дальності, на якій юніт зупиняється перед ціллю. */
    private static final long STOP_DIST_FACTOR = Fixed.fromFloat(0.85f);
    /** Множник захисту цілі, що стоїть у лісі. */
    private static final long FOREST_DEFENSE_BONUS = Fixed.fromFloat(1.5f);

    /**
     * Похибка наведення, яку артилерія ще вважає супроводом, а не розворотом
     * (радіани, Q47.16). Приблизно 5.7°.
     *
     * <p>Помітно більша за {@code Unit.FACING_EPSILON}: та відповідає на
     * питання «стволом рівно туди?», а тут питання інше — «чи це та сама
     * наводка, чи я почав наводитись заново». Ціль, яка йде, зсуває пеленг
     * щотіку; з тісним допуском гармата обнуляла б приціл вічно.
     */
    private static final long AIM_TRACK_TOLERANCE = Fixed.fromFloat(0.10f);

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

    /**
     * Власна гучність пострілу відносно решти звуків — властивість ассета, а не
     * налаштування. Що з нею зробить гравець, вирішує {@link AudioManager}:
     * раніше тут стояло кінцеве значення, і повзунок гучності на постріли не
     * впливав узагалі.
     */
    private static final float SHOT_GAIN = 0.35f;

    /**
     * Видимість очима конкретного юніта.
     *
     * <p>Потрібна саме артилерії: сторонова видимість каже лише «цю ціль
     * бачить хтось із армії», а гармата мусить стріляти тільки по тому, що
     * бачить сама. Ставиться ззовні, бо систему видимості створює симуляція.
     */
    private VisibilitySystem visibility;

    /**
     * Пошук шляху для наказу «підійти й відкрити вогонь».
     *
     * <p>Через інтерфейс, а не прямим посиланням на {@code PathFinder}: сітка
     * прохідності будується ліниво в симуляції, і бій не має знати, коли саме
     * це станеться.
     */
    public interface PathProvider {
        /** @return пари координат маршруту або {@code null}, якщо шляху нема */
        long[] findPath(long fromX, long fromY, long toX, long toY);
    }

    private PathProvider pathProvider;

    public void setVisibility(VisibilitySystem visibility)   { this.visibility = visibility; }
    public void setPathProvider(PathProvider pathProvider)   { this.pathProvider = pathProvider; }

    /** Чи бачить юніт ціль особисто. Без системи видимості — сторонова оцінка. */
    private boolean canSee(Unit observer, Unit target) {
        if (visibility != null) return visibility.canSee(observer, target);
        return target.isVisibleTo(observer.team);
    }

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

        // Артилерія не біжить на ціль — їй виставляємо ручну ціль (ПКМ по ворогу).
        Array<Unit> attackers = new Array<>();
        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            if (u instanceof Artillery) {
                if (enemy.team != u.team) ((Artillery) u).manualTarget = enemy;
            } else {
                attackers.add(u);
            }
        }

        if (attackers.size > 0) orderDirectCharge(attackers, enemy);
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

    /**
     * Зняти накази з тих, хто щойно зламався.
     *
     * <p>Кличе {@code GameSimulation} одразу після системи моралі. Окремий
     * прохід, а не перевірка всередині {@link MoraleSystem}, бо ордери й групи
     * — стан БОЮ: друге місце, яке в них лізе, рано чи пізно розійдеться з
     * першим у тому, що вважати порожньою групою.
     *
     * <p>Ручну ціль гармати тут теж треба гасити: обслуга, що розбіглась,
     * нікуди не наводить.
     */
    public void dropBrokenOrders() {
        for (int i = orders.size - 1; i >= 0; i--) {
            if (orders.get(i).att.isBroken()) orders.removeIndex(i);
        }
        for (int i = groups.size - 1; i >= 0; i--) {
            AttackGroup g = groups.get(i);
            g.cleanup();
            if (g.isEmpty()) groups.removeIndex(i);
        }
        Array<Unit> all = unitManager.getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!u.isBroken() || !(u instanceof Artillery)) continue;
            Artillery a = (Artillery) u;
            a.manualTarget = null;
            a.aimTarget    = null;
            a.aimTicks     = 0;
        }
    }

    public void cancelAttackOrders(Array<Unit> units) {
        for (int i = orders.size - 1; i >= 0; i--) {
            Unit att = orders.get(i).att;
            for (int j = 0; j < units.size; j++) {
                if (att == units.get(j)) { orders.removeIndex(i); break; }
            }
        }
        // Ручна ціль артилерії — теж наказ атаки, і наказ руху його скасовує.
        // Інакше гармата, якій сказали йти, наступного ж тіку розверталась би
        // назад до старої цілі: підхід до неї — це теж рух.
        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            if (u instanceof Artillery) ((Artillery) u).manualTarget = null;
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
            if (!o.att.alive || o.att.isBroken()
                    || !o.target.alive || !o.target.isVisibleTo(o.att.team)) {
                orders.removeIndex(i); continue;
            }
            processOrder(o);
        }

        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (!u.alive) continue;
            // Зламаний не воює — у цьому вся суть зламу. Перевірка стоїть тут,
            // а не всередині tryAttack: вона однаково стосується і автовогню,
            // і гарматного, і не має повторюватись у кожному з них.
            if (u.isBroken()) continue;

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
        Fx.update(delta);

        // Відкат гармат — теж за часом кадру.
        Array<Unit> all = unitManager.getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            u.updateCombatShake(delta);
            if (u instanceof Artillery) ((Artillery) u).updateRecoil(delta);
        }

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
            h = StateChecksum.fold(h, o.offX);
            h = StateChecksum.fold(h, o.offY);
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
            out.writeLong(o.offX);
            out.writeLong(o.offY);
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
            long ox = in.readLong(), oy = in.readLong();
            int  gi   = in.readInt();

            AttackGroup g = gi >= 0 && gi < groupCount ? restored[gi] : null;
            // Юніт або ціль могли не доїхати в знімку лише через пошкодження —
            // тоді наказ просто не відновлюється, а не валить весь ресинк.
            if (att == null || target == null || g == null) continue;

            AttackOrder o = new AttackOrder(att, target, sx, sy, fx, fy, ox, oy, g);
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

    /**
     * Інтервал між сусідами в шерензі наступу (Q47.16).
     *
     * <p>Мусить бути БІЛЬШИМ за суму хітбоксів (8+8=16), інакше розштовхування
     * почне працювати проти наказу: юніт іде на своє місце, його відпихають,
     * він іде знову. Те саме число, що й {@code UnitManager.GRID_SPACING}, і з
     * тієї самої причини.
     */
    private static final long CHARGE_LANE_SPACING = Fixed.fromInt(22);

    /**
     * Наказ атаки: шеренга лицем до цілі.
     *
     * <p>Етап SPREAD пропускається — група одразу в фазі {@code ADVANCE}, тобто
     * йде вперед, а не шикується на місці. Але йде вона РОЗГОРНУТО: кожен наказ
     * несе свій {@code perpOffset}, зсув упоперек осі наступу.
     *
     * <p><b>Раніше {@code perpOffset} був нульовим для всіх, і це і була та
     * «купа, що стоїть на місці».</b> {@code processOrder} у фазі ADVANCE
     * щотіку веде юніта в {@code ціль − напрямок×дистанція + перпендикуляр×зсув};
     * при нульовому зсуві це ОДНА точка на десятьох. Вони налазили один на
     * одного, {@link UnitSeparation} розпихував їх, наказ гнав назад — і група
     * товклася на місці, замість того щоб стріляти. Ненульові зсуви розводять
     * її по дузі навколо цілі, і кожен стоїть там, звідки бачить і б'є.
     *
     * <p>Вісь наступу береться від ЦЕНТРУ групи, а не від кожного окремо:
     * інакше двоє, що підходять з різних боків, порахували б перпендикуляр у
     * протилежні боки й помінялись би місцями посеред атаки.
     *
     * <p>Місця роздаються за поточним положенням уздовж шеренги — хто лівіший,
     * той і стає лівіше. Без цього наказ змушував би половину групи
     * перетинати іншу половину, щоб зайняти призначене місце.
     */
    private void orderDirectCharge(Array<Unit> units, Unit enemy) {
        int count = units.size;
        if (count == 0) return;

        AttackGroup group = new AttackGroup(enemy);
        group.phase = AttackGroup.Phase.ADVANCE;

        // Центр групи — цілочисельне середнє, тобто однакове в усіх клієнтах.
        long cx = 0, cy = 0;
        for (int i = 0; i < count; i++) { cx += units.get(i).x; cy += units.get(i).y; }
        cx /= count; cy /= count;

        long len = Fixed.normalize(enemy.x - cx, enemy.y - cy, dir);
        long nx = len == 0 ? Fixed.ONE : dir[0];
        long ny = len == 0 ? 0         : dir[1];
        long px = -ny, py = nx;

        int[] slots = laneOrder(units, cx, cy, px, py);

        for (int slot = 0; slot < count; slot++) {
            Unit u = units.get(slots[slot]);
            // (slot − (count−1)/2) у Fixed: шеренга центрується на осі.
            long lane = Fixed.mul(Fixed.fromInt(slot * 2 - (count - 1)) >> 1,
                                  CHARGE_LANE_SPACING);
            long stopDist = standoff(u, enemy);
            long fx = enemy.x - Fixed.mul(nx, stopDist) + Fixed.mul(px, lane);
            long fy = enemy.y - Fixed.mul(ny, stopDist) + Fixed.mul(py, lane);
            u.moveTo(fx, fy);
            AttackOrder order = new AttackOrder(u, enemy, fx, fy, fx, fy,
                                                fx - enemy.x, fy - enemy.y, group);
            orders.add(order);
            group.addOrder(order);
        }
        groups.add(group);
    }

    /**
     * Порядок юнітів уздовж шеренги: за проєкцією на її вісь, id як розв'язувач
     * нічиїх.
     *
     * <p>Сортування вставками — не з міркувань швидкості (груп більших за
     * десяток не буває), а тому що воно стабільне й написане тут же: результат
     * не залежить ні від реалізації {@code Arrays.sort} у конкретній JVM, ні від
     * порядку, в якому юніти потрапили в масив.
     */
    private int[] laneOrder(Array<Unit> units, long cx, long cy, long px, long py) {
        int n = units.size;
        int[] idx = new int[n];
        long[] key = new long[n];
        for (int i = 0; i < n; i++) {
            Unit u = units.get(i);
            idx[i] = i;
            key[i] = Fixed.mul(u.x - cx, px) + Fixed.mul(u.y - cy, py);
        }
        for (int i = 1; i < n; i++) {
            int  ji = idx[i];
            long jk = key[i];
            int  k  = i - 1;
            while (k >= 0 && (key[k] > jk
                              || (key[k] == jk && units.get(idx[k]).id > units.get(ji).id))) {
                idx[k + 1] = idx[k];
                key[k + 1] = key[k];
                k--;
            }
            idx[k + 1] = ji;
            key[k + 1] = jk;
        }
        return idx;
    }

    private void processOrder(AttackOrder order) {
        Unit attacker = order.att, target = order.target;

        // Артилерія ніколи не отримує processOrder (захист від edge-case)
        if (attacker instanceof Artillery) return;

        if (Fixed.dstSq(attacker.x, attacker.y, target.x, target.y)
                <= Fixed.mul(attacker.attackRange, attacker.attackRange)) {
            // Стрілець б'є з місця; рукопашник ТРИМАЄТЬСЯ впритул і повзе за
            // ціллю, яку сам же відштовхує. Без цього він щотіку то ставав
            // (ціль у радіусі), то смикався вперед (ціль виїхала з радіуса) —
            // саме це й виглядало як тремтіння кінноти під час удару.
            if (attacker.usesRangedFx()) attacker.stopMoving();
            else                         keepContact(attacker, target);
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
                // Своє місце в строю, зсунуте разом із ціллю. Осі тут уже не
                // рахуються: вони пораховані один раз у orderDirectCharge, і
                // перерахунок щотіку крутив би шеренгу навколо цілі.
                attacker.moveTo(target.x + order.offX, target.y + order.offY);
                break;
            }
        }
    }

    /**
     * Запас понад дотик хітбоксів, на якому зупиняється рукопашник.
     *
     * <p>Малий, але не нульовий, і в цьому вся річ: якщо ціль зупинки збігається
     * з відстанню, на якій спрацьовує розштовхування, юніт щотіку робить крок
     * уперед і його щотіку відпихають назад.
     */
    private static final long MELEE_PAD = Fixed.fromInt(2);

    /**
     * На якій відстані від цілі спинятись.
     *
     * <p>Для стрільця це просто частка дальності. Для рукопашника дальність
     * менша за суму хітбоксів помножену на будь-що — стати «на 85% від 18»
     * означає стати ВСЕРЕДИНІ цілі, звідки його виштовхує
     * {@link UnitSeparation}. Тому береться більше з двох: дотик хітбоксів із
     * запасом ніколи не заходить у зону розштовхування.
     */
    private long standoff(Unit attacker, Unit target) {
        long ranged  = Fixed.mul(attacker.attackRange, STOP_DIST_FACTOR);
        long contact = attacker.hitRadiusFixed() + target.hitRadiusFixed() + MELEE_PAD;
        return Math.max(ranged, contact);
    }

    /**
     * Тримати рукопашника впритул до цілі, поки він її б'є.
     *
     * <p>Наказ віддається щотіку заново, і це навмисно: ціль повзе від
     * поштовху, і юніт має повзти за нею тим самим темпом. Крок руху
     * обрізається залишком відстані, тож на місці він не смикається — просто
     * весь час стоїть там, де треба.
     */
    private void keepContact(Unit attacker, Unit target) {
        long len = Fixed.normalize(target.x - attacker.x, target.y - attacker.y, dir);
        if (len == 0) return;

        long reach = standoff(attacker, target);
        attacker.moveTo(target.x - Fixed.mul(dir[0], reach),
                        target.y - Fixed.mul(dir[1], reach));
    }

    /** Атака з урахуванням місцевості. Артилерія не може атакувати через цей метод. */
    private void tryAttack(Unit attacker, Unit target) {
        if (attacker instanceof Artillery) return;

        if (!attacker.canAttack()) return;
        long distSq = Fixed.dstSq(attacker.x, attacker.y, target.x, target.y);
        if (distSq > Fixed.mul(attacker.attackRange, attacker.attackRange)) return;

        // Розкид пострілу. Кидок стоїть саме тут — після кулдауну й дальності,
        // тобто рівно на тих тіках, коли постріл СТАВСЯ. Зсунути його вище
        // означало б смикати RNG і на тіках, коли зброя ще не готова, а
        // кількість смикань мусить збігатися на всіх клієнтах до одного.
        //
        // Хто б'є напевно (усе, крім піхоти), RNG не чіпає взагалі: гілка
        // залежить від класу юніта, а не від даних, тож вона однакова скрізь.
        long chance = attacker.hitChanceAt(Fixed.sqrt(distSq));
        if (chance < Fixed.ONE && random.nextFixedUnit() >= chance) {
            // Промах. Порох витрачено — кулдаун іде, як і після влучання.
            attacker.consumeAttackCooldown();
            if (attacker.usesRangedFx()) {
                spawnMissedShot(attacker, target);
                playShot();
            }
            return;
        }

        boolean hit;
        if (terrain != null) {
            TerrainType atkElev = terrain.elevationF(attacker.x, attacker.y);
            TerrainType defElev = terrain.elevationF(target.x, target.y);
            boolean targetInForest = terrain.isForestF(target.x, target.y);
            long defMult = TerrainCombatModifier.getDefenseMultiplier(atkElev, defElev);
            if (targetInForest) defMult = Fixed.mul(defMult, FOREST_DEFENSE_BONUS);

            hit = attacker.attackWithTerrain(target, defMult);
        } else {
            hit = attacker.attack(target);
        }
        if (!hit) return;

        // Тремтять обидва: той, хто вдарив, і той, кого вдарили. Це лише
        // картинка — позиції в симуляції не змінюються.
        if (target.alive) {
            attacker.kickCombatShake(attacker.combatShakeScale());
            target.kickCombatShake(attacker.combatShakeScale());
        } else {
            // Удар був смертельний — бою більше немає. Без цього переможець
            // ще півтори секунди дрижав би над порожнім місцем: таймер
            // навмисно довший за кулдаун, щоб триваючий бій не пульсував.
            attacker.clearCombatShake();
            target.clearCombatShake();
        }

        applyKnockback(attacker, target);

        // Ближній бій обходиться без трасера й пострілу.
        if (attacker.usesRangedFx()) {
            spawnShot(attacker, target);
            playShot();
        }
    }

    /**
     * Відкинути ціль ударом.
     *
     * <p>Напрямок — від атакуючого до цілі, тобто рівно той, у якому він на неї
     * і біг: удар з розгону продовжує рух вершника, а не штовхає навмання.
     *
     * <p>Зсув не застосовується тут: він розкладається на весь проміжок до
     * наступного удару і йде по тіку в {@code Unit.advanceKnockback}. Тому
     * атака штовхає ціль РІВНО, поки триває, а не смикає її раз на кулдаун.
     *
     * <p>Це СТАН симуляції, а не рендер: збита позиція міняє дальність до інших
     * і місцевість під ногами.
     */
    private void applyKnockback(Unit attacker, Unit target) {
        long force = attacker.knockbackForce();
        if (force <= 0) return;

        long len = Fixed.normalize(target.x - attacker.x, target.y - attacker.y, dir);
        if (len == 0) return;   // стоять в одній точці — напрямку немає

        target.applyKnockback(dir[0], dir[1], force, attacker.attackCooldownTicks);
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

        // 1. Ручна ціль (ПКМ по ворогу) має пріоритет і НЕ скидається, коли
        // ціль поза радіусом чи не видно: такий наказ означає «розберись із
        // цим», тож гармата сама йде на позицію.
        Unit manual = art.manualTarget;
        long rangeSq = Fixed.mul(Artillery.STRIKE_RANGE, Artillery.STRIKE_RANGE);
        if (manual != null && manual.alive) {
            boolean inRange = Fixed.dstSq(art.x, art.y, manual.x, manual.y) <= rangeSq;
            if (inRange && canSee(art, manual)) {
                // Дійшли: зупиняємось і працюємо по цілі.
                if (art.isMoving()) art.stopMoving();
                target = manual;
            } else {
                approachTarget(art, manual);
                aimAt(art, null);
                return;
            }
        } else {
            art.manualTarget = null; // ціль мертва — наказ вичерпано
            // Спершу ТА САМА ціль, що й минулого тіку, якщо вона ще ціль.
            // Пошук найближчого — лише коли триматись більше нема за кого.
            target = stillValidTarget(art, art.aimTarget);
            if (target == null) target = findNearestArtilleryTarget(art, all);
        }

        // Зміна цілі скидає приціл: 3 с наведення належать конкретному ворогу,
        // і доводити чужий приціл до пострілу означало б стріляти по тому, в
        // кого не цілились.
        aimAt(art, target);

        if (target == null) return;

        // Поки їде — розворотом керує рух, прицілитись у цей момент не можна.
        if (art.isMoving()) { art.aimTicks = 0; return; }

        // 2. Розворот на ціль. Крутиться навіть на перезарядці — щоб до
        // готовності вже стояти лицем. Прицілювання ж починається лише з
        // тіку, коли гармата довернулась: інакше 3 с прицілу спливали б під
        // час самого розвороту, і постріл ішов би боком.
        //
        // Але «довернулась» тут ширше, ніж isFacingPoint: та відповідає з
        // допуском 0.02 рад, а ціль, яка просто ЙДЕ, зсуває пеленг щотіку —
        // близька мішень на 60 од/с дає 0.03 рад за тік. За старим правилом
        // приціл обнулявся щотіку супроводу, і гармата не стріляла по нікому,
        // хто рухається. Тому скидає лише СПРАВЖНІЙ розворот.
        long error = art.facingErrorTo(target.x, target.y);
        art.turnToward(target.x, target.y);
        if (error > AIM_TRACK_TOLERANCE) { art.aimTicks = 0; return; }

        if (!art.isReady()) { art.aimTicks = 0; return; }

        art.aimTicks++;
        if (art.aimTicks >= Artillery.STRIKE_AIM_TICKS) {
            fireArtillery(art, target);
            art.aimTicks = 0;
        }
    }

    /**
     * Поставити гармату на ціль. Зміна цілі обнуляє приціл.
     *
     * <p>Єдине місце, де {@code aimTarget} присвоюється, — щоб не з'явилось
     * другого шляху, який міняє ціль і забуває скинути таймер.
     */
    private static void aimAt(Artillery art, Unit target) {
        if (art.aimTarget == target) return;
        art.aimTarget = target;
        art.aimTicks  = 0;
    }

    /**
     * Чи можна далі працювати по цій самій цілі: жива, в дальності, видима
     * самій гарматі.
     *
     * @return та сама ціль або {@code null}, якщо вона вибула
     */
    private Unit stillValidTarget(Artillery art, Unit target) {
        if (target == null || !target.alive) return null;
        long rangeSq = Fixed.mul(Artillery.STRIKE_RANGE, Artillery.STRIKE_RANGE);
        if (Fixed.dstSq(art.x, art.y, target.x, target.y) > rangeSq) return null;
        if (!canSee(art, target)) return null;
        return target;
    }

    /**
     * Найближчий ворог у радіусі обстрілу, якого ця гармата БАЧИТЬ САМА.
     *
     * <p>Раніше видимість тут не перевірялась зовсім, і артилерія автоматично
     * обстрілювала все в радіусі 220 — включно з тим, чого не бачив ніхто:
     * снаряди летіли в туман по цілях, про які гравець не мав знати.
     * Перевіряти сторонову видимість теж було б неправильно: тоді гармата
     * била б по цілі, яку розвідав чужий фланг, сама її не спостерігаючи.
     */
    private Unit findNearestArtilleryTarget(Artillery art, Array<Unit> all) {
        Unit nearest = null;
        long minDistSq = Fixed.mul(Artillery.STRIKE_RANGE, Artillery.STRIKE_RANGE);
        for (int i = 0; i < all.size; i++) {
            Unit other = all.get(i);
            if (!other.alive || other.team == art.team) continue;
            long d = Fixed.dstSq(art.x, art.y, other.x, other.y);
            if (d > minDistSq) continue;
            if (!canSee(art, other)) continue;
            minDistSq = d; nearest = other;
        }
        return nearest;
    }

    /**
     * Частка дальності, на яку гармата підходить до ручної цілі.
     *
     * <p>Не сама дальність: ціль рухається, і зупинившись рівно на межі
     * гармата втрачала б її з-під обстрілу від першого ж кроку суперника.
     */
    private static final long ART_APPROACH_FACTOR = Fixed.fromFloat(0.75f);

    /**
     * Підійти на позицію для стрільби по ручній цілі — з пошуком шляху.
     *
     * <p>Маршрут будується лише коли гармата стоїть: поки вона йде, шлях уже
     * є, а перебудова щотіку і коштувала б дорого, і смикала б юніт. Коли
     * дійде (або зупиниться) — перерахує з нового місця, з урахуванням того,
     * куди за цей час відійшла ціль.
     */
    private void approachTarget(Artillery art, Unit target) {
        if (art.isMoving()) return;

        long dx = art.x - target.x, dy = art.y - target.y;
        long dist = Fixed.length(dx, dy);
        long stand = Fixed.mul(Artillery.STRIKE_RANGE, ART_APPROACH_FACTOR);

        long tx, ty;
        if (dist == 0) {
            tx = target.x; ty = target.y;
        } else {
            // Стаємо на тій самій прямій, з боку гармати — тобто йдемо просто
            // назустріч, а не оббігаємо ціль по колу.
            tx = target.x + Fixed.mul(Fixed.div(dx, dist), stand);
            ty = target.y + Fixed.mul(Fixed.div(dy, dist), stand);
        }

        long[] path = pathProvider != null ? pathProvider.findPath(art.x, art.y, tx, ty) : null;
        if (path != null) art.followPath(path, tx, ty);
        else              art.moveTo(tx, ty);
    }

    private void fireArtillery(Artillery art, Unit target) {
        // Остання перевірка напрямку перед пострілом: сюди можна дійти лише
        // через прицілювання, але ціль за ці 3 с могла обійти гармату збоку.
        // Допуск той самий, що й у супроводі — інакше постріл зривався б від
        // піврадуса розбіжності, а приціл усе одно обнулявся б викликачем.
        if (art.facingErrorTo(target.x, target.y) > AIM_TRACK_TOLERANCE) {
            art.turnToward(target.x, target.y);
            return;
        }

        // І перевірка очей: за час прицілювання ціль могла зайти в ліс або за
        // пагорб. Стріляти по тому, чого гармата вже не бачить, вона не має.
        if (!canSee(art, target)) return;

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

        // Дульний спалах — на зрізі ствола, тобто зі зсувом уздовж напрямку.
        float facingRad = Fixed.toFloat(art.facing);
        Fx.muzzleBlast(
            Fixed.toFloat(art.x) + (float) Math.cos(facingRad) * Artillery.MUZZLE_OFFSET,
            Fixed.toFloat(art.y) + (float) Math.sin(facingRad) * Artillery.MUZZLE_OFFSET,
            facingRad);
        art.kickRecoil();

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
            // Множник моралі береться з гармати напряму, бо снаряди в грі
            // поки що бувають тільки її. Щойно з'явиться друга зброя по
            // площі, число переїде на сам ArtilleryShell — а до того це
            // означало б поле у знімку, яке ні від чого не залежить.
            u.takeDamage(Fixed.mul(baseDamage, falloff), Artillery.ART_MORALE_SHOCK);
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
        // Дим, іскри й вогонь живуть у спільному пулі — один прохід на всіх.
        Fx.draw(batch);
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
            float cy   = art.worldY() + art.renderSizePx() / 2f + 6f;
            float barW = art.renderSizePx() * 1.2f;
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

    /**
     * Наскільки вбік і за ціль іде трасер промаху, у частках відстані пострілу.
     *
     * <p>Куля мусить ПРОЛЕТІТИ повз, а не спинитись поруч: трасер, що
     * обривається біля цілі, читається як влучання, від якого чомусь не впало
     * здоров'я. Тому вона і зміщується вбік, і проскакує далі.
     */
    private static final float MISS_SIDE_SPREAD = 0.10f;
    private static final float MISS_OVERSHOOT   = 0.18f;

    /**
     * Трасер, що йде повз ціль.
     *
     * <p>Розкид тут береться з {@code MathUtils.random}, а НЕ з ігрового
     * генератора: це чистий візуал, і смикати ним детермінований RNG означало б
     * зсунути послідовність, від якої залежить розкид артилерії. Куди саме
     * пішла куля, що вже нікого не зачепить, на стан гри не впливає.
     */
    private void spawnMissedShot(Unit from, Unit to) {
        float fx = from.worldX(), fy = from.worldY();
        float dx = to.worldX() - fx, dy = to.worldY() - fy;

        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 0.0001f) { spawnShot(from, to); return; }

        float nx = dx / len, ny = dy / len;
        float px = -ny,      py = nx;

        float side = MathUtils.random(-MISS_SIDE_SPREAD, MISS_SIDE_SPREAD) * len;
        float past = (1f + MathUtils.random(0f, MISS_OVERSHOOT)) * len;

        spawnShotAt(fx, fy, fx + nx * past + px * side, fy + ny * past + py * side);
    }

    private void spawnShot(Unit from, Unit to) {
        spawnShotAt(from.worldX(), from.worldY(), to.worldX(), to.worldY());
    }

    private void spawnShotAt(float fromX, float fromY, float toX, float toY) {
        for (int i = 0; i < shots.size; i++) {
            ShotEffect s = shots.get(i);
            if (!s.active) { s.show(fromX, fromY, toX, toY); return; }
        }
        shots.get(0).show(fromX, fromY, toX, toY);
    }

    private void playShot() { AudioManager.playSfx(shotSound, SHOT_GAIN); }

    private static class AttackGroup {
        enum Phase { SPREAD, ADVANCE }
        final Unit target; final Array<AttackOrder> orders = new Array<>();
        Phase phase = Phase.SPREAD;
        AttackGroup(Unit t) { target = t; }
        void addOrder(AttackOrder o) { orders.add(o); }
        void cleanup() {
            for (int i = orders.size-1; i >= 0; i--) {
                AttackOrder o = orders.get(i);
                // Зламаний вибуває з групи нарівні з убитим: інакше шеренга
                // чекала б, поки він займе своє місце, а він біжить у
                // протилежний бік — фаза SPREAD не завершилась би ніколи.
                if (!o.att.alive || o.att.isBroken() || !o.target.alive) orders.removeIndex(i);
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
        final long spreadX, spreadY, finalX, finalY;
        /**
         * Місце цього юніта ВІДНОСНО ЦІЛІ, у світових одиницях.
         *
         * <p>Раніше тут лежав скаляр {@code perpOffset}, а {@code processOrder}
         * щотіку заново виводив вісь наступу з напрямку «цей юніт → ціль». При
         * нульовому зсуві це працювало, бо всі йшли в одну точку; з ненульовим —
         * розвалюється: юніт, який став збоку, бачить ціль під іншим кутом,
         * перераховує перпендикуляр у новий бік і їде далі вбік. Тобто шеренга
         * оберталася б навколо цілі замість того, щоб стояти.
         *
         * <p>Готовий зсув знімає це питання цілком: місце в строю визначається
         * один раз, у момент наказу, і далі просто їде за ціллю.
         */
        final long offX, offY;
        final AttackGroup group;

        AttackOrder(Unit a, Unit t, long sx, long sy, long fx, long fy,
                    long ox, long oy, AttackGroup g) {
            att = a; target = t; spreadX = sx; spreadY = sy; finalX = fx; finalY = fy; group = g;
            offX = ox; offY = oy;
        }
    }
}
