package io.jababa.lost_batalion.ai;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntIntMap;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.capture.CaptureManager;
import io.jababa.lost_batalion.capture.CapturePoint;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.net.commands.AttackCommand;
import io.jababa.lost_batalion.net.commands.GameCommand;
import io.jababa.lost_batalion.net.commands.MoveCommand;
import io.jababa.lost_batalion.net.commands.PathMoveCommand;
import io.jababa.lost_batalion.net.commands.SpawnCommand;
import io.jababa.lost_batalion.sim.GameSimulation;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.units.Artillery;
import io.jababa.lost_batalion.units.Cavalry;
import io.jababa.lost_batalion.units.Unit;
import io.jababa.lost_batalion.units.UnitType;

import java.util.ArrayList;
import java.util.List;

/**
 * Мозок супротивника: читає стан, віддає накази.
 *
 * <h3>Що йому можна знати</h3>
 * Рівно те саме, що й гравцеві. Видимість рахується ОКРЕМО для кожної сторони,
 * тож {@code Unit.isVisibleTo(me)} — уже готова й чесна відповідь. Режиму
 * зазирання немає й не буде: обійти туман війни означало б знецінити механіку,
 * на якій тримається гра.
 *
 * <h3>Чому тут можна float</h3>
 * Клас НЕ входить у симуляцію. Він читає стан і повертає накази; реплікуються
 * накази, а не спосіб, яким до них дійшли (див. {@link BotPlayer}).
 *
 * <h3>Головне правило: не годувати поодинці</h3>
 * Перша версія відправляла кожного новобранця напряму на контактну точку. При
 * одній утримуваній точці прибуток дає одного піхотинця на ~50 секунд, а йти
 * йому 4–20 секунд — тобто бот приходив по одному проти цілого загону й танув,
 * скільки б не купував. Тепер підкріплення йде на ЗБІРНИЙ пункт у тилу, а
 * загін рушає на ціль лише зібравши свій бюджет. Готовність вимірюється не
 * списком, а КУПНІСТЮ: скільки своїх стоять поруч одне з одним. Це та сама
 * величина, яку бачить гравець, коли каже «в нього там купа».
 *
 * <h3>Армія — не одна купа, а загони з бюджетом</h3>
 * Точки ранжуються ({@link #priorityOf}), кожній рахується бюджет
 * ({@link #budgetFor}), найближчі вільні бійці роздаються згори вниз, а резерв
 * іде на НАЙВАЖЧИЙ напрямок. Скільки напрямків ведеться водночас, вирішує
 * {@link Difficulty#fronts}. До цього наступальна ціль була рівно одна, і два
 * боти намертво впирались один в одного при вільній третій точці — 590:590 за
 * десять хвилин.
 *
 * <h3>Три частоти</h3>
 * Наказ СКИДАЄ те, що юніт робив: повторений щочверть секунди наказ руху
 * обнуляє маршрут і лишає роту тупцювати. Тому розподіл — раз на
 * {@link #ASSIGN_PERIOD_TICKS}, покупки — раз на {@link #ECONOMY_PERIOD_TICKS},
 * а бій хоч і переглядається щодумки, наказ віддає лише при ЗМІНІ цілі — і сама
 * ціль тримається, поки жива (див. {@link #fight}).
 */
public class TacticalBrain implements BotBrain {

    private static final int ASSIGN_PERIOD_TICKS  = 80;   // 2 с
    private static final int ECONOMY_PERIOD_TICKS = 40;   // 1 с

    /** Бажаний склад армії, частками. */
    private static final float WANT_INFANTRY = 0.60f;
    private static final float WANT_CAVALRY  = 0.25f;

    /**
     * Допуск «гармата вже на позиції».
     *
     * <p>Тільки для гармат. Для піхоти цей поріг колись означав «уже при ділі»
     * і ламав захоплення: юніт за 80 одиниць від центру, але ЗА межею
     * чотирикутника, більше не отримував наказів і стояв біля точки до кінця
     * матчу. Там тепер рівно {@code CapturePoint.contains}; у гармати ж зони
     * немає, і допуск потрібен — інакше вона переїжджала б на кожен піксель
     * зсуву розрахункової точки.
     */
    private static final float NEAR_POINT = 120f;

    /** З якої відстані група реагує на побаченого ворога. */
    private static final float ENGAGE_RANGE = 240f;

    /** У якому радіусі свої вважаються «купою». */
    private static final float MASS_RADIUS = 200f;

    /** Скільки гармата тримається позаду свого строю. */
    private static final float ARTILLERY_STANDOFF = 140f;

    /** Нижче цієї частки здоров'я юніта відводять у тил (де рівень це вміє). */
    private static final float WOUNDED_HP = 0.30f;

    /** Де саме збірний пункт: частка шляху від свого кута до цілі. */
    private static final float RALLY_FRACTION = 0.45f;

    /**
     * Скільки загін щонайдовше чекає на збірному, поки збереться хвиля.
     *
     * <p>Потрібен, бо поріг хвилі може не набратись НІКОЛИ: остання пара
     * новобранців при бідній економіці — це один юніт на ~50 секунд. Без
     * відсічки вони простояли б у тилу до кінця матчу.
     */
    private static final int WAVE_TIMEOUT_TICKS = 800;   // 20 с

    /** Скільки одиниць відстані «варта» повна смуга здоров'я при виборі цілі. */
    private static final float WEAKEST_WEIGHT = 120f;

    protected final GameSimulation sim;
    protected final Difficulty     level;

    private final int  playerId;
    private final Team me;
    private final Team foe;

    private final List<GameCommand> orders = new ArrayList<>();

    private final Array<Unit> mine   = new Array<>();
    private final Array<Unit> foes   = new Array<>();
    private final Array<Unit> group  = new Array<>();
    /** Хто з групи вже дістає до бою — окремий буфер, бо {@link #group} зайнятий. */
    private final Array<Unit> battle = new Array<>();
    /** Уже випущені вперед — другий буфер маршу, поруч із тими, хто ще збирається. */
    private final Array<Unit> wave   = new Array<>();
    /** Хто зараз тисне на дистанцію вогню — власний буфер, бо {@link #battle} потрібен далі. */
    private final Array<Unit> pressing = new Array<>();
    /** Кіннота, яку притримують, щоб не відірвалась від піхоти. */
    private final Array<Unit> escort = new Array<>();

    /** id юніта → індекс точки, за яку він відповідає. */
    private final IntIntMap assignment = new IntIntMap();
    /** індекс точки → id цілі, по якій група вже йде. */
    private final IntIntMap engagedWith = new IntIntMap();
    /** індекс точки → скільки бійців було в шерензі, коли її шикували. */
    private final IntIntMap engagedSize = new IntIntMap();
    /** індекс точки → з якого тіку можна переставити шеренгу того самого бою. */
    private final IntIntMap nextAttackTick = new IntIntMap();

    /** індекс точки → скільки бійців їй належить за планом. */
    private final IntIntMap budget = new IntIntMap();
    /** індекс точки → скільки бійців їй дісталось насправді. */
    private final IntIntMap squadSize = new IntIntMap();
    /** індекс точки → 1, якщо її загін уже рушив (гістерезис збору). */
    private final IntIntMap committedTo = new IntIntMap();
    /**
     * id юніта → індекс фронту, на який його ВЖЕ випустили зі збірного пункту.
     *
     * <p>Ключове тут — що прапорець на ЮНІТОВІ, а не на напрямку. Поки
     * зобов'язання було на напрямку, воно вмикалось раз і лишалось увімкненим:
     * {@link #assignment} перескладається щодві секунди, свіжий новобранець
     * одразу потрапляв до фронту, позначеного «рушив», і йшов у зону сам.
     * Поріг маси гейтив рівно ПЕРШУ хвилю, а далі йшов рівний струмочок по
     * одному — та сама вада, від якої поріг і робився, тільки з іншого боку.
     *
     * <p>Значення зберігає саме фронт: юніта, переприписаного на інший напрямок,
     * треба зібрати заново, а не вважати випущеним за старим наказом.
     */
    private final IntIntMap releasedFor = new IntIntMap();
    /** Буфер для чистки {@link #releasedFor} від мертвих id. */
    private final IntIntMap releaseScratch = new IntIntMap();
    /** індекс точки → тік, коли звідти востаннє йшла хвиля (для відсічки чекання). */
    private final IntIntMap lastWaveTick = new IntIntMap();

    private static final int[] EMPTY = new int[0];
    /** Індекси точок за спаданням важливості. */
    private int[] objectiveOrder = EMPTY;
    /** Найважчий напрямок — туди йде резерв, гармати й новобранці. −1 до першого розподілу. */
    private int mainFront = -1;

    private int nextAssignTick;
    private int nextEconomyTick;

    public TacticalBrain(GameSimulation sim) {
        this(sim, BotPlayer.PLAYER_ID, Difficulty.NORMAL);
    }

    public TacticalBrain(GameSimulation sim, Difficulty level) {
        this(sim, BotPlayer.PLAYER_ID, level);
    }

    public TacticalBrain(GameSimulation sim, int playerId, Difficulty level) {
        this.sim      = sim;
        this.playerId = playerId;
        this.level    = level;
        this.me       = Team.forPlayer(playerId);
        this.foe      = Team.forPlayer(playerId == 0 ? 1 : 0);

    }

    public Difficulty getLevel() { return level; }
    public int getPlayerId()     { return playerId; }

    // ── Намір назовні (для налагоджувального оверлея) ─────────────────────
    //
    // Читається ЛИШЕ з рендера. Це не інтерфейс керування ботом, а вікно в
    // нього: дивитись матч ботів і не бачити, куди вони збираються, означає
    // бачити ЩО вони роблять і не бачити ЧОМУ — а всі три вади, знайдені при
    // налагодженні, були саме про «чому».

    private CapturePoint lastObjective;
    private final float[] lastRally = new float[2];

