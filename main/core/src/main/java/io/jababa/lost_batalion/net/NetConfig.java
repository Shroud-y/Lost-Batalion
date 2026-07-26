package io.jababa.lost_batalion.net;

import io.jababa.lost_batalion.sim.TickRate;

/**
 * Константи мережевого шару та lockstep-циклу.
 *
 * <p>Параметри, які мусять збігатися між клієнтами (tick rate, input delay,
 * інтервал checksum), передаються ще й у StartMatch — щоб клієнт зі старішою
 * збіркою відвалився одразу з внятною помилкою, а не десинхронізувався через хвилину.
 */
public final class NetConfig {

    private NetConfig() {}

    /**
     * Піднімати при будь-якій зміні складу чи полів повідомлень.
     *
     * <p>2 — у {@code TickChecksum} додано покомпонентну розбивку хеша (етап 7).
     * <p>3 — у {@code TickCommands} додано покоління симуляції (етап 8): без
     * нього накази, що летіли в момент десинхрону, змішувались би з наказами
     * після ресинку. Клієнт зі старим полем розсипався б на розпакуванні, тому
     * <p>4 — додано {@code PathMoveCommand} (подвійний ПКМ = рух із пошуком
     * шляху). Новий клас у реєстрі Kryo зсуває нумерацію, тож старі клієнти
     * розпакували б наступні повідомлення як сміття.
     *
     * <p>Старі збірки мають відвалюватись ще в лоббі.
     */
    public static final int PROTOCOL_VERSION = 4;

    /** TCP-порт хоста (ігровий трафік). */
    public static final int TCP_PORT = 54555;
    /** UDP-порт широкомовного дискаверi лоббі в локальній мережі. */
    public static final int UDP_PORT = 54777;

    // ── Lockstep ──────────────────────────────────────────────────────────
    // Темп симуляції задає TickRate — це властивість гри, а не транспорту.
    // Тут лишаються лише псевдоніми для повідомлень, які їх переносять.

    /** @see TickRate#TICKS_PER_SECOND */
    public static final int TICK_RATE = TickRate.TICKS_PER_SECOND;
    /** @see TickRate#INPUT_DELAY_TICKS */
    public static final int INPUT_DELAY_TICKS = TickRate.INPUT_DELAY_TICKS;

    // ── Checksum / десинхрон ──────────────────────────────────────────────

    /** Дефолтний інтервал звірки хешів стану, у тіках (1 с при 40 Hz). */
    public static final int DEFAULT_CHECKSUM_INTERVAL = 40;

    /** Фактичний інтервал матчу. Задається хостом і розсилається у StartMatch. */
    private static int checksumIntervalTicks = DEFAULT_CHECKSUM_INTERVAL;

    public static int getChecksumIntervalTicks() { return checksumIntervalTicks; }

    public static void setChecksumIntervalTicks(int ticks) {
        checksumIntervalTicks = Math.max(1, ticks);
    }

    // ── Лоббі ─────────────────────────────────────────────────────────────

    public static final int MAX_PLAYERS      = 2;
    public static final int MAX_NICK_LENGTH  = 20;
    public static final int MAX_LOBBY_NAME_LENGTH = 32;

    /** Скільки чекати відповідей на широкомовний запит дискаверi. */
    public static final int DISCOVERY_TIMEOUT_MS = 1200;
    /** Як часто клієнт перескановує мережу. */
    public static final int DISCOVERY_INTERVAL_MS = 2500;
    /** Лоббі, від якого не було відповіді довше за це, зникає зі списку. */
    public static final int LOBBY_STALE_MS = 6000;

    // ── Дисконект ─────────────────────────────────────────────────────────

    /**
     * Скільки хост чекає на команди від гравця, перш ніж показати «гравець гальмує».
     * Це ще не дисконект — просто lockstep-очікування, яке варто підсвітити.
     */
    public static final int LAG_WARNING_MILLIS = 1500;

    /** Скільки тримати слот відключеного гравця під перепідключення. */
    public static final int RECONNECT_GRACE_MILLIS = 30_000;

    // ── Буфери KryoNet ────────────────────────────────────────────────────

    /**
     * Буфер запису має вміщати найбільше повідомлення — а це знімок стану для
     * ресинку, який серіалізує всіх юнітів разом.
     */
    public static final int WRITE_BUFFER_BYTES  = 2 * 1024 * 1024;
    public static final int OBJECT_BUFFER_BYTES = 2 * 1024 * 1024;
}
