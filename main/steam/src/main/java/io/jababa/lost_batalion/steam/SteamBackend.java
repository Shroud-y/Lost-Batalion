package io.jababa.lost_batalion.steam;

import io.jababa.lost_batalion.net.api.DiscoveredLobby;
import io.jababa.lost_batalion.net.api.LobbyDirectory;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.api.MultiplayerServices;
import io.jababa.lost_batalion.net.api.NetBackend;

/**
 * Мережевий бекенд поверх Steamworks: лоббі через матчмейкінг, матч через P2P.
 *
 * <p>Реєструється десктопним лаунчером у {@link MultiplayerServices}. Решта гри
 * бачить лише {@link NetBackend} і не знає, що Steam узагалі існує.
 *
 * <p>Шлях гравця: пошук лоббі ({@code SteamLobbyDirectory}) → кімната
 * ({@code SteamLobbySession}) → матч ({@code SteamMatchTransport}). Локальна
 * мережа проходить рівно ті самі три кроки своїми класами — тому екрани в обох
 * випадках одні й ті самі.
 */
public class SteamBackend implements NetBackend {

    public static final String ID = "steam";

    /**
     * Чи готовий бекенд вести матч від початку до кінця.
     *
     * <p>Знято на етапі 5: є пошук лоббі, сесія і транспорт матчу. Прапорець
     * лишається як вимикач — недоступний бекенд не зникає з меню, а гасне, і
     * {@code MultiplayerServices.select} мовчки відкочується на локальну мережу.
     */
    private static final boolean MATCHMAKING_READY = true;

    /**
     * Аватарки — і заразом ЄДИНИЙ на процес {@code SteamFriends}.
     *
     * <p>Ім'я персони теж питається в нього: колбек {@code SteamFriends}
     * потрібен саме аватаркам, а два екземпляри означали б дві підписки на ті
     * самі події заради одного рядка з іменем.
     */
    private final SteamAvatarSource avatarSource = new SteamAvatarSource();

    private SteamLobbyDirectory directory;

    /**
     * Ключ кімнати останнього створеного лоббі — його показує екран лоббі, щоб
     * гравець продиктував супернику. Живе тут, а не в сесії: сесія вмирає разом
     * з екраном, а показати ключ треба й після повернення в список.
     */
    private String lastRoomKey = "";

    /** Зонд самоперевірки; створюється раз і тільки за системною властивістю. */
    private SteamProbeTask probe;
    private boolean        probeChecked;

    @Override public String id()    { return ID; }
    @Override public String label() { return "STEAM"; }

    @Override
    public boolean isAvailable() {
        // Підняття лінива і стається саме тут — на першому ж запиті з
        // LostBatalion.create(). Див. SteamBoot: раніше Gdx.files ще немає.
        return SteamBoot.ensureBooted() && MATCHMAKING_READY;
    }

    @Override
    public String unavailableReason() {
        if (!SteamBoot.ensureBooted()) {
            String reason = SteamBoot.failure();
            return reason != null ? reason : "Steam недоступний";
        }
        if (!MATCHMAKING_READY) return "Steam-лоббі ще не реалізовано";
        return null;
    }

    /** Для Steam «адреса» — це SteamID64 лоббі, а не ключ кімнати. */
    @Override public String addressHint() { return "109775241359032576"; }

    @Override public boolean usesRoomKey() { return true; }

    /**
     * 8 тіків = 200 мс.
     *
     * <p>Типові для гри 2 тіки — це 50 мс, число для локальної мережі, де обіг
     * пакета вимірюється одиницями мілісекунд. Через Steam пакет іде десятки
     * мілісекунд напряму й сотні через ретранслятор Valve, а lockstep не
     * виконує тік, доки не має наказів ВІД УСІХ. Із затримкою 50 мс кожен тік
     * упирався в очікування: гра йшла ривками, а зверху висіло «гравець
     * гальмує матч».
     *
     * <p>200 мс — компроміс: покриває звичайний європейський пінг разом із
     * ретрансляцією, і при цьому наказ виконується за п'ятину секунди, що для
     * стратегії з ротами, а не з мікроконтролем, непомітно. Число не
     * підбиралось вимірюванням — коли з'явиться статистика реальних матчів,
     * його варто перевірити, а краще зробити адаптивним.
     */
    @Override public int inputDelayTicks() { return 8; }

