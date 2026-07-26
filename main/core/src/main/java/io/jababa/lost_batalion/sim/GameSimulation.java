package io.jababa.lost_batalion.sim;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.DeterministicRandom;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.net.commands.CommandContext;
import io.jababa.lost_batalion.path.NavGrid;
import io.jababa.lost_batalion.path.PathFinder;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.units.Artillery;
import io.jababa.lost_batalion.units.CombatManager;
import io.jababa.lost_batalion.units.Unit;
import io.jababa.lost_batalion.units.UnitManager;
import io.jababa.lost_batalion.visibility.VisibilitySystem;

/**
 * Весь ігровий стан і його поступ у часі.
 *
 * <p>Симуляція рухається строго тіками по 25 мс: скільки тіків виконати,
 * вирішує {@link LockstepClock}, а сам крок ніколи не залежить від FPS.
 *
 * <p>Межа проста: усе, що тут, входить у стан гри і колись потрапить у
 * checksum. Усе візуальне — трасери, вибухи, маркери, панель — лишається в
 * екрані й живе за часом кадру.
 *
 * <p>Чого тут свідомо НЕМАЄ: камери, вводу, будь-чого з {@code Gdx.input}, і —
 * від етапу 6 — жодного {@code float}. Уся арифметика стану цілочисельна
 * ({@link Fixed}), бо lockstep вимагає побітово однакового результату, а
 * бібліотечні {@code Math.sin}/{@code atan2}/{@code MathUtils} його не дають.
 *
 * <p>Симуляція реалізує {@link CommandContext}: наказ гравця — це чисті дані,
 * і саме тут вони перетворюються на зміну стану.
 */
public class GameSimulation implements CommandContext {

    private final TerrainQuery      terrain;
    private final UnitManager       unitManager;
    private final CombatManager     combatManager;
    private final VisibilitySystem  visibilitySystem;

    /**
     * Єдиний генератор випадкових чисел матчу. Seed приходить від хоста,
     * тому послідовність однакова у всіх.
     */
    private final DeterministicRandom random;

    /** Номер поточного тіку. Єдиний час, який симуляція знає. */
    private int tickNumber;

    /** Розміри карти: у Q47.16 для симуляції, у float — для рендеру й камери. */
    private final long  mapW, mapH;
    private final float mapWidth, mapHeight;

    /**
     * Буфер під юнітів, згаданих у команді. Один на симуляцію: команди
     * виконуються строго послідовно в межах тіку, тож перевикористання
     * безпечне і не смітить об'єктами 40 разів на секунду.
     */
    private final Array<Unit> commandScratch = new Array<>();

    /**
     * Сітка прохідності й пошук по ній.
     *
     * <p>Будуються один раз на матч із масок місцевості. У знімок стану не
     * входять: маски — це ассети, однакові у всіх, тож сітка відтворюється, а
     * не змінюється по ходу гри.
     *
     * <p>Створюються ліниво — при першому наказі з пошуком шляху. Побудова
     * сітки обходить 32 400 клітинок; платити за це в кожному матчі, де ніхто
     * жодного разу не двічі-клікнув, немає сенсу.
     */
    private NavGrid    navGrid;
    private PathFinder pathFinder;

    public GameSimulation(TerrainQuery terrain, float mapWidth, float mapHeight, long rngSeed) {
        this.terrain   = terrain;
        this.mapWidth  = mapWidth;
        this.mapHeight = mapHeight;
        this.mapW      = Fixed.fromFloat(mapWidth);
        this.mapH      = Fixed.fromFloat(mapHeight);
        this.random    = new DeterministicRandom(rngSeed);

        this.unitManager      = new UnitManager();
        this.combatManager    = new CombatManager(unitManager, terrain, random);
        this.visibilitySystem = new VisibilitySystem(terrain);
    }

