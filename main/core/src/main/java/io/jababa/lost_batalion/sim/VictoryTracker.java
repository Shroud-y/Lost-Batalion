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
 * Умова перемоги: очки за утримання сіл плюс поразка за втрату армії.
 *
 * <h3>Очки</h3>
 * Раз на {@link #SCORE_PERIOD_TICKS} кожна сторона отримує стільки очок,
 * скільки тримає точок. Хто перший набрав {@link #TARGET} — виграв.
 *
 * <p>Модель саме накопичувальна, а не «захопи всі три й тримай»: на карті з
 * трьома селами друге перетворює матч на один вирішальний ривок, тоді як очки
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

    /** Скільки очок треба набрати для перемоги. */
    public static final int TARGET = 900;

    /**
     * Період нарахування очок — одна секунда.
     *
     * <p>Дрібніше не має сенсу: очко за точку за секунду вже дає число, що
     * помітно росте, а щотіковий лічильник довелося б ділити на 40 у кожному
     * місці, де його показують.
     */
    public static final int SCORE_PERIOD_TICKS = TickRate.TICKS_PER_SECOND;

    /** Чим скінчився матч. */
    public enum Reason {
        /** Ще триває. */
        NONE,
        /** Набрано {@link #TARGET} очок. */
        POINTS,
        /** У переможеного не лишилось ні армії, ні коштів на нову. */
        ANNIHILATION
    }

    /** Очки по номеру гравця. У 1v1 сторін рівно дві. */
    private final int[] score = { 0, 0 };

    /** Тіків від останнього нарахування. */
    private int sinceScore;

    /** Переможець; {@code null}, поки матч триває АБО якщо вийшла нічия. */
    private Team winner;

    /** Чи матч завершено. Окремо від {@link #winner} саме заради нічиєї. */
    private boolean finished;

    private Reason reason = Reason.NONE;

    /** Тік, на якому все вирішилось. Показувати не обов'язково, але в логах видно. */
    private int decidedTick;

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

        for (int playerId = 0; playerId < score.length; playerId++) {
            score[playerId] += points == null
                             ? 0
                             : points.countOwned(Team.forPlayer(playerId));
        }
        if (checkPoints(tickNumber)) return;

        checkAnnihilation(units, economy, queue, tickNumber);
    }

    /**
     * Перемога за очками.
     *
     * <p>Обидві сторони можуть перетнути межу на одному нарахуванні — тоді
     * виграє та, в кого очок більше, а за повної рівності матч закінчується
     * внічию. Віддати перемогу гравцеві з меншим номером було б простіше, але
     * хост вигравав би нічиї самим фактом того, що він хост.
     */
    private boolean checkPoints(int tickNumber) {
        boolean first  = score[0] >= TARGET;
        boolean second = score[1] >= TARGET;
        if (!first && !second) return false;

        if (first && second) {
            if      (score[0] > score[1]) finish(Team.forPlayer(0), Reason.POINTS, tickNumber);
            else if (score[1] > score[0]) finish(Team.forPlayer(1), Reason.POINTS, tickNumber);
            else                          finish(null,              Reason.POINTS, tickNumber);
        } else {
            finish(Team.forPlayer(first ? 0 : 1), Reason.POINTS, tickNumber);
        }
        return true;
    }

    /**
     * Поразка за втратою армії.
     *
     * <p>Перевіряється в тому самому періоді, що й очки: прохід по всіх юнітах
     * щотіку заради події, яка стається раз на матч, — марна робота, а
     * запізнення до секунди на екрані непомітне.
     */
    private void checkAnnihilation(Array<Unit> units, Economy economy,
                                   SpawnQueue queue, int tickNumber) {
        boolean[] doomed = new boolean[score.length];
        for (int playerId = 0; playerId < doomed.length; playerId++) {
            doomed[playerId] = isDoomed(Team.forPlayer(playerId), playerId, units, economy, queue);
        }

        if (doomed[0] && doomed[1])      finish(null, Reason.ANNIHILATION, tickNumber);
        else if (doomed[0])              finish(Team.forPlayer(1), Reason.ANNIHILATION, tickNumber);
        else if (doomed[1])              finish(Team.forPlayer(0), Reason.ANNIHILATION, tickNumber);
    }

    /** Чи в сторони не лишилось ні армії, ні замовлень, ні грошей на нові. */
    private boolean isDoomed(Team team, int playerId, Array<Unit> units,
                             Economy economy, SpawnQueue queue) {
        if (units != null) {
            for (int i = 0; i < units.size; i++) {
                Unit u = units.get(i);
                if (u.alive && u.team == team) return false;
            }
        }
        if (queue != null) {
            Array<io.jababa.lost_batalion.economy.PendingSpawn> pending = queue.getPending();
            for (int i = 0; i < pending.size; i++) {
                if (pending.get(i).playerId == playerId) return false;
            }
        }
        return economy == null || economy.gold(playerId) < CHEAPEST_UNIT;
    }

    private void finish(Team victor, Reason why, int tickNumber) {
        winner      = victor;
        reason      = why;
        finished    = true;
        decidedTick = tickNumber;
    }

    // ── Читання ───────────────────────────────────────────────────────────

    public int score(int playerId) {
        return playerId >= 0 && playerId < score.length ? score[playerId] : 0;
    }

    /** Чи матч завершено — переможцем або внічию. */
    public boolean isFinished() { return finished; }

    /** Переможець або {@code null}: матч триває, або вийшла нічия. */
    public Team getWinner() { return winner; }

    /** Завершено без переможця. */
    public boolean isDraw() { return finished && winner == null; }

    public Reason getReason()  { return reason; }
    public int getDecidedTick() { return decidedTick; }

    /** Тіків до наступного нарахування — для смужки в HUD. */
    public int ticksToScore() { return SCORE_PERIOD_TICKS - sinceScore; }

    // ── Checksum і знімок ─────────────────────────────────────────────────

    public long stateDigest(long h) {
        for (int i = 0; i < score.length; i++) h = StateChecksum.fold(h, score[i]);
        h = StateChecksum.fold(h, sinceScore);
        h = StateChecksum.fold(h, finished ? 1 : 0);
        // Саме ordinal, а не сам об'єкт: null-переможець (нічия або матч, що
        // триває) мусить давати стале число, а не хеш посилання.
        h = StateChecksum.fold(h, winner == null ? -1 : winner.playerId());
        return StateChecksum.fold(h, reason.ordinal());
    }

    public void writeSnapshot(DataOutputStream out) throws IOException {
        out.writeInt(score.length);
        for (int i = 0; i < score.length; i++) out.writeInt(score[i]);
        out.writeInt(sinceScore);
        out.writeBoolean(finished);
        out.writeInt(winner == null ? -1 : winner.playerId());
        out.writeInt(reason.ordinal());
        out.writeInt(decidedTick);
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
        winner      = victor < 0 ? null : Team.forPlayer(victor);
        reason      = Reason.values()[in.readInt()];
        decidedTick = in.readInt();
    }
}
