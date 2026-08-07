package io.jababa.lost_batalion.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.DiscoveredLobby;
import io.jababa.lost_batalion.net.api.LobbyDirectory;
import io.jababa.lost_batalion.net.messages.LobbyInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Список лоббі зі Steam — те саме, що {@code LobbyScanner} для локальної мережі.
 *
 * <p>Різниця, з якої випливає весь код: у мережі лоббі ЗНИКАЮТЬ мовчки, тож
 * сканер тримає всіх, кого чув, і викидає за таймаутом. Steam натомість віддає
 * повний і актуальний список на кожен запит — тому тут список ЗАМІНЮЄТЬСЯ
 * цілком, а {@code lastSeenMillis} потрібен лише для сумісності з
 * {@link DiscoveredLobby}.
 *
 * <p>Потоку теж немає: {@code requestLobbyList} асинхронний, а відповідь
 * приходить у {@link #onLobbyMatchList(int)} з потоку рендеру, зсередини
 * {@code SteamAPI.runCallbacks()}. Повторний запит веде {@link #tick()},
 * якого смикає {@code SteamBackend.pump()}.
 */
public class SteamLobbyDirectory implements LobbyDirectory, SteamMatchmakingCallback {

    private final SteamMatchmakingHub hub;

    private final List<DiscoveredLobby> lobbies = new ArrayList<>();

    private boolean running;
    private boolean scanning;
    private long    lastRequestMillis;

    /**
     * Скільки пошуків уже завершилось. Порожній список сам собою нічого не
     * означає — до першої відповіді він теж порожній, — тож відрізнити «ще не
     * шукали» від «шукали й не знайшли» можна лише лічильником.
     */
    private int scanCount;

    /** Ключ кімнати; порожній — показувати всі лоббі гри. Ставить UI. */
    private String roomKey = "";

    public SteamLobbyDirectory() {
        this.hub = SteamMatchmakingHub.get();
    }

    /**
     * Показувати лише кімнату з цим ключем.
     *
     * <p>Зміна ключа одразу спорожнює список і замовляє новий пошук: інакше на
     * екрані лишались би рядки, знайдені за старим ключем, і гравець бачив би
     * лоббі, до якого щойно перестав мати стосунок.
     */
    public void setRoomKey(String key) {
        String normalized = SteamLobbyKeys.normalizeKey(key);
        if (normalized.equals(roomKey)) return;
        roomKey = normalized;
        lobbies.clear();
        if (running) request();
    }

    public String getRoomKey() { return roomKey; }

    // ── LobbyDirectory ────────────────────────────────────────────────────

    @Override
    public void start() {
        if (running || hub == null) return;
        running = true;
        hub.addListener(this);
        request();
    }

    @Override
    public void stop() {
        if (!running) return;
        running = false;
        scanning = false;
        if (hub != null) hub.removeListener(this);
        lobbies.clear();
    }

    @Override
    public void refresh() {
        if (running) request();
    }

    @Override
    public List<DiscoveredLobby> getLobbies() {
        return Collections.unmodifiableList(lobbies);
    }

    @Override
    public boolean isScanning() { return scanning; }

    /** @see #scanCount */
    public int getScanCount() { return scanCount; }

    /**
     * Черговий запит за розкладом. Кличеться щокадру, працює раз на
     * {@link NetConfig#DISCOVERY_INTERVAL_MS} — той самий темп, що в локальній
     * мережі, щоб два бекенди оновлювали список однаково жваво.
     */
    public void tick() {
        if (!running || scanning) return;
        if (System.currentTimeMillis() - lastRequestMillis >= NetConfig.DISCOVERY_INTERVAL_MS) {
            request();
        }
    }

    // ── Запит ─────────────────────────────────────────────────────────────

    private void request() {
        if (hub == null) return;
        SteamMatchmaking api = hub.api();

        // Фільтри діють на ОДИН наступний запит і скидаються самі.
        api.addRequestLobbyListDistanceFilter(SteamMatchmaking.LobbyDistanceFilter.Worldwide);
        api.addRequestLobbyListStringFilter(
            SteamLobbyKeys.GAME, SteamLobbyKeys.GAME_VALUE, SteamMatchmaking.LobbyComparison.Equal);
        api.addRequestLobbyListStringFilter(
            SteamLobbyKeys.PROTO, String.valueOf(NetConfig.PROTOCOL_VERSION),
            SteamMatchmaking.LobbyComparison.Equal);
        if (!roomKey.isEmpty()) {
            api.addRequestLobbyListStringFilter(
                SteamLobbyKeys.KEY, roomKey, SteamMatchmaking.LobbyComparison.Equal);
        }
        // Стеля відповіді. На AppID 480 без неї Steam радо віддав би сотні
        // чужих лоббі, які все одно відсіє фільтр — але вже після пересилання.
        api.addRequestLobbyListResultCountFilter(50);

        scanning = true;
        lastRequestMillis = System.currentTimeMillis();
        api.requestLobbyList();
    }

    // ── Відповідь ─────────────────────────────────────────────────────────

    @Override
    public void onLobbyMatchList(int lobbiesMatching) {
        scanning = false;
        scanCount++;
        if (!running || hub == null) return;

        List<DiscoveredLobby> fresh = new ArrayList<>(lobbiesMatching);
        long now = System.currentTimeMillis();

        for (int i = 0; i < lobbiesMatching; i++) {
            SteamID id = hub.api().getLobbyByIndex(i);
            if (id == null || !id.isValid()) continue;

            LobbyInfo info = SteamLobbyKeys.readInfo(hub, id);
            // Лоббі без назви гри в метаданих сюди потрапити не мало б — але
            // фільтр Steam порівнює рядки, а не гарантує, що ключ узагалі є.
            if (info.protocolVersion < 0) continue;

            fresh.add(new DiscoveredLobby(info, SteamMatchmakingHub.toAddress(id), now));
        }

        // Той самий стабільний порядок, що в локальній мережі: за назвою, потім
        // за адресою. Steam віддає лоббі в порядку своєї близькості, який
        // міняється між запитами, і рядки стрибали б щодві секунди.
        Collections.sort(fresh, new Comparator<DiscoveredLobby>() {
            @Override public int compare(DiscoveredLobby a, DiscoveredLobby b) {
                int byName = String.valueOf(a.info.lobbyName)
                    .compareToIgnoreCase(String.valueOf(b.info.lobbyName));
                return byName != 0 ? byName : a.address.compareTo(b.address);
            }
        });

        lobbies.clear();
        lobbies.addAll(fresh);
        SteamMatchmakingHub.log("пошук лоббі: знайдено " + fresh.size()
            + (roomKey.isEmpty() ? "" : " за ключем " + roomKey));
    }
}
