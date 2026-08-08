package io.jababa.lost_batalion.steam;

import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.messages.LobbyInfo;
import io.jababa.lost_batalion.net.messages.LobbyStatus;

import java.util.Random;

/**
 * Візитівка лоббі в метаданих Steam і ключ кімнати.
 *
 * <p>У локальній мережі ту саму роль грає {@link LobbyInfo}, серіалізований
 * Kryo в одну UDP-датаграму. У Steam серіалізації немає взагалі: метадані — це
 * плоскі пари рядок→рядок, які сервіс реплікує всім, хто бачить лоббі. Тому
 * тут не «протокол», а домовленість про імена ключів, і рівно вона мусить
 * збігатись у хоста й гостя.
 *
 * <p>Ключі короткі й з префіксом {@code lb_}: на AppID 480 у спільному просторі
 * лежать чужі лоббі, і збіг імені на кшталт {@code name} цілком імовірний.
 */
public final class SteamLobbyKeys {

    private SteamLobbyKeys() {}

    /** Ознака «це лоббі нашої гри». Перший фільтр пошуку. */
    public static final String GAME  = "lb_game";
    public static final String GAME_VALUE = "lost_batalion";

    /** Версія протоколу. Чужу відсіює сам пошук, а не список у грі. */
    public static final String PROTO = "lb_proto";

    /**
     * Ключ кімнати.
     *
     * <p><b>Це НЕ захист від сторонніх.</b> Фільтр пошуку діє лише на нашого
     * клієнта; будь-яка чужа утиліта на AppID 480 робить {@code
     * requestLobbyList} без фільтрів і бачить наше лоббі разом із ключем. Від
     * випадкового гостя рятує лише тип лоббі й {@code setLobbyJoinable(false)}.
     *
     * <p>Чого ключ справді вартий: коли на 480 одночасно тестують кілька пар,
     * кожна бачить лише своє лоббі, а не чотири однакових рядки «Лоббі Андрія».
     */
    public static final String KEY   = "lb_key";

    public static final String NAME     = "lb_name";
    public static final String NICK     = "lb_nick";
    public static final String SCENARIO = "lb_scen";
    public static final String STATUS   = "lb_status";
    public static final String MAX      = "lb_max";

    /**
     * Скільки гравців усередині.
     *
     * <p>Публікується хостом окремим ключем, а не рахується через
     * {@code getNumLobbyMembers}: той чесно відповідає лише про лоббі, у якому
     * ти вже сидиш, а в списку пошуку нам треба число ЧУЖОГО лоббі.
     */
    public static final String COUNT = "lb_count";

    /** Членські дані: готовність гостя ({@code "1"}/{@code "0"}). */
    public static final String READY = "lb_ready";

    /**
     * Членські дані: обране місце у форматі {@code "команда:місце"}.
     *
     * <p>Саме ЧЛЕНСЬКІ, а не лоббі: місце — це вибір самого гравця, і писати
     * його мусить він. Хост при цьому нічого не «дозволяє»: конфлікт двох
     * охочих на одне місце розв'язується при ЧИТАННІ, однаково в усіх (див.
     * {@code SteamLobbySession.publish}). Без цього довелось би вигадувати
     * хостовий арбітраж там, де його структурно немає.
     */
    public static final String SEAT = "lb_seat";

    /** Дані лоббі (пише лише власник): закриті місця, дві маски через кому. */
    public static final String CLOSED = "lb_closed";

    /**
     * Дані лоббі (пише лише власник): боти у форматі
     * {@code "команда:місце:РІВЕНЬ"} через крапку з комою.
     *
     * <p>Номери гравців ботам роздаються при читанні — після всіх людей, у
     * порядку цього рядка. Обидві сторони роблять це однаково, тож звіряти
     * нічого.
     */
    public static final String BOTS = "lb_bots";

