package io.jababa.lost_batalion.economy;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.capture.CaptureManager;
import io.jababa.lost_batalion.sim.StateChecksum;
import io.jababa.lost_batalion.sim.TickRate;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Золото сторін і прибуток з утримуваних сіл.
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
     * Скільки золота на старті.
     *
     * <p>Нуль означав би, що перші пів хвилини матчу гравець не може взагалі
     * нічого — а точки ще й треба встигнути зайняти. Сотні вистачає на дві
     * піхоти одразу, тобто на один реальний хід.
     */
    public static final int STARTING_GOLD = 100;

    /** Золото по номеру гравця. У 1v1 сторін рівно дві. */
    private final int[] gold = { STARTING_GOLD, STARTING_GOLD };

    /** Тіків від останнього нарахування. */
    private int sinceIncome;

    // ── Крок симуляції ────────────────────────────────────────────────────

    public void tick(CaptureManager points) {
        if (++sinceIncome < INCOME_PERIOD_TICKS) return;
        sinceIncome = 0;

        for (int playerId = 0; playerId < gold.length; playerId++) {
            gold[playerId] += incomePerPeriod(playerId, points);
        }
    }

    /** Скільки сторона отримає наступного нарахування. Показує HUD. */
    public int incomePerPeriod(int playerId, CaptureManager points) {
        if (points == null) return 0;
        return GOLD_PER_POINT * points.countOwned(Team.forPlayer(playerId));
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