    /** Куди бот зараз тисне. {@code null}, поки він ще не думав. */
    public CapturePoint getObjective() { return lastObjective; }
    /** Збірний пункт у світових координатах. */
    public float getRallyX() { return lastRally[0]; }
    public float getRallyY() { return lastRally[1]; }
    /** Чи ГОЛОВНИЙ загін уже рушив, чи ще збирається. */
    public boolean isCommitted() { return lastCommitted; }
    /** Купа головного загону — те число, з яким порівнюється його бюджет. */
    public int getCluster() { return lastCluster; }
    /** Бюджет головного загону — з чим саме порівнюється купа. */
    public int getMassGoal() { return lastGoal; }
    /** Скільки напрямків бот веде зараз. */
    public int getFrontCount() { return lastFronts; }

    private int     lastCluster;
    private int     lastGoal = 1;
    private int     lastFronts;
    private boolean lastCommitted;

    @Override public int decisionPeriodTicks() { return level.thinkPeriodTicks; }

    @Override
    public List<GameCommand> think(int executeTick) {
        // Дограний матч: наказувати нема кому. Симуляція при цьому крутиться
        // далі (інакше lockstep розійшовся б), тож мовчання тут — саме мовчання.
        if (sim.getVictory().isFinished()) return null;

        orders.clear();
        myUnits(mine);
        visibleFoes(foes);

        if (executeTick >= nextEconomyTick) {
            nextEconomyTick = executeTick + ECONOMY_PERIOD_TICKS;
            buy();
        }
        if (executeTick >= nextAssignTick) {
            nextAssignTick = executeTick + ASSIGN_PERIOD_TICKS;
            // Порядок обов'язковий: спершу хто куди приписаний, і лише потім
            // чи зібрався кожен загін — готовність тепер міряється в межах
            // ЗАГОНУ, а не по всій армії.
            assignObjectives(executeTick);
            updateWaves(executeTick);
            march(executeTick);
            // ПІСЛЯ маршу: охорона знімає юнітів із походу, а не навпаки.
            if (level.usesArtillery) protectGuns(executeTick);

            // Знімок наміру для оверлея — після рішень, щоб показував те, що
            // бот щойно вирішив, а не те, що збирався.
            int mainIndex = mainFront >= 0 && mainFront < points().size ? mainFront : -1;
            lastObjective = mainIndex < 0 ? null : points().get(mainIndex);
            float[] rally = rallyPoint(lastObjective);
            lastRally[0] = rally[0];
            lastRally[1] = rally[1];
            lastCluster   = mainIndex < 0 ? 0 : biggestCluster(mainIndex);
            lastGoal      = mainIndex < 0 ? 1 : budget.get(mainIndex, 1);
            lastCommitted = mainIndex >= 0 && committedTo.get(mainIndex, 0) == 1;
            lastFronts    = squadSize.size;
        }
        fight(executeTick);
        if (level.retreatsWounded) retreatWounded();

        return orders;
    }

    // ── Збір сил ──────────────────────────────────────────────────────────

    /**
     * Чи вже пора йти — окремо для КОЖНОГО загону і для кожної ХВИЛІ.
     *
     * <p>Загін іде, коли зібрав купу зі свого бюджету, і повертається
     * збиратись, лише коли від нього лишилось менше половини. Є випадки, коли
     * він іде НЕЗАЛЕЖНО від маси:
     * <ul>
     *   <li>бюджет і так одиничний — це дешевий загін на порожню точку, йому
     *       нема кого чекати;</li>
     *   <li>у бота немає жодної точки — сидіти в тилу означає програти за
     *       очками без бою;</li>
     *   <li>цю точку зривають — оборона не чекає, поки збереться загін;</li>
     *   <li>рівень узагалі не вміє збиратись ({@code massThreshold == 1}).</li>
     * </ul>
     *
     * <p><b>Хвиля не одна.</b> Перша йде на повний бюджет, а кожне наступне
     * поповнення чекає на збірному свою, меншу купу — воно приєднується до
     * кулака, який уже стоїть на точці, тож рівно стільки війська йому не
     * потрібно. Раніше наступних хвиль не було взагалі: {@link #committedTo}
     * вмикався один раз, і далі кожен новобранець ішов у зону поодинці.
     */
    private void updateWaves(int executeTick) {
        Array<CapturePoint> pts = points();
        boolean desperate = sim.getCapturePoints().countOwned(me) == 0;

        for (int p = 0; p < pts.size; p++) {
            int size = squadSize.get(p, 0);
            if (size == 0) {
                committedTo.remove(p, 0);
                lastWaveTick.remove(p, 0);
                continue;
            }

            CapturePoint point = pts.get(p);
            int want = budget.get(p, 1);
            boolean first = committedTo.get(p, 0) == 0;
            boolean forced = want <= 1
                          || level.massThreshold <= 1
                          || desperate
                          || underThreat(point);

            if (!forced && !first && size < Math.max(2, want / 2)) {
                // Відкат міряється ВИСНАЖЕННЯМ, а не купністю, і це принципово:
                // рота на марші розтягується в колону сама собою, купність
                // падає — і бот, який дивився на неї, розвертався на півдорозі
                // збиратись, потім знову вперед, і так усю гру. Виміряно:
                // HARD у такому стані програвав EASY, бо той просто грав.
                //
                // Відкочується ВЕСЬ загін разом із випусками: залишки, розкидані
                // по дорозі, мусять зібратись, а не доходити поодинці.
                committedTo.remove(p, 0);
                unrelease(p);
                first = true;
            }

            // Відлік чекання починається з появи загону, а не з першої хвилі:
            // інакше перший збір не має відсічки взагалі.
            if (!lastWaveTick.containsKey(p)) lastWaveTick.put(p, executeTick);

            // ПЕРША хвиля — рівно як раніше: купа з бюджету, будь-де. Це число
            // виміряне (massThreshold має вузьке дно 4–5), і чіпати його не
            // треба; вада була не в ньому, а в тому, що хвиля була одна.
            //
            // ПОПОВНЕННЯ ж не чекає взагалі: воно йде до СВОГО ЗАГОНУ
            // ({@link #gatherPoint}) і вважається випущеним, щойно до нього
            // пристало. Чекання на збірному тут пробувалось першим і виміряно
            // ГІРШИМ (6/16 перемог проти 10/16, самотніх смертей не менше):
            // поки поповнення стоїть у тилу, точки віддаються, а доходить воно
            // однаково поодинці, тільки пізніше.
            float[] gather = gatherPoint(p, point);
            int waiting = first ? biggestCluster(p) : waitingAt(p, gather);
            if (waiting == 0 && !forced) continue;

            // ПОВНИЙ бюджет, а не «скільки є».
            //
            // Було `Math.min(want, size)`, і це знецінювало поріг маси рівно
            // там, де він потрібен: {@link #budgetFor} рахує бюджет за баченими
            // ворогами, тож проти роти на точці want виходив 8, — але якщо в
            // загоні троє, min давав 3, і трійка вирушала на вісьмох. Саме це
            // видно оком: загін марширує в купу ворогів і не звертає. Тепер він
            // просто не виходить, поки не набере, скільки треба; примусові
            // випадки (зрив своєї точки, жодної точки, одиничний бюджет) свою
            // силу зберігають.
            int need = first ? want : 1;
            boolean timedOut = executeTick - lastWaveTick.get(p, executeTick) >= WAVE_TIMEOUT_TICKS;

            // Перша хвиля виступає ЦІЛКОМ, скільки б не розтягнулась: саме так
            // це й міряли. Поповнення — лише те, що вже пристало до загону.
            if (forced || waiting >= need || timedOut)
                releaseWave(p, executeTick, gather, forced || first);
        }
    }