    /**
     * Початкова розстановка обох армій.
     *
     * <p>Виконується однаково на всіх клієнтах із однакових вхідних даних —
     * інакше матч розійшовся б ще до першого наказу. Ніякого RNG тут поки що
     * немає навмисно: розстановка від карти, а не від випадку.
     *
     * <p>Армії дзеркальні: хост ({@code playerId 0}) ліворуч, гість
     * ({@code playerId 1}) праворуч, склад однаковий.
     *
     * <p>Порядок додавання юнітів визначає їхні id, тому переставляти рядки
     * місцями не можна: id їздять у командах.
     */
    public void spawnInitialForces() {
        spawnArmy(Team.forPlayer(0), Fixed.mul(mapW, Fixed.fromFloat(0.22f)));
        spawnArmy(Team.forPlayer(1), Fixed.mul(mapW, Fixed.fromFloat(0.78f)));

        // Стартовий стан теж має бути «як після тіку»: без цього перший кадр
        // інтерполював би юнітів від нуля координат до їхніх справжніх місць.
        Array<Unit> all = unitManager.getAllUnits();
        for (int i = 0; i < all.size; i++) all.get(i).beginTick();

        // Щоб перший же кадр малювався з правильним туманом, а не показував
        // усе відразу до першого тіку.
        visibilitySystem.update(all);
    }

    private void spawnArmy(Team team, long baseX) {
        unitManager.spawnSquad(team, baseX, mapH >> 1);
        unitManager.spawnSquad(team, baseX, Fixed.div(mapH, Fixed.fromFloat(1.7f)));
        unitManager.spawnSquad(team, baseX, Fixed.div(mapH, Fixed.fromFloat(1.5f)));

        long artY = (mapH >> 1) - Fixed.fromInt(60);
        unitManager.addUnit(new Artillery(team, baseX - Fixed.fromInt(80), artY));
        unitManager.addUnit(new Artillery(team, baseX + Fixed.fromInt(80), artY));
    }

    /**
     * Рівно один крок симуляції.
     *
     * <p>Порядок підсистем зафіксований і однаковий скрізь: рух, бій, видимість.
     * Переставити їх місцями — змінити результат, бо бій дивиться на позиції
     * після руху, а видимість — на те, хто вижив у бою.
     *
     * <p>Команди на цей тік мають бути застосовані ДО виклику — це робить
     * {@link MatchRunner}, бо наказ, відданий на тіку N, мусить впливати вже
     * на рух цього ж тіку в усіх однаково.
     */
    public void tick() {
        tickNumber++;

        unitManager.tick(terrain, mapW, mapH);
        combatManager.tick();
        visibilitySystem.update(unitManager.getAllUnits());
    }

    /**
     * Прибрати з поля армію сторони, яка вибула з матчу.
     *
     * <p>Юніти не видаляються зі списку, а позначаються мертвими: id мусять
     * лишитись валідними. Наказ, відданий перед самим вибуттям, ще може
     * дійти до виконання і послатись на цього юніта — і мусить тихо
     * відсіятись, а не впасти на відсутньому id.
     *
     * <p>Викликається строго з {@code MatchRunner} на призначеному тіку
     * вибуття, однаковому в усіх, — тому це детермінована зміна стану.
     */
    public void removeArmy(Team team) {
        Array<Unit> all = unitManager.getAllUnits();
        Array<Unit> fallen = new Array<>();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (u.team != team || !u.alive) continue;
            fallen.add(u);
        }
        if (fallen.size == 0) return;