    @Override
    public void setSearchKey(String key) {
        createDirectory();
        directory.setRoomKey(key);
    }

    @Override
    public String hostedRoomKey() {
        return lastRoomKey;
    }

    @Override
    public String emptyListHint() {
        return "Лоббі не знайдено. Перевір ключ кімнати — він у хоста на екрані лоббі.\n"
             + "Свіже лоббі з'являється в пошуку не миттєво: Steam розсилає його\n"
             + "по своїх серверах кілька секунд.";
    }

    @Override
    public String defaultNick() {
        // Ім'я персони — зручність, а не умова гри: не вийшло, то гравець
        // впише сам. Саме в цей же SteamFriends пізніше ляже
        // onGameLobbyJoinRequested — вхід за запрошенням через оверлей Steam.
        return SteamBoot.isRunning() ? avatarSource.personaName() : "";
    }

    @Override
    public io.jababa.lost_batalion.net.api.AvatarSource avatars() {
        return avatarSource;
    }

    /**
     * Такт колбеків. Дістається бекенду щокадру навіть коли в меню обрана
     * локальна мережа — без нього Steam не побачить ні запрошення від друга,
     * ні власного стану.
     */
    @Override
    public void pump() {
        // ensureBooted() саме тут, а не лише в isAvailable(): при обраній
        // локальній мережі isAvailable() Steam-бекенда не питає НІХТО, і
        // Steamworks не піднявся б узагалі — тобто ні оверлей, ні запрошення
        // від друга не працювали б, поки гравець сам не відкриє мультиплеєр.
        // Виклик ідемпотентний: справжня спроба рівно одна за процес.
        if (!SteamBoot.ensureBooted()) return;

        // Порядок важливий: спершу колбеки, потім розклад запитів. Інакше
        // відповідь на щойно замовлений пошук чекала б зайвий кадр.
        SteamBoot.runCallbacks();

        // Після колбеків, бо частина аватарок приїжджає саме ними, — а решта
        // не приїжджає взагалі, і тоді їх ловить цей опит (див. update()).
        avatarSource.update();

        if (directory != null) directory.tick();

        if (!probeChecked) {
            probeChecked = true;
            probe = SteamProbeTask.createIfRequested();
        }
        if (probe != null) probe.tick();
    }

    // ── Матчмейкінг ───────────────────────────────────────────────────────

    /**
     * Каталог один на бекенд, а не новий на кожен виклик.
     *
     * <p>Екран мультиплеєра просить його в конструкторі й кличе
     * {@code start()}/{@code stop()} у {@code show()}/{@code hide()}, тобто за
     * сеанс гри таких запитів багато. Пошук у Steam асинхронний, і його
     * розклад веде {@link #pump()} — тримати розклад для кожного викинутого
     * екземпляра означало б слати запит на кожен відкритий колись екран.
     */
    @Override
    public LobbyDirectory createDirectory() {
        if (directory == null) directory = new SteamLobbyDirectory();
        return directory;
    }

    @Override
    public LobbySession host(String lobbyName, String nick, String scenarioId, int maxPlayers) {
        lastRoomKey = SteamLobbyKeys.generateKey();
        return new SteamLobbySession(lobbyName, nick, scenarioId, maxPlayers, lastRoomKey);
    }

    @Override
    public LobbySession join(DiscoveredLobby lobby, String nick) {
        return new SteamLobbySession(lobby.address, nick);
    }

    /**
     * Приєднання за введеним рядком. Для Steam це SteamID64 лоббі — той самий,
     * що стоїть у {@code DiscoveredLobby.address}. Ключ кімнати сюди НЕ
     * годиться: він звужує пошук, а не адресує лоббі.
     */
    @Override
    public LobbySession joinByAddress(String address, String nick) {
        return new SteamLobbySession(address == null ? "" : address.trim(), nick);
    }
}