    /**
     * Куди йде поповнення цього напрямку.
     *
     * <p>Поки загін збирається — на збірний пункт у тилу. Щойно він рушив —
     * до НЬОГО САМОГО, у середину його маси, а не на точку. Різниця в цьому і
     * є виправленням струмочка: новобранець, посланий на точку, приходив туди
     * сам і під вогонь, а посланий до своїх — приходить до своїх, хоч би де
     * вони зараз були, і далі отримує накази разом з ними.
     */
    private float[] gatherPoint(int front, CapturePoint point) {
        float sx = 0f, sy = 0f;
        int n = 0;
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery || assignment.get(u.id, -1) != front) continue;
            if (!released(u)) continue;
            sx += u.worldX(); sy += u.worldY(); n++;
        }
        if (n == 0) return rallyPoint(point);
        return new float[] { sx / n, sy / n };
    }

    /** Скільки бійців фронту вже зібралось у заданій точці й чекає випуску. */
    private int waitingAt(int front, float[] spot) {
        int n = 0;
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery || assignment.get(u.id, -1) != front) continue;
            if (released(u)) continue;
            if (Math.hypot(u.worldX() - spot[0], u.worldY() - spot[1]) <= MASS_RADIUS) n++;
        }
        return n;
    }

    /**
     * Випустити хвилю: тих, хто приписаний до фронту і вже пристав до загону.
     * Хто ще в дорозі — піде наступною; інакше «хвиля» розчіплюється дорогою і
     * кожен доходить сам.
     *
     * <p>{@code all} знімає умову близькості: так виступає ПЕРША хвиля (вона
     * йде цілим загоном, хоч би як розтягнулась) і будь-яка примусова —
     * оборона не чекає, поки всі доїдуть.
     */
    private void releaseWave(int front, int executeTick, float[] gather, boolean all) {
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery || assignment.get(u.id, -1) != front) continue;
            if (!all
                && Math.hypot(u.worldX() - gather[0], u.worldY() - gather[1]) > MASS_RADIUS) continue;
            releasedFor.put(u.id, front);
        }
        committedTo.put(front, 1);
        lastWaveTick.put(front, executeTick);
    }

    /**
     * Викинути з {@link #releasedFor} тих, кого вже немає серед живих.
     *
     * <p>Мапа переживає всю партію, а мертвих юнітів за десять хвилин
     * набирається більше, ніж живих. {@link IntIntMap} не має видалення за
     * умовою, тож простіше перекласти вцілілих у буфер.
     */
    private void pruneReleased() {
        if (releasedFor.size == 0) return;
        releaseScratch.clear();
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            int front = releasedFor.get(u.id, Integer.MIN_VALUE);
            if (front != Integer.MIN_VALUE) releaseScratch.put(u.id, front);
        }
        releasedFor.clear();
        releasedFor.putAll(releaseScratch);
    }

    /** Повернути фронт у стан збору: всі його випуски скасовано. */
    private void unrelease(int front) {
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (releasedFor.get(u.id, -1) == front) releasedFor.remove(u.id, 0);
        }
    }

    /**
     * Чи цього юніта вже випустили вперед.
     *
     * <p>Порівнюється саме з ПОТОЧНОЮ припискою: юніт, переприписаний на інший
     * напрямок, випущеним не рахується й іде збиратись на новий збірний.
     */
    private boolean released(Unit u) {
        if (u instanceof Artillery) {
            // Гармата власного збору не має: вона рушає, коли рушив головний
            // загін. Сам по собі цей прапорець майже нічого не гейтить —
            // виміряно, що він вмикається на першому ж циклі й не відкочується;
            // від'їзд гармати вперед стримує не він, а GUN_BEHIND у marchArtillery.
            if (mainFront < 0) return true;
            return committedTo.get(mainFront, 0) == 1;
        }
        int front = assignment.get(u.id, -1);
        if (front < 0) return true;
        return releasedFor.get(u.id, -1) == front;
    }

    /**
     * Найбільша купа В МЕЖАХ ЗАГОНУ цієї точки.
     *
     * <p>Груба, але чесна міра: для кожного бійця рахуємо, скільки своїх у
     * {@link #MASS_RADIUS}, і беремо максимум. Гармата не рахується — вона не
     * тримає лінію, і рота з двох піхотинців плюс гармати не є групою з трьох.
     * Рахувати по ВСІЙ армії, як було, тепер не можна: тоді кулак на одному
     * напрямку зараховувався б як готовність загону на іншому.
     *
     * <p>Готовність ХВИЛІ міряється не цим, а {@link #waitingAtRally}: там
     * важливо, скільки бійців уже стоїть на збірному, а не наскільки купно
     * розтягнулась армія взагалі.
     */
    private int biggestCluster(int point) {
        int best = 0;
        for (int i = 0; i < mine.size; i++) {
            Unit a = mine.get(i);
            if (a instanceof Artillery || assignment.get(a.id, -1) != point) continue;
            int near = 1;
            for (int j = 0; j < mine.size; j++) {
                Unit b = mine.get(j);
                if (b == a || b instanceof Artillery || assignment.get(b.id, -1) != point) continue;
                if (Math.hypot(a.worldX() - b.worldX(), a.worldY() - b.worldY()) <= MASS_RADIUS) near++;
            }
            if (near > best) best = near;
        }
        return best;
    }

    /**
     * Збірний пункт — позаду, на шляху від свого кута до цілі.
     *
     * <p>Не сам кут: підкріплення й так виходить звідти, і збиратись там означає
     * потім іти повний шлях удруге. Не сама ціль: там бій. Точка на
     * {@link #RALLY_FRACTION} шляху — це «за спиною фронту», куди новачок
     * дійде сам і де його не вб'ють поодинці.
     */
    private float[] rallyPoint(CapturePoint objective) {
        float homeX = playerId == 0 ? 0f : sim.getMapWidth();
        float homeY = playerId == 0 ? 0f : sim.getMapHeight();
        if (objective == null) return new float[] { homeX, homeY };

        float tx = Fixed.toFloat(objective.x), ty = Fixed.toFloat(objective.y);
        return new float[] {
            homeX + (tx - homeX) * RALLY_FRACTION,
            homeY + (ty - homeY) * RALLY_FRACTION
        };
    }

    // ── Економіка ─────────────────────────────────────────────────────────

    /**
     * Купівля одного юніта за раз.
     *
     * <p>Саме одного: золото списується на тіку ВИКОНАННЯ, тож пачка замовлень
     * в одному наказі частково провалилась би — перше списання пройшло б, решта
     * тихо відсіялась на {@code economy.spend}.
     *
     * <p>Новобранець іде на ЗБІРНИЙ пункт, а не на ціль. Це і є виправлення
     * струмочка: раніше кожен куплений юніт вирушав просто в бій і гинув сам.
     */
    private void buy() {
        int usable = (int) (gold() * (1f - level.goldReserve));

        UnitType want = nextUnitType();
        if (want == null || usable < want.cost) return;

        float[] spot = rallyPoint(mainObjective());
        order(new SpawnCommand(playerId, want.ordinal(),
                               Fixed.fromFloat(spot[0]), Fixed.fromFloat(spot[1])));
    }

    /** Чого зараз бракує складу — беремо найбільший недобір проти бажаних часток. */
    private UnitType nextUnitType() {
        int inf = 0, cav = 0, art = 0;
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery)    art++;
            else if (u instanceof Cavalry) cav++;
            else                           inf++;
        }
        int total = inf + cav + art;
        if (total == 0) return UnitType.INFANTRY;

        float wantArt = level.usesArtillery ? 1f - WANT_INFANTRY - WANT_CAVALRY : 0f;
        float wantInf = level.usesArtillery ? WANT_INFANTRY
                                            : WANT_INFANTRY / (WANT_INFANTRY + WANT_CAVALRY);
        float wantCav = 1f - wantInf - wantArt;

        float lackInf = wantInf - inf / (float) total;
        float lackCav = wantCav - cav / (float) total;
        float lackArt = wantArt - art / (float) total;

        if (lackArt >= lackInf && lackArt >= lackCav && wantArt > 0f) return UnitType.ARTILLERY;
        if (lackCav >= lackInf)                                       return UnitType.CAVALRY;
        return UnitType.INFANTRY;
    }

    // ── Цілі ──────────────────────────────────────────────────────────────

    /**
     * Чи цю точку в бота ЗАРАЗ забирають.
     *
     * <p>{@code holder} — той, хто тягне прогрес; якщо він чужий, точку зривають
     * просто зараз. Реакція на це — єдине, що змушує бота повернутись, і саме
     * її колись бракувало: він ішов уперед, поки за спиною втрачав усе.
     */
    private boolean underThreat(CapturePoint p) {
        // Ознака — ПРОСІДАННЯ прогресу на своїй точці, а не чужий holder.
        //
        // Було `owner == me && holder == foe`, і ці два стани взаємно виключні
        // за побудовою: `CaptureManager` при зриві лишає holder власником і
        // тільки гасить progress, а holder стає чужим рівно в той тік, коли
        // owner обнуляється (CaptureManager:118-122). Тобто умова не могла
        // справдитись НІКОЛИ — уся оборона точок була мертвим кодом, і рівні з
        // `defendsPoints` нічим від інших у цьому не відрізнялись.
        //
        // `progress < FULL` на своїй точці — це рівно те, що бачить гравець:
        // смуга захоплення поповзла вниз. Ніякого зазирання тут немає.
        return level.defendsPoints && p.owner == me && p.holder == me
            && p.progress < CaptureManager.FULL;
    }

    /**
     * Куди дивиться армія в цілому: найважчий напрямок.
     *
     * <p>Саме він, а не найпріоритетніший — туди йде резерв, туди ж їдуть
     * гармати й підкріплення, і збірний пункт мусить бути один із ними.
     * До першого розподілу його ще немає, тоді береться за пріоритетом.
     */
    private CapturePoint mainObjective() {
        Array<CapturePoint> pts = points();
        if (pts.size == 0) return null;
        if (mainFront >= 0 && mainFront < pts.size) return pts.get(mainFront);

        CapturePoint best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < pts.size; i++) {
            float k = priorityOf(pts.get(i));
            if (k < bestScore) { bestScore = k; best = pts.get(i); }
        }
        return best;
    }

    /**
     * Розподіл сил по напрямках — по одному загону на точку, кожному свій
     * бюджет.
     *
     * <h3>Що тут було не так</h3>
     * Раніше «наступальна ціль» була рівно ОДНА: гарнізони лишались на вже
     * своїх точках, а весь залишок армії — у {@code mainIndex}. Тобто бот
     * фізично не міг заходити на дві точки водночас, скільки б у нього не було
     * війська. У дзеркальному матчі це давало намертво заклинений рахунок:
     * двоє тримають по точці, третя вільна, і жоден не має чим її взяти, бо
     * весь кулак стоїть навпроти чужого кулака. Виміряно 590:590 за десять
     * хвилин.
     *
     * <p>Спроба полагодити це «летючим загоном із усієї кінноти»
     * ({@code splitsForces}) провалилась і провалилась заслужено: кіннота дає
     * 26 урону проти 15 у піхоти, і забрати ЇЇ з головного бою означає програти
     * головний бій. Різниця тут у тому, що ділиться не рід військ, а
     * ЧИСЕЛЬНІСТЬ: кожен напрямок отримує рівно стільки, скільки йому треба, і
     * лише з того, що лишилось після напрямків важливіших.
     *
     * <h3>Порядок</h3>
     * Напрямки ранжуються (див. {@link #priorityOf}), кожному рахується
     * бюджет, і найближчі вільні юніти роздаються згори вниз. Хто лишився —
     * до головного напрямку: резерв стоїть за головним ударом, а не розмазується.
     * Скільки напрямків бот веде водночас, вирішує {@link Difficulty#fronts} —
     * це і є та навичка, якої новачок не має.
     */
    private void assignObjectives(int executeTick) {
        Array<CapturePoint> pts = points();
        assignment.clear();
        budget.clear();
        squadSize.clear();
        pruneReleased();
        if (pts.size == 0) { objectiveOrder = EMPTY; return; }

        objectiveOrder = rankObjectives(pts);

        // Оборонці лишаються там, куди їх послали, доки не вийде замок.
        //
        // Без цього бот беззахисний перед найдешевшим прийомом у жанрі: кіннота
        // (швидкість 40) заходить на тилову точку, {@link #priorityOf} ставить
        // її першою, розподіл тягне туди піхоту (швидкість 20), кіннота йде
        // далі — і наступний же цикл тягне ту саму піхоту назад. Кулак їздить
        // по карті й не б'ється ніде. Виміряно зондом {@code FlipStand}: бот
        // програвав 11 матчів із 12, втрачаючи по 14–21 юніта проти 3–15 у
        // нальотчика, при тому що той узагалі не шукав генеральної битви.
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery || executeTick >= lockedUntil.get(u.id, 0)) continue;
            int p = lockedTo.get(u.id, -1);
            if (p < 0 || p >= pts.size) continue;
            assignment.put(u.id, p);
            squadSize.put(p, squadSize.get(p, 0) + 1);
        }

        int combat = combatCount();
        int fronts = Math.max(1, Math.min(level.fronts, objectiveOrder.length));
        int opened = 0;
        for (int slot = 0; slot < objectiveOrder.length && opened < fronts; slot++) {
            int p = objectiveOrder[slot];
            if (opened > 0 && !canOpenSecondary(pts.get(p), combat)) continue;
            opened++;
            int want = budgetFor(pts.get(p));
            budget.put(p, want);

            // Оборона зриву — робота для ШВИДКИХ. Піхота, послана навздогін
            // кінноті, не наздожене її ніколи: 20 проти 40.
            boolean urgent = level.defendsPoints && underThreat(pts.get(p));

            for (int taken = squadSize.get(p, 0); taken < want; taken++) {
                Unit best = null;
                float bestDist = Float.MAX_VALUE;
                for (int i = 0; i < mine.size; i++) {
                    Unit u = mine.get(i);
                    if (u instanceof Artillery || assignment.containsKey(u.id)) continue;
                    float d = dist(u, pts.get(p));
                    if (urgent && u instanceof Cavalry) d -= CAVALRY_DEFENCE_BONUS;
                    if (d < bestDist) { bestDist = d; best = u; }
                }
                if (best == null) break;
                assignment.put(best.id, p);
                squadSize.put(p, squadSize.get(p, 0) + 1);
                if (urgent) {
                    lockedTo.put(best.id, p);
                    lockedUntil.put(best.id, executeTick + DEFEND_LOCK_TICKS);
                }
            }
        }

        // Резерв — на найважчий напрямок, а НЕ на найпріоритетніший.
        //
        // Різниця не теоретична. На старті всі точки нічиї, тобто рівні за
        // класом, і першою в списку стає просто найближча до свого кута —
        // порожня, з бюджетом у гарнізон. Скинути туди весь залишок означало б
        // тримати всю армію на точці, за яку ніхто не б'ється, поки решту карти
        // забирають. Найбільший бюджет — це рівно те місце, де є з ким битись.
        int mainIndex = heaviestFront();
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery || assignment.containsKey(u.id)) continue;
            assignment.put(u.id, mainIndex);
            squadSize.put(mainIndex, squadSize.get(mainIndex, 0) + 1);
        }
        mainFront = mainIndex;
    }

    /**
     * Чи можна відкривати ДРУГИЙ (і далі) напрямок на цю точку.
     *
     * <p>Дві умови, і обидві куплені кров'ю на стенді. Перша: армії має
     * вистачати — головний кулак плюс гарнізон; інакше «другий напрямок» це
     * просто половина кулака, знята з головного бою. Друга: точка мусить бути
     * на СВОЇЙ половині шляху. Дрібний загін, посланий у чужий тил, іде наосліп
     * — туман війни ховає те, що там стоїть, тож бюджет за побаченими ворогами
     * рахує нуль, — і гине цілком. Виміряно: з відкритими напрямками на весь
     * бік HARD втрачав 7 юнітів із 13 за півтори хвилини й програвав EASY, який
     * просто тримав усе в одній купі.
     *
     * <p>Наслідок навмисний: чужу або далеку точку бот бере ГОЛОВНИМ кулаком,
     * коли вона стане головним напрямком, а не відщипнутим загоном.
     */
    private boolean canOpenSecondary(CapturePoint p, int combat) {
        if (combat < level.massThreshold + level.garrison) return false;

        float px = Fixed.toFloat(p.x), py = Fixed.toFloat(p.y);
        float w = sim.getMapWidth(), h = sim.getMapHeight();
        float homeX = playerId == 0 ? 0f : w, homeY = playerId == 0 ? 0f : h;
        float foeX  = playerId == 0 ? w  : 0f, foeY = playerId == 0 ? h : 0f;
        return Math.hypot(px - homeX, py - homeY) <= Math.hypot(px - foeX, py - foeY);
    }

    /** Скільки живих бійців без гармат — міра сили, а не строю. */
    private int combatCount() {
        int n = 0;
        for (int i = 0; i < mine.size; i++) if (!(mine.get(i) instanceof Artillery)) n++;
        return n;
    }

    /** Напрямок із найбільшим бюджетом; при рівних — найпріоритетніший. */
    private int heaviestFront() {
        int best = objectiveOrder[0], bestWant = -1;
        for (int slot = 0; slot < objectiveOrder.length; slot++) {
            int p = objectiveOrder[slot];
            int want = budget.get(p, -1);
            if (want > bestWant) { bestWant = want; best = p; }
        }
        return best;
    }

    /**
     * Скільки бійців варта ця точка.
     *
     * <p>Порожня точка, біля якої нікого немає, береться ГАРНІЗОНОМ, а не
     * кулаком: посилати туди пів армії означає просто не мати армії там, де
     * б'ються. А от точку, за яку є з ким битись, дешевим загоном брати не
     * можна — це те саме годування поодинці, від якого існує поріг маси.
     */
    private int budgetFor(CapturePoint point) {
        int foesThere = foesNear(point);
        boolean contested = point.owner == foe || foesThere > 0;
        if (!contested) return Math.max(1, level.garrison);
        // Бачений ворог задає нижню межу: приходити на точку, де стоїть рота,
        // з фіксованою п'ятіркою означає програвати той самий бій щоразу.
        return Math.max(Math.max(2, level.massThreshold),
                        (int) Math.ceil(foesThere * level.attackOdds) + 1);
    }

    /** Скільки видимих ворогів біля точки — тобто чи й з ким доведеться битись. */
    private int foesNear(CapturePoint point) {
        float px = Fixed.toFloat(point.x), py = Fixed.toFloat(point.y);
        int n = 0;
        for (int i = 0; i < foes.size; i++) {
            Unit f = foes.get(i);
            if (Math.hypot(f.worldX() - px, f.worldY() - py) <= ENGAGE_RANGE) n++;
        }
        return n;
    }

    /**
     * Індекси точок за спаданням важливості.
     *
     * <p>Сортування вставками: точок одиниці, а порядок мусить бути стійким —
     * від нього залежить, куди піде наступне поповнення, і перетасовування
     * рівних варіантів щодві секунди перекидало б загони туди-сюди.
     */
    private int[] rankObjectives(Array<CapturePoint> pts) {
        int n = pts.size;
        int[] idx = new int[n];
        float[] key = new float[n];
        for (int i = 0; i < n; i++) { idx[i] = i; key[i] = priorityOf(pts.get(i)); }
        for (int i = 1; i < n; i++) {
            int ji = idx[i]; float jk = key[i]; int k = i - 1;
            while (k >= 0 && key[k] > jk) { idx[k + 1] = idx[k]; key[k + 1] = key[k]; k--; }
            idx[k + 1] = ji; key[k + 1] = jk;
        }
        return idx;
    }

    /**
     * Важливість напрямку: менше — терміновіше.
     *
     * <p>Свою точку, яку зривають, боронимо першою: вона вже дає прибуток і
     * очки, і втратити її дорожче, ніж не взяти чужу. Далі НІЧИЯ — вона
     * дістається дешевше за всі інші, і саме її обидва боти колись не брали
     * взагалі. Чужа — третьою; своя спокійна — останньою, її досить пильнувати.
     *
     * <p>Крок рангу свідомо більший за будь-яку відстань на карті, тож відстань
     * розв'язує лише нічиї всередині одного класу.
     */
    private float priorityOf(CapturePoint p) {
        float rank;
        if (underThreat(p))       rank = 0f;
        else if (p.owner == null) rank = 4000f;
        else if (p.owner == foe)  rank = 8000f;
        else                      rank = 12000f;

        float homeX = playerId == 0 ? 0f : sim.getMapWidth();
        float homeY = playerId == 0 ? 0f : sim.getMapHeight();
        return rank + (float) Math.hypot(Fixed.toFloat(p.x) - homeX,
                                         Fixed.toFloat(p.y) - homeY);
    }

    // ── Рух ───────────────────────────────────────────────────────────────

    /**
     * Наказ віддається ОДИН на групу: {@code PathMoveCommand} бере масив id, і
     * саме так група отримує спільний маршрут із особистими смугами — строй
     * складається сам по дорозі. Окремі накази дали б купу в одній точці.
     */
    private void march(int marchTick) {
        Array<CapturePoint> pts = points();
        if (pts.size == 0) return;

        for (int p = 0; p < pts.size; p++) {
            if (squadSize.get(p, 0) == 0) continue;

            CapturePoint point = pts.get(p);
            float[] rally = gatherPoint(p, point);

            // Два накази на напрямок, бо в одному загоні тепер два стани:
            // випущена хвиля йде на точку, поповнення — до неї.
            group.clear();
            wave.clear();
            for (int i = 0; i < mine.size; i++) {
                Unit u = mine.get(i);
                if (assignment.get(u.id, -1) != p || u instanceof Artillery) continue;
                // Хто боронить гармату — не чіпаємо: інакше наступний цикл
                // покликав би його назад на точку, і охорона не встигла б нікуди.
                if (guarding(u, marchTick)) continue;
                if (marchTick < heldUntil.get(u.id, 0)) continue;   // притримана кіннота
                // Хто вже йде — не чіпаємо: повторний наказ обнулив би маршрут.
                if (u.isMoving()) continue;
                // Хто б'ється — тим паче: наказ руху скасовує наказ атаки, і
                // юніт вийшов би з-під вогню рівно посеред бою.
                if (inContact(u)) continue;

                if (released(u)) {
                    // Ціль хвилі — сама точка.
                    // ТІЛЬКИ входження в зону, і це важливо. Раніше тут стояло
                    // ще й «або ближче за NEAR_POINT = 120», і саме воно ламало
                    // захоплення: юніт, який став за 80 одиниць від центру, але
                    // ЗА межею чотирикутника, вважався таким, що при ділі, і
                    // більше не отримував жодного наказу. Бот приходив, ставав
                    // поруч із точкою і не брав її до кінця матчу.
                    if (point.contains(u.x, u.y)) continue;
                    wave.add(u);
                } else {
                    // Уже в купі біля збірного — стоїмо й чекаємо решту.
                    if (Math.hypot(u.worldX() - rally[0], u.worldY() - rally[1]) < MASS_RADIUS) continue;
                    group.add(u);
                }
            }

            if (group.size > 0)
                order(new PathMoveCommand(playerId, idsOf(group),
                                          Fixed.fromFloat(rally[0]), Fixed.fromFloat(rally[1])));

            float[] spot = approachSpot(point);
            holdBackHorse(p, spot, marchTick);
            if (wave.size > 0)
                order(new PathMoveCommand(playerId, idsOf(wave),
                                          Fixed.fromFloat(spot[0]), Fixed.fromFloat(spot[1])));
        }

        marchArtillery(mainObjective(), marchTick);
    }

    /**
     * Наскільки кінноті дозволено випереджати піхоту, перш ніж її притримають.
     *
     * <p>Не нуль: рівно в лінію її не поставити, бо наказ віддається раз на дві
     * секунди, а за цей час кіннота проходить 80 одиниць.
     */
    private static final float COHESION_PAD = 120f;

    /** Скільки притримана кіннота лишається без нових наказів. */
    private static final int HOLD_TICKS = 240;   // 6 с

    /** id кіннотника → до якого тіку його не чіпають після притримання. */
    private final IntIntMap heldUntil = new IntIntMap();

    /**
     * Не дати кінноті прийти в бій самій.
     *
     * <p>Хвиля виходить однією групою, але кіннота має швидкість 40 проти 20 у
     * піхоти — тобто ДО ЦІЛІ ВОНА ДОХОДИТЬ УДВІЧІ ШВИДШЕ і зустрічає ворога
     * сама. Поріг маси тут не рятує: він рахує, скільки вийшло, а не скільки
     * дійшло разом. Саме це видно оком — «кіннота йде насмерть».
     *
     * <p>Тому та кіннота, що відірвалась, дістає за ціль не точку, а СВОЮ
     * ПІХОТУ: вона підтягується до лінії й іде далі разом з нею. Вилучається з
     * {@link #wave}, щоб не отримати обидва накази в одному тіку.
     */



    private void holdBackHorse(int front, float[] spot, int marchTick) {
        if (!level.holdsFormation) return;

        // Центр піхоти рахується по ВСІХ випущених, у тому числі тих, що йдуть.
        // Брати тільки нерухомих (тобто те, що зібрав march) не можна: на марші
        // нерухома піхота — це саме та, що вже прийшла, і кіннота порівнювалась
        // би сама з собою.
        float cx = 0f, cy = 0f;
        int n = 0;
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (assignment.get(u.id, -1) != front) continue;
            if (u instanceof Artillery || u instanceof Cavalry || !released(u)) continue;
            cx += u.worldX(); cy += u.worldY(); n++;
        }
        if (n == 0) return;                       // сама кіннота — рівнятись нема на кого
        cx /= n; cy /= n;

        float footDist = (float) Math.hypot(cx - spot[0], cy - spot[1]);

        escort.clear();
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (!(u instanceof Cavalry) || assignment.get(u.id, -1) != front) continue;
            if (!released(u) || guarding(u, marchTick)) continue;
            // Уже притриманий — не наказувати вдруге. Наказ, повторений щодві
            // секунди на центр маси, що сам рухається, скидає маршрут щоразу.
            if (marchTick < heldUntil.get(u.id, 0)) continue;
            // Хто вже б'ється — не чіпати. Витягти його звідти означало б
            // відступ, а відступ у цій грі виміряно програшним: розриву
            // контакту немає, і той, хто пішов, дістає урон у спину задарма.
            if (inContact(u)) continue;
            float d = (float) Math.hypot(u.worldX() - spot[0], u.worldY() - spot[1]);
            if (d >= footDist - COHESION_PAD) continue;   // ще не відірвалась
            escort.add(u);
        }
        if (escort.size == 0) return;

        // Притримувати ЛИШЕ перед справжньою смертю, а не завжди.
        //
        // Безумовне вирівнювання по піхоті виміряно дорогим: HARD проти NORMAL
        // 6/12 замість 12/12. Кіннота це 26 урону проти 15 у піхоти й удвічі
        // більша швидкість, тобто темп усієї армії; гальмувати її там, де
        // попереду порожньо, означає віддати і темп, і точки. А от заїхати
        // самою в юрбу — те, від чого вона й гине.
        int foesThere = 0;
        for (int i = 0; i < foes.size; i++) {
            Unit f = foes.get(i);
            if (Math.hypot(f.worldX() - spot[0], f.worldY() - spot[1]) <= ENGAGE_RANGE) foesThere++;
        }
        if (foesThere < escort.size) return;

        // Тут СВІДОМО віддається наказ тому, хто вже йде, — єдине таке місце в
        // боті. Марш узагалі не чіпає рухомих юнітів, бо повторний наказ скидає
        // маршрут; але кіннота, яка вдвічі швидша за піхоту, саме В РУСІ й
        // відривається, тож правило «не чіпати рухомих» означало б, що
        // стримати її не може ніщо. Перевірено виміром: доки холдбек дивився
        // лише на нерухомих, він не спрацьовував майже ніколи.
        for (int i = wave.size - 1; i >= 0; i--)
            if (escort.contains(wave.get(i), true)) wave.removeIndex(i);
        for (int i = 0; i < escort.size; i++)
            heldUntil.put(escort.get(i).id, marchTick + HOLD_TICKS);

        order(new PathMoveCommand(playerId, idsOf(escort),
                                  Fixed.fromFloat(cx), Fixed.fromFloat(cy)));
    }

    /**
     * Чи юніт ЗАРАЗ стріляє — тобто ворог у його власній дальності.
     *
     * <p>Міряється саме дальністю юніта, а не {@link #ENGAGE_RANGE}. Ширший
     * радіус тут виглядав розумно і був пасткою: коли переваги в головах
     * бракує, наказу атаки немає, а марш заблокований «бо поруч ворог» — і
     * загін завмирає за 240 одиниць від точки, яку мав узяти, поки суперник
     * спокійно її тримає. Рівно та поведінка, від якої все це й правиться.
     */
    private boolean inContact(Unit u) {
        float reach = Fixed.toFloat(u.attackRange) + CONTACT_PAD;
        for (int i = 0; i < foes.size; i++) {
            Unit f = foes.get(i);
            if (Math.hypot(u.worldX() - f.worldX(), u.worldY() - f.worldY()) <= reach)
                return true;
        }
        return false;
    }

    /** Запас понад дальність: ціль, що ворушиться на межі, теж рахується за бій. */
    private static final float CONTACT_PAD = 20f;

    /** Радіуси, на яких шукається позиція біля точки. */
    private static final float[] APPROACH_RADII = { 55f, 30f };

    /**
     * Наскільки ліс вартий підйому на ярус.
     *
     * <p>Ліс дає ×1.5 захисту і ×1.8–×4.0 маскування, ярус висоти — до 30%
     * захисту й помітно більший огляд. Дві переваги різнорідні, тож число тут
     * не виводиться з формул: воно означає «ліс приблизно як один ярус, але
     * трохи менше», бо огляд із лісу гірший, а бот однаково має бачити.
     */
    private static final float FOREST_WORTH = 0.8f;

    /**
     * Куди саме ставати всередині точки.
     *
     * <p><b>Кандидат МУСИТЬ лежати в чотирикутнику зони.</b> Раніше перевірки
     * не було, кільце мало радіус 70, і для зони C п'ять із восьми позицій
     * лежали за межею — а брались саме вони, бо високе тут якраз навколо села,
     * а не в ньому. Загін ішов «на точку», ставав поруч і не захоплював нічого.
     * Разом зі знятим порогом {@code NEAR_POINT} у {@link #march} це і є та
     * пара, від якої бот роками стояв біля точок.
     *
     * <p>Рельєф усередині зони все одно вартий вибору: ярус дає до ±30%
     * захисту, ліс — ще ×1.5 і маскування. Легкий рівень цього не робить:
     * читати місцевість і є та навичка, якої новачкові бракує.
     */
    private float[] approachSpot(CapturePoint point) {
        float px = Fixed.toFloat(point.x), py = Fixed.toFloat(point.y);
        if (!level.seeksHighGround) return new float[] { px, py };

        TerrainQuery terrain = sim.getTerrain();
        float bestX = px, bestY = py;
        float bestScore = spotScore(terrain, px, py);

        for (int r = 0; r < APPROACH_RADII.length; r++) {
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4.0;
                float x = px + (float) Math.cos(ang) * APPROACH_RADII[r];
                float y = py + (float) Math.sin(ang) * APPROACH_RADII[r];
                if (!point.contains(Fixed.fromFloat(x), Fixed.fromFloat(y))) continue;
                if (terrain.elevation(x, y) == TerrainType.RIVER) continue;
                float s = spotScore(terrain, x, y);
                if (s > bestScore) { bestScore = s; bestX = x; bestY = y; }
            }
        }
        return new float[] { bestX, bestY };
    }

    /** Чого варта позиція: ярус плюс ліс, якщо рівень уміє ним користуватись. */
    private float spotScore(TerrainQuery terrain, float x, float y) {
        float score = terrain.height(x, y);
        if (level.usesCover && terrain.isForest(x, y)) score += FOREST_WORTH;
        return score;
    }

    /** Наскільки вбік від прямої «ціль → свій кут» дозволено шукати позицію гармати. */
    private static final float ARTILLERY_SEARCH_RADIUS = 90f;

    /**
     * Гармата тримається позаду: {@code STRIKE_RANGE} 220 і жодного захисту в
     * контакті. Базова точка — на відрізку від цілі до власного кута.
     *
     * <h3>Чому цього мало</h3>
     * Гола геометрія ставила гармату куди випало. А гарматі потрібні дві речі,
     * яких геометрія не знає: вона стріляє тільки по тому, що БАЧИТЬ САМА
     * ({@code findNearestArtilleryTarget} перевіряє {@code canSee}), і вона
     * найдорожчий юніт у грі. Позиція в улоговині за гребенем або в лісосмузі
     * означала гармату, яка коштує 150 золота й не стріляє ніколи. Тому навколо
     * базової точки пробується кільце, і кандидат мусить МАТИ ЛІНІЮ ЗОРУ на
     * ціль; серед тих, що мають, береться найвищий — з висоти і видно далі, і
     * захист кращий.
     *
     * <p>Ліс тут, на відміну від піхоти, штраф, а не бонус: гармата, яка
     * сховалась і осліпла, марна.
     */
    private void marchArtillery(CapturePoint target, int marchTick) {
        if (target == null) return;

        group.clear();
        wave.clear();
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (!(u instanceof Artillery) || u.isMoving()) continue;
            // Гармату, яку щойно відводила тривога, СЮДИ НЕ ЧІПАТИ.
            //
            // Інакше два контролери тягнуть один юніт у різні боки: тут її
            // женуть на позицію за ARTILLERY_STANDOFF від точки, а protectGuns
            // бачить ворога ближче за своїх і відводить назад — і так по колу
            // кожні дві секунди. Оком це видно точно як «гармата дійшла до
            // точки й повернулась».
            if (marchTick < gunAlarm.get(u.id, 0)) continue;
            if (released(u)) wave.add(u); else group.add(u);
        }

        // Невипущена гармата чекає на збірному разом із піхотою. Раніше цієї
        // гілки не було зовсім: {@code marchArtillery} — окремий шлях, він не
        // питав зобов'язання взагалі, і гармата виїжджала на STANDOFF (140 від
        // цілі) сама, поки її прикриття ще стояло на збірному за 45% шляху.
        // Тобто найдорожчий юніт у грі йшов попереду армії й без охорони.
        float[] rally = rallyPoint(target);
        if (group.size > 0 && anyFartherThan(group, rally, NEAR_POINT))
            order(new PathMoveCommand(playerId, idsOf(group),
                                      Fixed.fromFloat(rally[0]), Fixed.fromFloat(rally[1])));

        if (wave.size == 0) return;

        float tx = Fixed.toFloat(target.x), ty = Fixed.toFloat(target.y);
        float homeX = playerId == 0 ? 0f : sim.getMapWidth();
        float homeY = playerId == 0 ? 0f : sim.getMapHeight();
        float dx = homeX - tx, dy = homeY - ty;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;

        float baseX = clamp(tx + dx / len * ARTILLERY_STANDOFF, 0f, sim.getMapWidth());
        float baseY = clamp(ty + dy / len * ARTILLERY_STANDOFF, 0f, sim.getMapHeight());

        float[] spot = level.seeksHighGround
                     ? gunPosition(baseX, baseY, tx, ty)
                     : new float[] { baseX, baseY };

        if (!anyFartherThan(wave, spot, NEAR_POINT)) return;

        order(new PathMoveCommand(playerId, idsOf(wave),
                                  Fixed.fromFloat(spot[0]), Fixed.fromFloat(spot[1])));
    }

    /**
     * Чи хоч хтось із групи стоїть далі за {@code limit} від точки.
     *
     * <p>Допуск тут несучий, а не косметичний: гармата цілиться 3 секунди, і
     * будь-який наказ руху це наведення скидає. Позиція, перерахована щодві
     * секунди, зрушує на десятки одиниць сама собою — без допуску гармата
     * переїжджала б вічно й не стріляла ніколи. Пробували прив'язати позицію до
     * центру маси піхоти (щоб гармата не виїжджала поперед строю) — виміряно
     * ЗБИТКОВИМ саме через це: HARD проти NORMAL 6/12 замість 8/12, бо позиція
     * почала їздити за строєм. Не повертати без вирішеної проблеми наведення.
     */
    private boolean anyFartherThan(Array<Unit> units, float[] spot, float limit) {
        for (int i = 0; i < units.size; i++) {
            if (Math.hypot(units.get(i).worldX() - spot[0],
                           units.get(i).worldY() - spot[1]) > limit) return true;
        }
        return false;
    }

    /**
     * Вибрати вогневу позицію навколо {@code (baseX, baseY)} з видимістю на
     * {@code (tx, ty)}.
     *
     * <p>Якщо жоден кандидат не бачить цілі — вертаємо базову точку: стояти
     * абикуди все одно краще, ніж не стояти ніде, а видимість може відкритись,
     * коли ціль вийде з-за гребеня.
     */
    private float[] gunPosition(float baseX, float baseY, float tx, float ty) {
        TerrainQuery terrain = sim.getTerrain();
        float bestX = baseX, bestY = baseY, bestScore = -Float.MAX_VALUE;
        boolean anySighted = false;

        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4.0;
            for (int r = 0; r < 2; r++) {
                float radius = r == 0 ? ARTILLERY_SEARCH_RADIUS : ARTILLERY_SEARCH_RADIUS / 2f;
                float x = clamp(baseX + (float) Math.cos(ang) * radius, 0f, sim.getMapWidth());
                float y = clamp(baseY + (float) Math.sin(ang) * radius, 0f, sim.getMapHeight());
                if (terrain.elevation(x, y) == TerrainType.RIVER) continue;

                boolean sighted = gunSees(terrain, x, y, tx, ty);
                if (anySighted && !sighted) continue;

                float score = terrain.height(x, y);
                if (terrain.isForest(x, y)) score -= FOREST_WORTH;

                if (sighted && !anySighted) { anySighted = true; bestScore = -Float.MAX_VALUE; }
                if (score > bestScore) { bestScore = score; bestX = x; bestY = y; }
            }
        }
        return new float[] { bestX, bestY };
    }

    /** Чи бачить гармата з {@code (x,y)} точку {@code (tx,ty)} — ліс і гребені. */
    private boolean gunSees(TerrainQuery terrain, float x, float y, float tx, float ty) {
        long fx = Fixed.fromFloat(x),  fy = Fixed.fromFloat(y);
        long gx = Fixed.fromFloat(tx), gy = Fixed.fromFloat(ty);
        if (terrain.hasForestOnSegmentF(fx, fy, gx, gy,
                TerrainQuery.LOS_ORIGIN_FOREST_SKIP_FIXED,
                TerrainQuery.LOS_ORIGIN_FOREST_SKIP_FIXED)) return false;
        return !terrain.hasGroundAboveOnSegmentF(fx, fy, gx, gy,
                    terrain.heightF(fx, fy), terrain.heightF(gx, gy));
    }

    // ── Бій ───────────────────────────────────────────────────────────────

    /**
     * Реакція на побаченого ворога.
     *
     * <p>Автоатака в {@code CombatManager} і без бота стріляє по всьому в
     * дальності. Наказ атаки потрібен для іншого — ЗІЙТИСЬ: він шикує групу
     * лицем до цілі й веде її вперед. Тому він і дорогий: кожне повторення
     * скасовує попередні накази й перезапускає підхід.
     *
     * <h3>Чому тут гістерезис, а не просто «бий найкращу ціль»</h3>
     * Ціль обирається за {@link Difficulty#focusesWeakest} — найслабшою. А
     * найслабша міняється щоразу, коли хтось отримав урон, тобто по кілька
     * разів на секунду. Важкий рівень думає раз на 2 тіки; разом це давало до
     * двадцяти наказів атаки за секунду, кожен із яких скидав рух усієї групи.
     * Армія перезапускала наступ швидше, ніж встигала зрушити, і бот, який
     * «думає найчастіше», через це програвав тому, хто думає рідко. Тому ціль
     * ТРИМАЄТЬСЯ, поки вона жива, видима і в межах бою, а не переобирається на
     * кожній думці.
     *
     * <h3>Хто входить у групу</h3>
     * Тільки ті, хто вже дістає до бою ({@link #ENGAGE_RANGE} від цілі), і
     * ніколи гармати. Гармата в списку означала б {@code manualTarget}, який
     * наступного ж {@link #marchArtillery} стирається наказом руху — і гармата
     * весь матч смикалась би між «іду на позицію» і «йду на ворога». Далеке
     * підкріплення теж не чіпаємо: наказ атаки збив би йому марш до збірного
     * пункту й потягнув би в бій поодинці — рівно та вада, від якої існує
     * поріг маси.
     */
    private void fight(int executeTick) {
        if (foes.size == 0) { engagedWith.clear(); engagedSize.clear(); return; }

        Array<CapturePoint> pts = points();
        for (int p = 0; p < pts.size; p++) {
            group.clear();
            for (int i = 0; i < mine.size; i++) {
                Unit u = mine.get(i);
                if (u instanceof Artillery) continue;
                if (assignment.get(u.id, -1) != p) continue;
                if (guarding(u, executeTick)) continue;   // відряджений до гармати
                group.add(u);
            }
            if (group.size == 0) { forget(p); continue; }

            Unit target = heldTarget(p);
            if (target == null) target = pickTarget();

            if (target == null) { forget(p); continue; }

            // Б'ються ті, хто дістає. Решта — підкріплення на марші.
            battle.clear();
            for (int i = 0; i < group.size; i++) {
                Unit u = group.get(i);
                if (Math.hypot(u.worldX() - target.worldX(),
                               u.worldY() - target.worldY()) <= ENGAGE_RANGE) battle.add(u);
            }
            if (battle.size == 0) { forget(p); continue; }

            int nearbyFoes = 0;
            for (int i = 0; i < foes.size; i++) {
                Unit f = foes.get(i);
                if (Math.hypot(f.worldX() - target.worldX(),
                               f.worldY() - target.worldY()) <= ENGAGE_RANGE) nearbyFoes++;
            }
            // Перевага по головах. Точнішої оцінки не треба: рішення бінарне, а
            // зайва точність зробила б поведінку смиканою на кожній втраті.
            float need = nearbyFoes * oddsAgainst(target);
            if (battle.size < need) {
                // ВІДСТУПУ ТУТ НЕМАЄ, І ЦЕ ВИМІРЯНО ДВІЧІ.
                //
                // Спокуса очевидна: загін програє за головами — хай відійде.
                // Пробували 2026-08-06 і на збірний, і коротким кроком назад
                // (220 одиниць): в обох випадках NORMAL програвав EASY 4/12
                // замість 9/12, хоча EASY відступати не вміє взагалі. Причина в
                // правилах гри, а не в реалізації: РОЗРИВУ КОНТАКТУ немає —
                // той, хто пішов, не стріляє й далі отримує урон у спину, тож
                // відступ це подарунок. А втрачена точка — це очки, тобто умова
                // перемоги.
                //
                // Лікується не реакція, а ПРИБУТТЯ: див. поріг першої хвилі в
                // {@link #updateWaves} — саме він не давав загонові прийти в
                // бій, який він не тягне.
                forget(p);
                continue;
            }

            // Зійтись на дистанцію влучного вогню. Робиться ПІСЛЯ перевірки
            // переваги й до наказу атаки: наказ атаки доводить шеренгу до
            // standoff, натиск проходить останні метри.
            if (level.pressesRange) pressRange(p, target, executeTick);

            boolean sameTarget = engagedWith.get(p, -1) == target.id;
            boolean sameRoster = engagedSize.get(p, -1) == battle.size;
            if (sameTarget && sameRoster) continue;
            // Приріст складу — привід переставити шеренгу, але не частіше, ніж
            // раз на період розподілу: інакше кожен новоприбулий коштував би
            // всій групі перезапуску наступу.
            if (sameTarget && executeTick < nextAttackTick.get(p, 0)) continue;

            engagedWith.put(p, target.id);
            engagedSize.put(p, battle.size);
            nextAttackTick.put(p, executeTick + ASSIGN_PERIOD_TICKS);
            order(new AttackCommand(playerId, idsOf(battle), target.id));
        }
    }

    /** З якої відстані ворог біля гармати вважається загрозою. */
    private static final float GUN_ALARM = 140f;

    /** Скільки далі за тривогу шукається перехоплення. */
    private static final float GUARD_REACH = 420f;

    /** Скільки юніт лишається в охороні — і скільки його не чіпають марш і бій. */
    private static final int GUARD_TICKS = 240;   // 6 с

    /** Куди гармата відходить: частка шляху до своїх. */
    private static final float RETREAT_STEP = 160f;
    /** Наскільки «ближчою» рахується кіннота, коли треба гасити зрив точки. */
    private static final float CAVALRY_DEFENCE_BONUS = 600f;

    /** Скільки оборонець лишається приписаним до точки, яку боронить. */
    private static final int DEFEND_LOCK_TICKS = 320;   // 8 с

    /** id юніта → точка, до якої він прив'язаний обороною. */
    private final IntIntMap lockedTo = new IntIntMap();
    /** id юніта → до якого тіку тримається прив'язка. */
    private final IntIntMap lockedUntil = new IntIntMap();

    /** id юніта → до якого тіку він в охороні гармати. */
    private final IntIntMap guardUntil = new IntIntMap();
    /** id гармати → з якого тіку можна знову піднімати тривогу. */
    private final IntIntMap gunAlarm = new IntIntMap();

    /** Чи цей юніт зараз відряджений боронити гармату. */
    private boolean guarding(Unit u, int executeTick) {
        return executeTick < guardUntil.get(u.id, 0);
    }

    /**
     * Тривога за гарматою: відвести її до своїх і вислати перехоплення.
     *
     * <h3>Чому це взагалі потрібно</h3>
     * {@code Artillery.damage == 0} — гармата не може відбитись НІЯК. Швидкість
     * 17 проти 40 у кінноти, тобто втекти вона теж не може. Тому єдина відповідь
     * — бігти ДО СВОЇХ і водночас кинути на нальотчика тих, хто дістане.
     *
     * <p>Виміряно зондом {@code RaidStand} (той самий рівень плюс один прийом:
     * кіннотою на видиму гармату, ціль тримається до смерті): бот без цієї
     * реакції програвав 9 матчів із 12 і втрачав УСІ свої гармати в кожному.
     * 150 золота за штуку, тобто три піхотинці, і жодного пострілу у відповідь.
     *
     * <h3>Чому з відсічкою, а не щодумки</h3>
     * Наказ скидає те, що юніт робив. Тривога, піднята щодві секунди,
     * тримала б і гармату, і охорону в стані вічного перезапуску — та сама
     * пастка, що й скрізь тут. Тому один наказ на {@link #GUARD_TICKS}, а
     * відряджені юніти на цей час випадають із {@link #march} і {@link #fight}:
     * інакше наступний же цикл покликав би їх назад на точку.
     */
    private void protectGuns(int executeTick) {
        for (int i = 0; i < mine.size; i++) {
            Unit gun = mine.get(i);
            if (!(gun instanceof Artillery)) continue;

            Unit raider = null;
            float best = GUN_ALARM;
            for (int j = 0; j < foes.size; j++) {
                Unit f = foes.get(j);
                float d = (float) Math.hypot(f.worldX() - gun.worldX(),
                                             f.worldY() - gun.worldY());
                if (d < best) { best = d; raider = f; }
            }
            // Тривога — це ПРОРИВ до гармати, а не «ворог десь попереду».
            // Ознака: нальотчик ближчий до гармати, ніж будь-хто зі своїх,
            // тобто він уже за спиною лінії. Без цієї умови тривога висіла б
            // увесь бій — ворожа лінія стоїть у межах GUN_ALARM завжди, — і бот
            // без упину задкував гарматою й висмикував по двоє з бою. Виміряно:
            // так він програвав ЛЕГКОМУ рівню, який гармат не має взагалі.
            if (raider != null && best >= nearestFriendDist(gun)) raider = null;
            if (raider == null) { gunAlarm.remove(gun.id, 0); continue; }
            if (executeTick < gunAlarm.get(gun.id, 0)) continue;
            gunAlarm.put(gun.id, executeTick + GUARD_TICKS);

            // 1. Гармата відходить ДО СВОЇХ. Не «геть від ворога»: тікаючи в
            //    порожнечу, вона просто вмирає далі від допомоги.
            float[] home = infantryCentre();
            if (home == null) home = rallyPoint(mainObjective());
            float dx = home[0] - gun.worldX(), dy = home[1] - gun.worldY();
            float len = (float) Math.hypot(dx, dy);
            if (len > 1f) {
                float step = Math.min(RETREAT_STEP, len);
                order(new MoveCommand(playerId, new int[] { gun.id },
                        Fixed.fromFloat(gun.worldX() + dx / len * step),
                        Fixed.fromFloat(gun.worldY() + dy / len * step)));
            }

            // 2. Перехоплення: найближчі до НАЛЬОТЧИКА, а не до гармати —
            //    важливо, хто встигне, а не хто поруч із тим, що бороним.
            pressing.clear();
            int want = 2;
            for (int pass = 0; pass < want; pass++) {
                Unit bestGuard = null;
                float bestD = GUARD_REACH;
                for (int j = 0; j < mine.size; j++) {
                    Unit u = mine.get(j);
                    if (u instanceof Artillery || pressing.contains(u, true)) continue;
                    if (guarding(u, executeTick)) continue;
                    float d = (float) Math.hypot(u.worldX() - raider.worldX(),
                                                 u.worldY() - raider.worldY());
                    if (d < bestD) { bestD = d; bestGuard = u; }
                }
                if (bestGuard == null) break;
                pressing.add(bestGuard);
            }
            if (pressing.size == 0) continue;

            for (int j = 0; j < pressing.size; j++)
                guardUntil.put(pressing.get(j).id, executeTick + GUARD_TICKS);
            order(new AttackCommand(playerId, idsOf(pressing), raider.id));
        }
    }

    /** Відстань від гармати до найближчого свого бійця; {@code MAX_VALUE}, якщо бійців немає. */
    private float nearestFriendDist(Unit gun) {
        float best = Float.MAX_VALUE;
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery) continue;
            float d = (float) Math.hypot(u.worldX() - gun.worldX(),
                                         u.worldY() - gun.worldY());
            if (d < best) best = d;
        }
        return best;
    }

    /** Центр маси живої піхоти (без гармат); {@code null}, якщо піхоти немає. */
    private float[] infantryCentre() {
        float sx = 0f, sy = 0f;
        int n = 0;
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery) continue;
            sx += u.worldX(); sy += u.worldY(); n++;
        }
        return n == 0 ? null : new float[] { sx / n, sy / n };
    }

    /**
     * Дистанція, на яку бот підводить лінію.
     *
     * <p>{@code Infantry} влучає зі 100% до 30 одиниць і лише з 35% на межі 90.
     * Ставиться трохи ближче за 30, бо ціль рухається, а наказ віддається раз
     * на секунду — на самій межі половина залпів уже летіла б із розкидом.
     */
    private static final float PRESS_DIST = 26f;

    /** Допуск, у межах якого лінію не чіпають: наказ руху скидає те, що юніт робив. */
    private static final float PRESS_PAD = 14f;

    /** Не частіше разу на секунду — інакше це знову «наказ, що скасовує наказ». */
    private static final int PRESS_PERIOD_TICKS = 40;

    /** індекс точки → з якого тіку можна знову тиснути. */
    private final IntIntMap nextPressTick = new IntIntMap();

    /**
     * Довести групу до дистанції влучного вогню й тримати її там.
     *
     * <h3>Навіщо</h3>
     * {@code CombatManager.standoff} спиняє стрільця на {@code 0.85 × дальність},
     * тобто на 76.5 для піхоти, і {@code processOrder} узагалі не зближується,
     * якщо ціль уже в межах 90. Шанс влучити там ~50% проти 100% упритул — той
     * самий загін б'є вдвічі слабше. Дуельний стенд, 6 проти 6, єдина різниця
     * дистанція: сторона, що зійшлась, виграла 5 боїв із 6, 18 вцілілих проти 1.
     *
     * <h3>Чому наказ РУХУ, а не атаки</h3>
     * Дистанцію наказ атаки не приймає: вона рахується всередині бою. А наказ
     * руху скасовує наказ атаки — і це тут доречно, бо АВТОатака стріляє й на
     * ходу ({@code CombatManager.update} не спиняє того, хто йде). Тобто
     * група підходить, стріляючи, і лишається під автовогнем упритул. Рівно те,
     * що робить рукою гравець.
     *
     * <h3>Пастка, від якої тут допуск і період</h3>
     * Наказ СКИДАЄ те, що юніт робив. Натиск, повторений щодумки (у HARD це
     * 20 разів на секунду), не дав би зробити й кроку — та сама вада, через яку
     * колись «найшвидший» рівень програвав найповільнішому. Тому не частіше
     * разу на секунду і лише коли група справді стоїть задалеко.
     */
    private void pressRange(int front, Unit target, int executeTick) {
        if (executeTick < nextPressTick.get(front, 0)) return;

        // Тиснуть лише ті, хто вже дістає: підхід від ENGAGE_RANGE — робота
        // наказу атаки, він веде шеренгою, а натиск зіпсував би її строй.
        pressing.clear();
        float cx = 0f, cy = 0f;
        for (int i = 0; i < battle.size; i++) {
            Unit u = battle.get(i);
            float d = (float) Math.hypot(u.worldX() - target.worldX(),
                                         u.worldY() - target.worldY());
            if (d > Fixed.toFloat(u.attackRange) + PRESS_PAD) continue;
            if (d <= PRESS_DIST + PRESS_PAD) continue;   // уже впритул
            pressing.add(u);
            cx += u.worldX(); cy += u.worldY();
        }
        if (pressing.size == 0) return;
        cx /= pressing.size; cy /= pressing.size;

        // Точка на PRESS_DIST від цілі з БОКУ ГРУПИ: інакше половина лінії
        // обходила б ціль, підставляючи спину решті ворогів.
        float dx = cx - target.worldX(), dy = cy - target.worldY();
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;

        nextPressTick.put(front, executeTick + PRESS_PERIOD_TICKS);
        order(new MoveCommand(playerId, idsOf(pressing),
                              Fixed.fromFloat(target.worldX() + dx / len * PRESS_DIST),
                              Fixed.fromFloat(target.worldY() + dy / len * PRESS_DIST)));
    }

    /**
     * У скільки разів більше голів треба, щоб іти в цю атаку.
     *
     * <p>Базове число — з рівня. Але {@code TerrainCombatModifier} дає до ±30%
     * захисту за різницю ярусів, і бот, який цього не питав (а він не питав
     * ЖОДНОГО разу), однаково охоче йшов угору й униз. Тепер атака на вищий
     * ярус коштує дорожче в головах, а на нижчий — дешевше: та сама перевага,
     * якою гравець користується не задумуючись.
     *
     * <p>Множник рахується з {@link #battle}, тобто з тих, хто справді піде,
     * і по ЦЕНТРУ їхньої маси — окремі яруси під кожним юнітом зробили б
     * рішення нестійким на межі двох плям маски.
     */
    private float oddsAgainst(Unit target) {
        float odds = level.attackOdds;
        if (!level.seeksHighGround || battle.size == 0) return odds;

        float cx = 0f, cy = 0f;
        for (int i = 0; i < battle.size; i++) { cx += battle.get(i).worldX(); cy += battle.get(i).worldY(); }
        cx /= battle.size; cy /= battle.size;

        TerrainQuery terrain = sim.getTerrain();
        int mine = (int) terrain.height(cx, cy);
        int theirs = (int) terrain.height(target.worldX(), target.worldY());
        if (theirs > mine) odds *= UPHILL_ODDS;
        else if (theirs < mine) odds *= DOWNHILL_ODDS;
        return odds;
    }

    /** Наскільки дорожче йти на ворога, що стоїть вище. */
    private static final float UPHILL_ODDS   = 1.40f;
    /** …і наскільки дешевше — на того, хто нижче. */
    private static final float DOWNHILL_ODDS = 0.85f;

    private void forget(int point) {
        engagedWith.remove(point, 0);
        engagedSize.remove(point, 0);
    }

    /**
     * Ціль, по якій цей напрямок уже б'ється, — якщо вона ще ціль.
     *
     * @return {@code null}, коли її вбили, втратили з очей або вона вийшла з бою
     */
    private Unit heldTarget(int point) {
        int id = engagedWith.get(point, -1);
        if (id < 0) return null;
        for (int i = 0; i < foes.size; i++) {
            Unit f = foes.get(i);
            if (f.id != id) continue;
            for (int j = 0; j < group.size; j++) {
                Unit u = group.get(j);
                if (Math.hypot(u.worldX() - f.worldX(),
                               u.worldY() - f.worldY()) <= ENGAGE_RANGE) return f;
            }
            return null;    // ціль жива, але бій із нею вже не наш
        }
        return null;
    }

    /**
     * Нова ціль для групи: найслабша (де рівень це вміє) або найближча серед
     * тих, до кого хтось із групи дістає.
     */
    private Unit pickTarget() {
        Unit best = null;
        float bestKey = Float.MAX_VALUE;
        for (int i = 0; i < foes.size; i++) {
            Unit f = foes.get(i);
            float near = Float.MAX_VALUE;
            for (int j = 0; j < group.size; j++) {
                Unit u = group.get(j);
                float d = (float) Math.hypot(u.worldX() - f.worldX(), u.worldY() - f.worldY());
                if (d < near) near = d;
            }
            if (near > ENGAGE_RANGE) continue;
            // Добити пораненого — це на одну рушницю менше проти тебе вже цього
            // залпу, тоді як рівний розподіл вогню лишає всіх живими найдовше.
            //
            // Але здоров'я не мусить бити відстань НАСТІЛЬКИ. Раніше вага була
            // 10000, тобто будь-який поранений на краю бою переважував здорового
            // впритул: шеренга кидалась через усе поле добивати одного, ламала
            // стрій і підставлялась. Виміряно бот-проти-бота — з тією вагою HARD
            // брав 6/8 проти NORMAL, а взагалі без «добивання» 7/8. Тепер повна
            // смуга здоров'я коштує рівно {@link #WEAKEST_WEIGHT} одиниць
            // відстані, тож пораненого добивають, коли він поруч.
            float key = level.focusesWeakest ? f.hpRatio() * WEAKEST_WEIGHT + near : near;
            if (key < bestKey) { bestKey = key; best = f; }
        }
        return best;
    }

    /**
     * Відвести поранених.
     *
     * <p>Юніт нижче {@link #WOUNDED_HP} у бою помре наступним, а живим він
     * коштує 50 золота, які вже витрачені. Відвід на збірний пункт — не
     * милосердя, а економія: у грі немає лікування, зате є прибуток, і кожен
     * збережений юніт це півхвилини доходу.
     */
    private void retreatWounded() {
        group.clear();
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (u instanceof Artillery || u.isMoving()) continue;
            if (u.hpRatio() > WOUNDED_HP) continue;
            // Відводити нема сенсу, якщо поруч і так нікого немає.
            boolean threatened = false;
            for (int j = 0; j < foes.size; j++) {
                Unit f = foes.get(j);
                if (Math.hypot(u.worldX() - f.worldX(), u.worldY() - f.worldY()) < ENGAGE_RANGE) {
                    threatened = true; break;
                }
            }
            if (threatened) group.add(u);
        }
        if (group.size == 0) return;

        float[] rally = rallyPoint(mainObjective());
        order(new PathMoveCommand(playerId, idsOf(group),
                                  Fixed.fromFloat(rally[0]), Fixed.fromFloat(rally[1])));
    }

    // ── Дрібниці ──────────────────────────────────────────────────────────

    protected void order(GameCommand command) { orders.add(command); }

    private Array<Unit> myUnits(Array<Unit> out) {
        out.clear();
        Array<Unit> all = sim.getUnitManager().getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (u.alive && u.team == me) out.add(u);
        }
        return out;
    }

    private Array<Unit> visibleFoes(Array<Unit> out) {
        out.clear();
        Array<Unit> all = sim.getUnitManager().getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);
            if (u.alive && u.team == foe && u.isVisibleTo(me)) out.add(u);
        }
        return out;
    }

    private Array<CapturePoint> points() { return sim.getCapturePoints().getPoints(); }

    private int gold() { return sim.getEconomy().gold(playerId); }

    private static int[] idsOf(Array<Unit> units) {
        int[] ids = new int[units.size];
        for (int i = 0; i < units.size; i++) ids[i] = units.get(i).id;
        return ids;
    }

    private static float dist(Unit u, CapturePoint p) {
        return (float) Math.hypot(u.worldX() - Fixed.toFloat(p.x),
                                  u.worldY() - Fixed.toFloat(p.y));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