        // Спершу зняти накази — інакше бойові групи лишились би з мертвими
        // учасниками й далі тягнули б їх у checksum.
        combatManager.stopUnits(fallen);
        for (int i = 0; i < fallen.size; i++) {
            Unit u = fallen.get(i);
            u.hp = 0;
            u.alive = false;
            u.selected = false;
        }
        unitManager.clearSelection();
        visibilitySystem.update(all);
    }

    // ── CommandContext ────────────────────────────────────────────────────
    //
    // Спільне для всіх методів: юніти беруться за id і фільтруються за
    // власником. Невідомі, мертві й чужі id мовчки випадають — так вимагає
    // контракт CommandContext, і це не захист «про всяк випадок»: юніт цілком
    // законно може загинути за ті два тіки, поки наказ їхав по мережі.
    //
    // Координати приходять уже у Q47.16, тож жодного перетворення тут немає —
    // те, що намалював курсор, і те, що виконає симуляція, це те саме число.

    @Override
    public void moveUnits(int playerId, int[] unitIds, long targetX, long targetY) {
        Array<Unit> units = own(playerId, unitIds);
        if (units.size == 0) return;
        combatManager.cancelAttackOrders(units);
        unitManager.moveUnitsTo(units, targetX, targetY, mapW, mapH);
    }

    /**
     * Рух із пошуком найшвидшого шляху — ОДИН маршрут на всю групу.
     *
     * <p>Шлях будується від центроїда виділення до цілі, і ним ідуть усі. Це і
     * дешевше (один пошук замість десятка), і виглядає правильніше: група
     * рухається разом, а не розпливається окремими стежками. Розходяться юніти
     * лише в кінці — кожен у своє місце в строю, як і при звичайному наказі.
     */
    @Override
    public void pathMoveUnits(int playerId, int[] unitIds, long targetX, long targetY) {
        Array<Unit> units = own(playerId, unitIds);
        if (units.size == 0) return;
        combatManager.cancelAttackOrders(units);

        ensureNavigation();

        // Центроїд: цілочисельне середнє, тож однакове в усіх.
        long cx = 0, cy = 0;
        for (int i = 0; i < units.size; i++) { cx += units.get(i).x; cy += units.get(i).y; }
        cx /= units.size; cy /= units.size;

        long[] waypoints = pathFinder.findPath(cx, cy, targetX, targetY);

        // Спершу розкладаємо цілі по строю звичайним чином — так місця в
        // шерензі однакові з тими, що дає простий наказ.
        unitManager.moveUnitsTo(units, targetX, targetY, mapW, mapH);

        if (waypoints == null) return;   // ціль поруч або шлях не знайдено — йдемо прямо

        // …а потім кажемо кожному пройти спільним маршрутом до свого місця.
        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            u.followPath(waypoints, u.getTargetX(), u.getTargetY());
        }
    }

    private void ensureNavigation() {
        if (navGrid == null) {
            navGrid    = new NavGrid(terrain, mapWidth, mapHeight);
            pathFinder = new PathFinder(navGrid);
        }
    }

    /** Сітка прохідності матчу. Створюється при першій потребі. */
    public NavGrid getNavGrid() { ensureNavigation(); return navGrid; }

    @Override
    public void moveUnitsToLine(int playerId, int[] unitIds, long x1, long y1, long x2, long y2) {
        Array<Unit> units = own(playerId, unitIds);
        if (units.size == 0) return;
        combatManager.cancelAttackOrders(units);
        unitManager.moveUnitsToLine(units, x1, y1, x2, y2, mapW, mapH);
    }

    @Override
    public void moveUnitsToCurve(int playerId, int[] unitIds, long[] pointsXY) {
        Array<Unit> units = own(playerId, unitIds);
        if (units.size == 0 || pointsXY == null || pointsXY.length < 4) return;
        combatManager.cancelAttackOrders(units);
        unitManager.moveUnitsAlongPath(units, pointsXY, mapW, mapH);
    }

    @Override
    public void orderAttack(int playerId, int[] unitIds, int targetUnitId) {
        Array<Unit> units = own(playerId, unitIds);
        if (units.size == 0) return;

        Unit target = unitManager.findById(targetUnitId);
        // Ціль могла загинути, поки наказ їхав, — це не помилка, просто наказ
        // втратив сенс. Атака по своїх не існує як механіка.
        if (target == null || !target.alive || target.team == Team.forPlayer(playerId)) return;

        combatManager.orderAttack(units, target);
    }

    @Override
    public void stopUnits(int playerId, int[] unitIds) {
        Array<Unit> units = own(playerId, unitIds);
        if (units.size == 0) return;
        combatManager.stopUnits(units);
    }

    private Array<Unit> own(int playerId, int[] unitIds) {
        return unitManager.collectOwned(unitIds, Team.forPlayer(playerId), commandScratch);
    }

    // ── Доступ ────────────────────────────────────────────────────────────

    public int getTickNumber()                { return tickNumber; }

    /**
     * Перевести симуляцію на тік зі знімка. Єдиний легальний викликач —
     * {@link SimulationSnapshot}: у звичайному ході гри номер тіку рухає
     * лише {@link #tick()}.
     */
    public void setTickNumber(int tick)       { this.tickNumber = tick; }
    public UnitManager getUnitManager()       { return unitManager; }
    public CombatManager getCombatManager()   { return combatManager; }
    public VisibilitySystem getVisibility()   { return visibilitySystem; }
    public TerrainQuery getTerrain()          { return terrain; }
    public DeterministicRandom getRandom()    { return random; }
    public float getMapWidth()                { return mapWidth; }
    public float getMapHeight()               { return mapHeight; }
    public long  getMapWidthFixed()           { return mapW; }
    public long  getMapHeightFixed()          { return mapH; }
}
