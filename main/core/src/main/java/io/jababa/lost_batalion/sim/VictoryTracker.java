package io.jababa.lost_batalion.sim;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.capture.CaptureManager;
import io.jababa.lost_batalion.economy.Economy;
import io.jababa.lost_batalion.economy.SpawnQueue;
import io.jababa.lost_batalion.units.Unit;
import io.jababa.lost_batalion.units.UnitType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Умова перемоги: очки за утримання стратегічних точок плюс поразка за втрату
 * армії.
 *
 * <h3>Очки</h3>
 * Раз на {@link #SCORE_PERIOD_TICKS} кожна сторона отримує
 * {@link #POINTS_PER_CAPTURE} очок за кожну утримувану точку. Хто перший
 * набрав {@link #target()} — виграв.
 *
 * <p>Модель саме накопичувальна, а не «захопи всі три й тримай»: на карті з
 * трьома точками друга перетворює матч на один вирішальний ривок, тоді як очки
 * дають перевазі накопичуватись, а відставанню — лишати шанс відіграти.
 *
 * <h3>Анігіляція</h3>
 * Сторона програє, коли в неї не лишилось нічого: ні живих юнітів, ні
 * замовлень у черзі, ні золота на найдешевшого юніта. Усі три умови разом
 * навмисно — по одній кожна дає хибну поразку в момент, коли армія вже
 * замовлена, але ще не вийшла з кута.
 *
 * <p>Побічний ефект, який тут доречний: вибуття гравця з матчу вбиває його
 * армію через {@code GameSimulation.removeArmy}, тобто «суперник вийшов»
 * природно стає перемогою, а не окремим випадком.
 *
 * <h3>Чому симуляція не зупиняється</h3>
 * Переможець фіксується, але {@code tick()} продовжує крутитись. Зупиняти крок
 * симуляції по досягненню умови не можна: у lockstep будь-яка розбіжність у
 * тому, ЧИ виконався тік, — це негайний розсинхрон. Тому результат тут лише
 * записується, а показує його і припиняє ввід виключно екран.
 *
 * <p>Усе цілочисельне й входить у checksum: від результату залежить, що бачить
 * гравець, і розійтись у переможці двом клієнтам не можна.
 */
public class VictoryTracker {

    /**
     * Скільки очок треба набрати для перемоги в матчі один на один.
     *
     * <p>Три точки під повним контролем — п'ять хвилин, одна точка — п'ятнадцять.
     */
    public static final int TARGET_BASE = 900;

    /**
     * Наскільки межа росте за кожного ДОДАТКОВОГО гравця в найбільшій команді.
     *
     * <p>Потрібно тому, що дохід і рахунок масштабуються по-різному: кожен
     * гравець команди отримує ПОВНИЙ дохід із кожної точки (див.
     * {@code Economy}), тобто армія сторони росте кратно її складу, а очки за
     * ту саму точку сторона отримує ті самі. Без надбавки матч 5 на 5 тривав би
     * рівно стільки ж, скільки 1 на 1, при вп'ятеро більших арміях — тобто
     * рахунок вирішував би все ще до того, як армії встигли зустрітись.
     *
     * <p>Число першого наближення: 5 на 5 дає 2700, тобто 15 хвилин при повному
     * контролі. Підбирати його треба вимірюванням, як числа рівнів бота.
     */
    public static final int TARGET_PER_EXTRA_PLAYER = 450;

    /** Скільки секунд між нарахуваннями. */
    public static final int SCORE_PERIOD_SECONDS = 5;

    /**
     * Період нарахування очок — п'ять секунд.
     *
     * <p>Не секунда: з кроком +1 щосекунди рахунок читався як секундомір, а не
     * як рахунок матчу. Рідший, але крупніший крок дає ту саму швидкість
     * набору й ту саму тривалість матчу, зате видно подію — точка принесла
     * очки, — а не рівномірне цокання.
     */
    public static final int SCORE_PERIOD_TICKS = TickRate.TICKS_PER_SECOND * SCORE_PERIOD_SECONDS;

    /**
     * Скільки очок дає одна утримувана точка за нарахування.
     *
     * <p>Рівно період у секундах: сумарна швидкість лишається «очко за точку
     * за секунду», тож {@link #target()} не треба переставляти разом із періодом.
     */
    public static final int POINTS_PER_CAPTURE = SCORE_PERIOD_SECONDS;

    /** Чим скінчився матч. */
    public enum Reason {
        /** Ще триває. */
        NONE,
        /** Набрано {@link #target()} очок. */
        POINTS,
        /** У переможеного не лишилось ні армії, ні коштів на нову. */
        ANNIHILATION
    }

    /** Склад матчу: за ним відомо, чиї юніти й золото належать якій стороні. */
    private final PlayerRoster roster;

    /** Межа очок ЦЬОГО матчу. Похідна від розміру команд, тому не константа. */
    private final int target;

    /** Очки по індексу СТОРОНИ (0 — сині, 1 — червоні), а не по номеру гравця. */
    private final int[] score = { 0, 0 };

    /** Тіків від останнього нарахування. */
    private int sinceScore;

    /** Переможець; {@code null}, поки матч триває АБО якщо вийшла нічия. */
    private Team winner;

    /** Чи матч завершено. Окремо від {@link #winner} саме заради нічиєї. */
    private boolean finished;

    private Reason reason = Reason.NONE;

    /** Тік, на якому все вирішилось. Дає й тривалість матчу для екрана результату. */
    private int decidedTick;

    /**
     * Підсумок матчу: втрачено юнітів і утримувано точок на мить фіксації.
     *
     * <h4>Чому знімається один раз, а не накопичується</h4>
     * Лічильник, який тікав би на кожній смерті, довелось би чіпляти до
     * єдиного місця загибелі юніта — а таких місць три ({@code takeDamage},
     * {@code takeDamageWithTerrain}, {@code GameSimulation.removeArmy}), і
     * кожне нове стало б четвертим, про яке легко забути. Натомість тут
     * рахується скан по списку в мить {@link #finish}: {@code allUnits} під час
     * матчу НЕ чиститься (мертві лишаються з {@code alive == false}), а знімок
     * пише всіх юнітів без фільтра по {@code alive}, тож і ресинк історію
     * втрат не з'їдає.
     *
     * <h4>Чому точки треба саме зберегти</h4>
     * Симуляція після перемоги не спиняється (див. вище), і точки далі
     * переходять з рук у руки. Живий {@code countOwned} на екрані результату
     * показував би, як підсумок матчу повзе вже після його кінця.
     */
    private final int[] lost       = { 0, 0 };
    private final int[] ownedAtEnd = { 0, 0 };

    public VictoryTracker(PlayerRoster roster) {
        this.roster = roster;
        this.target = TARGET_BASE + TARGET_PER_EXTRA_PLAYER * (roster.maxTeamSize() - 1);
    }

    /** Межа очок ЦЬОГО матчу — її називає екран результату. */
    public int target() { return target; }

    /**
     * Найдешевший юніт у каталозі.
     *
     * <p>Рахується з {@link UnitType}, а не пишеться числом: константа 50
     * розійшлася б із каталогом тихо, і сторона з 60 золота вважалась би
     * безнадійною через рік після того, як з'явився юніт за 55.
     */
    private static final int CHEAPEST_UNIT = cheapestCost();

    private static int cheapestCost() {
        int min = Integer.MAX_VALUE;
        for (UnitType t : UnitType.values()) if (t.cost < min) min = t.cost;
        return min;
    }

    // ── Крок симуляції ────────────────────────────────────────────────────

    /**
     * Викликається ОСТАННІМ у тіку — після руху, бою, точок і економіки.
     *
     * <p>Порядок тут не косметичний: очки нараховуються за власників точок
     * цього тіку, а анігіляція дивиться на золото після нарахування прибутку.
     * Порахувати раніше означало б відставати на тік від того, що бачить
     * гравець.
     */
    public void tick(CaptureManager points, Array<Unit> units,
                     Economy economy, SpawnQueue queue, int tickNumber) {
        // Результат незмінний: перерахунок після завершення міг би переписати
        // переможця на наступному ж тіку, коли переможений добере точку.
        if (finished) return;

        // Обидві перевірки — раз на період, а не щотіку: анігіляція проходить по
        // всіх юнітах, і робити цей прохід 40 разів на секунду заради події, яка
        // стається раз на матч, немає сенсу.
        if (++sinceScore < SCORE_PERIOD_TICKS) return;
        sinceScore = 0;

        for (int side = 0; side < score.length; side++) {
            score[side] += points == null
                         ? 0
                         : points.countOwned(Team.byIndex(side)) * POINTS_PER_CAPTURE;
        }
        if (checkPoints(tickNumber, units, points)) return;

        checkAnnihilation(units, economy, queue, tickNumber, points);
    }

    /**
     * Перемога за очками.
     *
     * <p>Обидві сторони можуть перетнути межу на одному нарахуванні — тоді
     * виграє та, в кого очок більше, а за повної рівності матч закінчується
     * внічию. Віддати перемогу гравцеві з меншим номером було б простіше, але
     * хост вигравав би нічиї самим фактом того, що він хост.
     */
    private boolean checkPoints(int tickNumber, Array<Unit> units, CaptureManager points) {
        boolean first  = score[0] >= target;
        boolean second = score[1] >= target;
        if (!first && !second) return false;

        if (first && second) {
            if      (score[0] > score[1]) finish(Team.byIndex(0), Reason.POINTS, tickNumber, units, points);
            else if (score[1] > score[0]) finish(Team.byIndex(1), Reason.POINTS, tickNumber, units, points);
            else                          finish(null,            Reason.POINTS, tickNumber, units, points);
        } else {
            finish(Team.byIndex(first ? 0 : 1), Reason.POINTS, tickNumber, units, points);
        }
        return true;
    }

    /**
     * Поразка за втратою армії.
     *
     * <p>Перевіряється в тому самому періоді, що й очки: прохід по всіх юнітах
     * щотіку заради події, яка стається раз на матч, — марна робота. Ціна —
     * поразку видно із запізненням до {@link #SCORE_PERIOD_SECONDS} секунд;
     * окремий лічильник на секунду того не вартий, бо це ще одне поле в
     * checksum і в знімку.
     */
    private void checkAnnihilation(Array<Unit> units, Economy economy,
                                   SpawnQueue queue, int tickNumber, CaptureManager points) {
        boolean[] doomed = new boolean[score.length];
        for (int side = 0; side < doomed.length; side++) {
            doomed[side] = isDoomed(Team.byIndex(side), units, economy, queue);
        }

        if (doomed[0] && doomed[1])      finish(null, Reason.ANNIHILATION, tickNumber, units, points);
        else if (doomed[0])              finish(Team.byIndex(1), Reason.ANNIHILATION, tickNumber, units, points);
        else if (doomed[1])              finish(Team.byIndex(0), Reason.ANNIHILATION, tickNumber, units, points);
    }

    /**
     * Чи в сторони не лишилось ні армії, ні замовлень, ні грошей на нові.
     *
     * <p>Рахується по СТОРОНІ цілком, а не по гравцеві: розорений гравець у
     * команді, яка ще воює, програшем не є — його союзники тримають поле. Тому
     * досить, щоб хоч у когось із команди лишився юніт, замовлення або гроші на
     * найдешевше.
     */
    private boolean isDoomed(Team team, Array<Unit> units,
                             Economy economy, SpawnQueue queue) {
        if (units != null) {
            for (int i = 0; i < units.size; i++) {
                Unit u = units.get(i);
                if (u.alive && u.team == team) return false;
            }
        }
        int[] side = roster.playersOf(team);
        if (queue != null) {
            Array<io.jababa.lost_batalion.economy.PendingSpawn> pending = queue.getPending();
            for (int i = 0; i < pending.size; i++) {
                for (int p = 0; p < side.length; p++) {
                    if (pending.get(i).playerId == side[p]) return false;
                }
            }
        }
        if (economy == null) return true;
        for (int p = 0; p < side.length; p++) {
            if (economy.gold(side[p]) >= CHEAPEST_UNIT) return false;
        }
        return true;
    }

    private void finish(Team victor, Reason why, int tickNumber,
                        Array<Unit> units, CaptureManager points) {
        winner      = victor;
        reason      = why;
        finished    = true;
        decidedTick = tickNumber;

        for (int side = 0; side < lost.length; side++) {
            Team team = Team.byIndex(side);
            int fallen = 0;
            if (units != null) {
                for (int i = 0; i < units.size; i++) {
                    Unit u = units.get(i);
                    // Поглинуті батареєю — не втрата: гармата не загинула, а
                    // стала частиною сусідньої (див. Unit.absorbed).
                    if (u != null && !u.alive && !u.absorbed() && u.team == team) fallen++;
                }
            }
            lost[side]       = fallen;
            ownedAtEnd[side] = points == null ? 0 : points.countOwned(team);
        }
    }

    // ── Читання ───────────────────────────────────────────────────────────

    /** Очки СТОРОНИ. Аргумент — індекс сторони (0/1), а не номер гравця. */
    public int score(int side) {
        return side >= 0 && side < score.length ? score[side] : 0;
    }

    public int score(Team team) { return team == null ? 0 : score(team.index()); }

    /** Чи матч завершено — переможцем або внічию. */
    public boolean isFinished() { return finished; }

    /** Переможець або {@code null}: матч триває, або вийшла нічия. */
    public Team getWinner() { return winner; }

    /** Завершено без переможця. */
    public boolean isDraw() { return finished && winner == null; }

    public Reason getReason()  { return reason; }
    public int getDecidedTick() { return decidedTick; }

    /** Скільки юнітів сторона втратила за матч. Осмислене лише після завершення. */
    public int unitsLost(int side) {
        return side >= 0 && side < lost.length ? lost[side] : 0;
    }

    /**
     * Скільки юнітів сторона знищила.
     *
     * <p>Окремого поля немає навмисно: у матчі рівно дві сторони, тож знищене
     * однією — це втрачене другою. Другий лічильник міг би розійтися з першим
     * лише через помилку, і тоді екран результату показував би два числа, які
     * суперечать одне одному.
     */
    public int unitsKilled(int side) {
        return unitsLost(side == 0 ? 1 : 0);
    }

    /** Скільки точок сторона утримувала в мить завершення матчу. */
    public int pointsHeldAtEnd(int side) {
        return side >= 0 && side < ownedAtEnd.length ? ownedAtEnd[side] : 0;
    }

    /** Тривалість матчу в секундах — по тіку, а не по годиннику: тіки в усіх однакові. */
    public int durationSeconds() { return decidedTick / TickRate.TICKS_PER_SECOND; }

    /** Тіків до наступного нарахування — для смужки в HUD. */
    public int ticksToScore() { return SCORE_PERIOD_TICKS - sinceScore; }

    // ── Checksum і знімок ─────────────────────────────────────────────────

    public long stateDigest(long h) {
        for (int i = 0; i < score.length; i++) h = StateChecksum.fold(h, score[i]);
        h = StateChecksum.fold(h, sinceScore);
        h = StateChecksum.fold(h, finished ? 1 : 0);
        // Саме ordinal, а не сам об'єкт: null-переможець (нічия або матч, що
        // триває) мусить давати стале число, а не хеш посилання.
        h = StateChecksum.fold(h, winner == null ? -1 : winner.index());
        h = StateChecksum.fold(h, reason.ordinal());
        // Підсумок теж у checksum: його бачить гравець, і розійтись у числі
        // втрат двом клієнтам не можна.
        for (int i = 0; i < lost.length; i++)       h = StateChecksum.fold(h, lost[i]);
        for (int i = 0; i < ownedAtEnd.length; i++) h = StateChecksum.fold(h, ownedAtEnd[i]);
        return h;
    }

    public void writeSnapshot(DataOutputStream out) throws IOException {
        out.writeInt(score.length);
        for (int i = 0; i < score.length; i++) out.writeInt(score[i]);
        out.writeInt(sinceScore);
        out.writeBoolean(finished);
        out.writeInt(winner == null ? -1 : winner.index());
        out.writeInt(reason.ordinal());
        out.writeInt(decidedTick);
        for (int i = 0; i < lost.length; i++)       out.writeInt(lost[i]);
        for (int i = 0; i < ownedAtEnd.length; i++) out.writeInt(ownedAtEnd[i]);
    }

    public void readSnapshot(DataInputStream in) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int value = in.readInt();
            if (i < score.length) score[i] = value;
        }
        sinceScore  = in.readInt();
        finished    = in.readBoolean();
        int victor  = in.readInt();
        winner      = victor < 0 ? null : Team.byIndex(victor);
        reason      = Reason.values()[in.readInt()];
        decidedTick = in.readInt();
        for (int i = 0; i < lost.length; i++)       lost[i]       = in.readInt();
        for (int i = 0; i < ownedAtEnd.length; i++) ownedAtEnd[i] = in.readInt();
    }
}
