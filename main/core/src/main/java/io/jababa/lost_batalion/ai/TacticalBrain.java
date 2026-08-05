package io.jababa.lost_batalion.ai;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntIntMap;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.capture.CaptureManager;
import io.jababa.lost_batalion.capture.CapturePoint;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.net.commands.AttackCommand;
import io.jababa.lost_batalion.net.commands.GameCommand;
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
 * армія рушає на ціль лише зібравшись ({@link Difficulty#massThreshold}).
 * Готовність вимірюється не списком, а КУПНІСТЮ: скільки своїх стоять поруч
 * одне з одним. Це та сама величина, яку бачить гравець, коли каже «в нього там
 * купа».
 *
 * <h3>Три частоти</h3>
 * Наказ СКИДАЄ те, що юніт робив: повторений щочверть секунди наказ руху
 * обнуляє маршрут і лишає роту тупцювати. Тому бій — щоразу (і лише при ЗМІНІ
 * цілі), розподіл — раз на {@link #ASSIGN_PERIOD_TICKS}, покупки — раз на
 * {@link #ECONOMY_PERIOD_TICKS}.
 */
public class TacticalBrain implements BotBrain {

    private static final int ASSIGN_PERIOD_TICKS  = 80;   // 2 с
    private static final int ECONOMY_PERIOD_TICKS = 40;   // 1 с

    /** Бажаний склад армії, частками. */
    private static final float WANT_INFANTRY = 0.60f;
    private static final float WANT_CAVALRY  = 0.25f;

    /** Наскільки близько до точки вважати, що юніт уже при ділі. */
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

    protected final GameSimulation sim;
    protected final Difficulty     level;

    private final int  playerId;
    private final Team me;
    private final Team foe;

    private final List<GameCommand> orders = new ArrayList<>();

    private final Array<Unit> mine  = new Array<>();
    private final Array<Unit> foes  = new Array<>();
    private final Array<Unit> group = new Array<>();

    /** id юніта → індекс точки, за яку він відповідає. */
    private final IntIntMap assignment = new IntIntMap();
    /** індекс точки → id цілі, по якій група вже йде. */
    private final IntIntMap engagedWith = new IntIntMap();

    private int nextAssignTick;
    private int nextEconomyTick;

    /**
     * Чи армія вже рушила.
     *
     * <p>Прапорець із гістерезисом: набравши масу, бот іде вперед і не
     * передумує від першої втрати — інакше він смикався б між «іду» і «збираюсь»
     * щодві секунди й не робив би ні того, ні іншого.
     */
    private boolean committed;

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
            updateCommitment();
            assignObjectives();
            march();
        }
        fight();
        if (level.retreatsWounded) retreatWounded();

        return orders;
    }

    // ── Збір сил ──────────────────────────────────────────────────────────

    /**
     * Чи вже пора йти.
     *
     * <p>Бот іде, коли зібрав купу з {@link Difficulty#massThreshold} бійців, і
     * припиняє, лише коли від неї лишилось менше половини — тоді відходить
     * збиратись наново. Є три випадки, коли він іде НЕЗАЛЕЖНО від маси:
     * <ul>
     *   <li>у нього немає жодної точки — сидіти в тилу означає програти за очками
     *       без бою;</li>
     *   <li>його точку зривають — оборона не чекає, поки збереться загін;</li>
     *   <li>рівень узагалі не вміє збиратись ({@code massThreshold == 1}).</li>
     * </ul>
     */
    private void updateCommitment() {
        if (level.massThreshold <= 1) { committed = true; return; }

        if (sim.getCapturePoints().countOwned(me) == 0 || threatenedPoint() != null) {
            committed = true;
            return;
        }

        if (!committed) {
            committed = biggestCluster() >= level.massThreshold;
            return;
        }
        // Відкат міряється ВИСНАЖЕННЯМ, а не купністю, і це принципово: рота на
        // марші розтягується в колону сама собою, купність падає — і бот, який
        // дивився на неї, розвертався на півдорозі назад збиратись, потім знову
        // вперед, і так усю гру. Виміряно: HARD у такому стані програвав EASY,
        // бо той із порогом 1 просто грав.
        committed = combatCount() >= Math.max(2, level.massThreshold / 2);
    }

    /** Скільки живих бійців (без гармат) — міра сили, не строю. */
    private int combatCount() {
        int n = 0;
        for (int i = 0; i < mine.size; i++) if (!(mine.get(i) instanceof Artillery)) n++;
        return n;
    }

    /**
     * Найбільша купа своїх бійців.
     *
     * <p>Груба, але чесна міра: для кожного бійця рахуємо, скільки своїх у
     * {@link #MASS_RADIUS}, і беремо максимум. Гармата не рахується — вона не
     * тримає лінію, і рота з двох піхотинців плюс гармати не є групою з трьох.
     */
    private int biggestCluster() {
        int best = 0;
        for (int i = 0; i < mine.size; i++) {
            Unit a = mine.get(i);
            if (a instanceof Artillery) continue;
            int near = 1;
            for (int j = 0; j < mine.size; j++) {
                Unit b = mine.get(j);
                if (b == a || b instanceof Artillery) continue;
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
     * Своя точка, яку ЗАРАЗ зривають.
     *
     * <p>{@code holder} — той, хто тягне прогрес; якщо він чужий, точку в бота
     * забирають просто зараз. Реакція на це — єдине, що змушує бота
     * повернутись, і саме її бракувало: він ішов уперед, поки за спиною
     * втрачав усе.
     */
    private CapturePoint threatenedPoint() {
        if (!level.defendsPoints) return null;
        Array<CapturePoint> pts = points();
        for (int i = 0; i < pts.size; i++) {
            CapturePoint p = pts.get(i);
            if (p.owner == me && p.holder == foe && p.progress > 0) return p;
        }
        return null;
    }

    /**
     * Куди тиснути: спершу те, що в нас забирають, потім нічия точка, потім
     * чужа, потім своя. Серед рівних — найближча до центру мас.
     *
     * <p>Нічия ВИЩЕ за чужу навмисно: два боти інакше впирались один в одного,
     * тримали по точці, а третю не брав ніхто — 290:290 весь матч.
     */
    private CapturePoint mainObjective() {
        CapturePoint threatened = threatenedPoint();
        if (threatened != null) return threatened;

        Array<CapturePoint> pts = points();
        if (pts.size == 0) return null;

        float cx = 0f, cy = 0f;
        if (mine.size > 0) {
            for (int i = 0; i < mine.size; i++) { cx += mine.get(i).worldX(); cy += mine.get(i).worldY(); }
            cx /= mine.size; cy /= mine.size;
        }

        CapturePoint best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < pts.size; i++) {
            CapturePoint p = pts.get(i);
            // Крок рангу свідомо більший за будь-яку відстань на карті.
            float rank = p.owner == null ? 0f : (p.owner == foe ? 4000f : 8000f);
            float d = (float) Math.hypot(Fixed.toFloat(p.x) - cx, Fixed.toFloat(p.y) - cy);
            if (rank + d < bestScore) { bestScore = rank + d; best = p; }
        }
        return best;
    }

    /**
     * Друга ціль для окремого загону (лише там, де рівень це вміє).
     *
     * <p>Загін — кіннота: вона швидша й дешевша за піхоту, тобто саме те, чим
     * не шкода забирати порожню точку на іншому кінці карти, поки основні сили
     * тиснуть.
     */
    private CapturePoint secondObjective(CapturePoint main) {
        if (!level.splitsForces) return null;
        Array<CapturePoint> pts = points();
        CapturePoint best = null;
        float bestRank = Float.MAX_VALUE;
        for (int i = 0; i < pts.size; i++) {
            CapturePoint p = pts.get(i);
            if (p == main || p.owner == me) continue;
            float rank = p.owner == null ? 0f : 1f;
            if (rank < bestRank) { bestRank = rank; best = p; }
        }
        return best;
    }

    private void assignObjectives() {
        Array<CapturePoint> pts = points();
        if (pts.size == 0) { assignment.clear(); return; }

        CapturePoint main   = mainObjective();
        CapturePoint second = secondObjective(main);
        int mainIndex   = pts.indexOf(main, true);
        int secondIndex = second == null ? -1 : pts.indexOf(second, true);

        assignment.clear();

        // Гарнізони своїх точок — з тих, хто ВЖЕ найближче, щоб наказ нікого не
        // розвертав з дороги.
        for (int p = 0; p < pts.size; p++) {
            CapturePoint point = pts.get(p);
            if (point.owner != me || p == mainIndex) continue;

            for (int guard = 0; guard < level.garrison; guard++) {
                Unit best = null;
                float bestDist = Float.MAX_VALUE;
                for (int i = 0; i < mine.size; i++) {
                    Unit u = mine.get(i);
                    if (assignment.containsKey(u.id) || u instanceof Artillery) continue;
                    float d = dist(u, point);
                    if (d < bestDist) { bestDist = d; best = u; }
                }
                if (best == null) break;
                assignment.put(best.id, p);
            }
        }

        // Летючий загін на другу ціль — уся вільна кіннота.
        if (secondIndex >= 0) {
            for (int i = 0; i < mine.size; i++) {
                Unit u = mine.get(i);
                if (u instanceof Cavalry && !assignment.containsKey(u.id)) {
                    assignment.put(u.id, secondIndex);
                }
            }
        }

        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (!assignment.containsKey(u.id)) assignment.put(u.id, mainIndex);
        }
    }

    // ── Рух ───────────────────────────────────────────────────────────────

    /**
     * Наказ віддається ОДИН на групу: {@code PathMoveCommand} бере масив id, і
     * саме так група отримує спільний маршрут із особистими смугами — строй
     * складається сам по дорозі. Окремі накази дали б купу в одній точці.
     */
    private void march() {
        Array<CapturePoint> pts = points();
        if (pts.size == 0) return;

        CapturePoint main = mainObjective();
        int mainIndex = pts.indexOf(main, true);
        float[] rally = rallyPoint(main);

        for (int p = 0; p < pts.size; p++) {
            CapturePoint point = pts.get(p);

            // Головні сили без зібраної маси нікуди не йдуть — вони збираються.
            boolean gathering = (p == mainIndex) && !committed;

            group.clear();
            for (int i = 0; i < mine.size; i++) {
                Unit u = mine.get(i);
                if (assignment.get(u.id, -1) != p || u instanceof Artillery) continue;
                // Хто вже йде — не чіпаємо: повторний наказ обнулив би маршрут.
                if (u.isMoving()) continue;

                if (gathering) {
                    // Уже в купі біля збірного — стоїмо й чекаємо решту.
                    if (Math.hypot(u.worldX() - rally[0], u.worldY() - rally[1]) < MASS_RADIUS) continue;
                } else {
                    if (point.contains(u.x, u.y) || dist(u, point) < NEAR_POINT) continue;
                }
                group.add(u);
            }
            if (group.size == 0) continue;

            float[] spot = gathering ? rally : approachSpot(point);
            order(new PathMoveCommand(playerId, idsOf(group),
                                      Fixed.fromFloat(spot[0]), Fixed.fromFloat(spot[1])));
        }

        marchArtillery(main);
    }

    /**
     * Куди саме ставати біля точки.
     *
     * <p>Центр зони — не завжди найкраще місце: рельєф дає до ±30% захисту й
     * міняє огляд у рази. Пробуємо кільце позицій навколо центру й беремо
     * найвищу, яка не в річці. Ліс не відкидаємо — він ховає.
     *
     * <p>Легкий рівень цього не робить: читати рельєф і є та навичка, якої
     * новачкові бракує.
     */
    private float[] approachSpot(CapturePoint point) {
        float px = Fixed.toFloat(point.x), py = Fixed.toFloat(point.y);
        if (!level.seeksHighGround) return new float[] { px, py };

        TerrainQuery terrain = sim.getTerrain();
        float bestX = px, bestY = py;
        float bestH = terrain.height(px, py);

        for (int a = 0; a < 8; a++) {
            double ang = a * Math.PI / 4.0;
            float x = clamp(px + (float) Math.cos(ang) * 70f, 0f, sim.getMapWidth());
            float y = clamp(py + (float) Math.sin(ang) * 70f, 0f, sim.getMapHeight());
            if (terrain.elevation(x, y) == TerrainType.RIVER) continue;
            float h = terrain.height(x, y);
            if (h > bestH) { bestH = h; bestX = x; bestY = y; }
        }
        return new float[] { bestX, bestY };
    }

    /**
     * Гармата тримається позаду: {@code STRIKE_RANGE} 220 і жодного захисту в
     * контакті. Ставимо її на відрізку від цілі до власного кута.
     */
    private void marchArtillery(CapturePoint target) {
        if (target == null) return;

        group.clear();
        for (int i = 0; i < mine.size; i++) {
            Unit u = mine.get(i);
            if (!(u instanceof Artillery) || u.isMoving()) continue;
            group.add(u);
        }
        if (group.size == 0) return;

        float tx = Fixed.toFloat(target.x), ty = Fixed.toFloat(target.y);
        float homeX = playerId == 0 ? 0f : sim.getMapWidth();
        float homeY = playerId == 0 ? 0f : sim.getMapHeight();
        float dx = homeX - tx, dy = homeY - ty;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;

        float sx = clamp(tx + dx / len * ARTILLERY_STANDOFF, 0f, sim.getMapWidth());
        float sy = clamp(ty + dy / len * ARTILLERY_STANDOFF, 0f, sim.getMapHeight());

        boolean anyFar = false;
        for (int i = 0; i < group.size; i++) {
            if (Math.hypot(group.get(i).worldX() - sx, group.get(i).worldY() - sy) > NEAR_POINT) {
                anyFar = true; break;
            }
        }
        if (!anyFar) return;

        order(new PathMoveCommand(playerId, idsOf(group),
                                  Fixed.fromFloat(sx), Fixed.fromFloat(sy)));
    }

    // ── Бій ───────────────────────────────────────────────────────────────

    /**
     * Реакція на побаченого ворога.
     *
     * <p>Автоатака в {@code CombatManager} і без бота стріляє по всьому в
     * дальності. Наказ атаки потрібен для іншого — ЗІЙТИСЬ: він будує лінію
     * обличчям до цілі й веде її вперед. Тому віддається рідко й лише коли ціль
     * змінилась: повторний наказ перезапускав би фазу розгортання, і рота
     * тупцювала б замість наступати.
     */
    private void fight() {
        if (foes.size == 0) { engagedWith.clear(); return; }

        Array<CapturePoint> pts = points();
        for (int p = 0; p < pts.size; p++) {
            group.clear();
            float cx = 0f, cy = 0f;
            for (int i = 0; i < mine.size; i++) {
                Unit u = mine.get(i);
                if (assignment.get(u.id, -1) != p) continue;
                group.add(u);
                cx += u.worldX(); cy += u.worldY();
            }
            if (group.size == 0) { engagedWith.remove(p, 0); continue; }
            cx /= group.size; cy /= group.size;

            Unit target = null;
            float bestKey = Float.MAX_VALUE;
            int nearbyFoes = 0;
            for (int i = 0; i < foes.size; i++) {
                Unit f = foes.get(i);
                float d = (float) Math.hypot(f.worldX() - cx, f.worldY() - cy);
                if (d > ENGAGE_RANGE) continue;
                nearbyFoes++;
                // Найслабший замість найближчого: добити пораненого — це на
                // одну рушницю менше проти тебе вже цього залпу, тоді як рівний
                // розподіл вогню лишає всіх живими найдовше.
                float key = level.focusesWeakest ? f.hpRatio() * 10000f + d : d;
                if (key < bestKey) { bestKey = key; target = f; }
            }
            if (target == null) { engagedWith.remove(p, 0); continue; }

            // Перевага по головах. Точнішої оцінки не треба: рішення бінарне, а
            // зайва точність зробила б поведінку смиканою на кожній втраті.
            if (group.size < nearbyFoes * level.attackOdds) continue;

            if (engagedWith.get(p, -1) == target.id) continue;
            engagedWith.put(p, target.id);
            order(new AttackCommand(playerId, idsOf(group), target.id));
        }
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