    /**
     * Дані лоббі (пише лише власник): SteamID64 вигнаних, через кому.
     *
     * <p>У Steam немає способу викинути когось із лоббі ззовні — учасник
     * виходить лише сам. Тому кік тут — це оголошення: вигнаний бачить себе в
     * списку й виходить власноруч. Проти зловмисно зміненого клієнта це не
     * працює, і не мусить: він однаково не потрапить у {@code lb_members}, які
     * хост фіксує на старті.
     */
    public static final String KICKED = "lb_kick";

    /**
     * Параметри матчу, які хост публікує при старті: версія, seed, сценарій і
     * темп. Плоский рядок через {@code |} — метадані це пари рядок→рядок, і
     * бінарний блоб у base64 дав би лише нечитабельність у діагностиці.
     */
    public static final String START = "lb_start";

    /**
     * Склад матчу, зафіксований на момент старту: SteamID64 через кому, у
     * канонічному порядку (власник першим).
     *
     * <p>Без нього гість, який зайшов між натисканням «Старт» і читанням
     * метаданих, зсунув би нумерацію гравців рівно в однієї зі сторін — а це
     * розбіжність симуляції з першого ж наказу.
     */
    public static final String MEMBERS = "lb_members";

    // ── Ключ кімнати ──────────────────────────────────────────────────────

    /**
     * Абетка ключа: без {@code 0/O}, {@code 1/I/L}, {@code 5/S}, {@code 8/B}.
     * Ключ передають голосом або в месенджері, і кожна пара, яку легко
     * сплутати, — це зайва спроба «чому не знаходить».
     */
    private static final char[] ALPHABET = "ACDEFGHJKMNPQRTUVWXYZ2346789".toCharArray();

    public static final int KEY_LENGTH = 6;

    private static final Random RANDOM = new Random();

    /** Новий ключ кімнати. Регістр верхній — див. DESIGN §3. */
    public static String generateKey() {
        StringBuilder sb = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < KEY_LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Привести введене гравцем до канонічного вигляду.
     *
     * <p>Ключ порівнюється Steam-ом ТОЧНИМ збігом рядка, тож нормалізація
     * мусить бути тут, а не в UI: пробіл із буфера обміну або малі літери
     * інакше дали б «нічого не знайдено» без жодного пояснення.
     */
    public static String normalizeKey(String raw) {
        if (raw == null) return "";
        return raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    // ── Візитівка ─────────────────────────────────────────────────────────

    /** Зібрати {@link LobbyInfo} з метаданих лоббі. */
    public static LobbyInfo readInfo(SteamMatchmakingHub hub, com.codedisaster.steamworks.SteamID lobby) {
        LobbyInfo info = new LobbyInfo();
        info.protocolVersion = parseInt(hub.getData(lobby, PROTO), -1);
        info.lobbyName   = orDefault(hub.getData(lobby, NAME), "Лоббі");
        info.hostNick    = orDefault(hub.getData(lobby, NICK), "?");
        info.scenarioId  = emptyToNull(hub.getData(lobby, SCENARIO));
        info.playerCount = parseInt(hub.getData(lobby, COUNT), 1);
        info.maxPlayers  = parseInt(hub.getData(lobby, MAX), NetConfig.MAX_PLAYERS);
        info.status      = parseStatus(hub.getData(lobby, STATUS));
        return info;
    }

    private static LobbyStatus parseStatus(String raw) {
        if (raw == null || raw.isEmpty()) return LobbyStatus.WAITING;
        try {
            return LobbyStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            // Метадані пише інша збірка — у ній міг з'явитись стан, якого ми не
            // знаємо. Вважати таке лоббі «вільним» гірше, ніж «зайнятим».
            return LobbyStatus.IN_GAME;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String orDefault(String raw, String fallback) {
        return raw == null || raw.isEmpty() ? fallback : raw;
    }

    private static String emptyToNull(String raw) {
        return raw == null || raw.isEmpty() ? null : raw;
    }
}
