package io.jababa.lost_batalion.economy;

import io.jababa.lost_batalion.capture.CaptureManager;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.sim.PlayerRoster;
import io.jababa.lost_batalion.sim.StateChecksum;
import io.jababa.lost_batalion.sim.TickRate;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Золото сторін і прибуток з утримуваних стратегічних точок.
 *
 * <p>Прибуток нараховується не щотіку, а раз на {@link #INCOME_PERIOD_TICKS}:
 * «5 золота за точку кожні 5 секунд» має бути видно як подію, а не як цифру,
 * що безперервно повзе. Лічильник періоду один на матч, а не по одному на
 * гравця, — обидві сторони отримують дохід одночасно, і питання «чий таймер
 * тікав швидше» не виникає.
 *
 * <p>Усе цілочисельне й входить у checksum: від золота залежить, чи виконається
 * замовлення війська, тобто склад армій.
 */
public class Economy {

    /** Скільки золота дає одна утримувана точка за період. */
    public static final int GOLD_PER_POINT = 5;

    /** Довжина періоду нарахування — 5 секунд. */
    public static final int INCOME_PERIOD_TICKS = TickRate.TICKS_PER_SECOND * 5;

    /**
     * Скільки золота на старті, коли армія вже стоїть на полі.
     *
     * <p>Нуль означав би, що перші пів хвилини матчу гравець не може взагалі
     * нічого — а точки ще й треба встигнути зайняти. Сотні вистачає на дві
     * піхоти одразу, тобто на один реальний хід.
     */
    public static final int STARTING_GOLD = 100;

    /**
     * Скільки золота на старті, коли стартової армії НЕМАЄ (мережевий матч).
     *
     * <p>Не кругле число з голови: це ціна тієї самої армії, яку одиночна гра
     * ставить на поле готовою ({@code GameSimulation.spawnArmy} — шість піхоти,
     * три кінноти, дві гармати = 840), плюс звичайна сотня на перший хід. Тобто
     * гравець отримує рівно те саме, тільки склад обирає сам і висаджує туди,
     * куди хоче.
     */
    public static final int STARTING_GOLD_NO_ARMY = 940;

    /**
     * Золото по номеру гравця.
     *
     * <p>Масив на всю стелю гравців, а не на учасників: playerId — це адреса, а
     * не порядковий номер у складі, і при вибулих номерах у ньому бувають
     * дірки. Не-учасники лишаються з нулем і в перевірку анігіляції не
     * потрапляють, бо та питає ростер.
     */
    private final int[] gold = new int[NetConfig.MAX_PLAYERS];

    /** Хто за яку сторону — потрібно, щоб знати, чиї точки дають цьому гравцеві дохід. */
    private final PlayerRoster roster;

    /** Тіків від останнього нарахування. */
    private int sinceIncome;

    public Economy(PlayerRoster roster, int startingGold) {
        this.roster = roster;
        int[] players = roster.players();
        for (int i = 0; i < players.length; i++) gold[players[i]] = startingGold;
    }

    // ── Крок симуляції ────────────────────────────────────────────────────

    public void tick(CaptureManager points) {
        if (++sinceIncome < INCOME_PERIOD_TICKS) return;
        sinceIncome = 0;

        // Обхід — за складом матчу, за зростанням номера: додавання цілих
        // чисел від порядку не залежить, але порядок тут ще й документує, що
        // дохід рахується рівно учасникам.
        int[] players = roster.players();
        for (int i = 0; i < players.length; i++) {
            gold[players[i]] += incomePerPeriod(players[i], points);
        }
    }

    /**
     * Скільки гравець отримає наступного нарахування. Показує HUD.
     *
     * <p>КОЖЕН гравець команди отримує повні {@link #GOLD_PER_POINT} за кожну
     * точку команди — дохід не ділиться. Тому армія сторони росте пропорційно
     * кількості гравців у ній, а рахунок — ні; саме це компенсує піднята межа
     * перемоги у {@code VictoryTracker}.
     */
    public int incomePerPeriod(int playerId, CaptureManager points) {
        if (points == null || !roster.has(playerId)) return 0;
        return GOLD_PER_POINT * points.countOwned(roster.team(playerId));
    }

    public int gold(int playerId) {
        return valid(playerId) ? gold[playerId] : 0;
    }

    /** Скільки тіків лишилось до нарахування — для смужки в HUD. */
    public int ticksToIncome() { return INCOME_PERIOD_TICKS - sinceIncome; }

    /**
     * Списати, якщо вистачає.
     *
     * <p>Перевірка й списання — одна операція навмисно: розділити їх означало б
     * дати двом командам на одному тіку пройти перевірку з тим самим залишком
     * і піти в мінус.
     *
     * @return true, якщо золото списано
     */
    public boolean spend(int playerId, int amount) {
        if (!valid(playerId) || amount < 0 || gold[playerId] < amount) return false;
        gold[playerId] -= amount;
        return true;
    }

    /** Повернути золото (скасоване замовлення). */
    public void refund(int playerId, int amount) {
        if (!valid(playerId) || amount < 0) return;
        gold[playerId] += amount;
    }

    private boolean valid(int playerId) {
        return playerId >= 0 && playerId < gold.length;
    }

    // ── Checksum і знімок ─────────────────────────────────────────────────

    public long stateDigest(long h) {
        for (int i = 0; i < gold.length; i++) h = StateChecksum.fold(h, gold[i]);
        return StateChecksum.fold(h, sinceIncome);
    }

    public void writeSnapshot(DataOutputStream out) throws IOException {
        out.writeInt(gold.length);
        for (int i = 0; i < gold.length; i++) out.writeInt(gold[i]);
        out.writeInt(sinceIncome);
    }

    public void readSnapshot(DataInputStream in) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int value = in.readInt();
            if (i < gold.length) gold[i] = value;
        }
        sinceIncome = in.readInt();
    }
}
