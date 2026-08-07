package io.jababa.lost_batalion.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.kryo.SessionEventQueue;
import io.jababa.lost_batalion.net.messages.LobbyState;
import io.jababa.lost_batalion.net.messages.LobbyStatus;
import io.jababa.lost_batalion.net.messages.PlayerSlot;
import io.jababa.lost_batalion.net.messages.StartMatch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Лоббі у Steam. ОДИН клас на обидві ролі — на відміну від локальної мережі,
 * де їх два.
 *
 * <p>Причина не в економії: у KryoNet хост — єдине джерело істини, бо тільки
 * він знає склад лоббі, і гість цей склад отримує повідомленням. У Steam склад
 * лоббі зберігає й реплікує сам сервіс, тож обидві сторони читають ОДНЕ І ТЕ Ж
 * і будують {@link LobbyState} однаково. Розсилати нічого не треба, а розбіжність
 * між тим, що бачить хост і що гість, стає структурно неможливою.
 *
 * <p>Хостові лишається рівно те, чого Steam за нас не вирішить: метадані лоббі
 * (назва, сценарій, стан) і момент старту.
 *
 * <p><b>Номери гравців.</b> Власник лоббі — завжди 0, решта за зростанням
 * SteamID64. Обидві сторони рахують це самостійно й отримують однаковий
 * результат; звіряти нема чого. На момент старту склад ще й фіксується
 * ключем {@link SteamLobbyKeys#MEMBERS} — щоб гість, який зайшов у ту саму
 * мілісекунду, не зсунув нумерацію в одного з учасників.
 *
 * <p>Потоки: колбеки Steamworks приходять із потоку рендеру, тож черга подій
 * тут не міст між потоками, а лише спосіб дотриматись контракту
 * {@link LobbySession#pump}.
 */
public class SteamLobbySession implements LobbySession, SteamMatchmakingCallback {

    private static final int HOST_PLAYER_ID = 0;

    private final SteamMatchmakingHub hub;
    private final SessionEventQueue   events = new SessionEventQueue();

    private final String  nick;
    private final boolean host;

    /** Лише в хоста: те, що піде в метадані одразу після створення лоббі. */
    private final String lobbyName;
    private final String roomKey;
    private final int    maxPlayers;

    private SteamID     lobby;
    private SteamID     me;
    private LobbyState  published;
    private LobbyStatus status = LobbyStatus.WAITING;
    private String      scenarioId;
    private boolean     alive = true;
    /** Щоб {@code lb_start} не спрацював двічі: метадані оновлюються не раз. */
    private boolean     matchStarted;

    // ── Створення ─────────────────────────────────────────────────────────

    /** Хост: створити лоббі. */
    public SteamLobbySession(String lobbyName, String nick, String scenarioId,
                             int maxPlayers, String roomKey) {
        this.hub        = SteamMatchmakingHub.get();
        this.host       = true;
        this.nick       = nick;
        this.lobbyName  = lobbyName;
        this.scenarioId = scenarioId;
        this.roomKey    = SteamLobbyKeys.normalizeKey(roomKey);
        this.maxPlayers = Math.max(2, Math.min(maxPlayers, NetConfig.MAX_PLAYERS));

        if (hub == null) {
            fail("Steam недоступний");
            return;
        }
        hub.addListener(this);
        readSelf();
        // Public — рішення проєкту: саме воно дає шлях через пошук лоббі.
        // Захистом від сторонніх воно НЕ є, див. SteamLobbyKeys.KEY;
        // від випадкового гостя рятує setLobbyJoinable(false) на повній кімнаті.
        hub.api().createLobby(SteamMatchmaking.LobbyType.Public, this.maxPlayers);
    }

    /** Гість: увійти в знайдене лоббі. */
    public SteamLobbySession(String address, String nick) {
        this.hub        = SteamMatchmakingHub.get();
        this.host       = false;
        this.nick       = nick;
        this.lobbyName  = null;
        this.roomKey    = "";
        this.maxPlayers = NetConfig.MAX_PLAYERS;

        if (hub == null) {
            fail("Steam недоступний");
            return;
        }

        SteamID target = SteamMatchmakingHub.fromAddress(address);
        if (target == null || !target.isValid()) {
            fail("Не схоже на адресу лоббі Steam: " + address);
            return;
        }

        hub.addListener(this);
        readSelf();
        hub.api().joinLobby(target);
    }

    /**
     * Власний SteamID. Береться один раз: він потрібен, щоб упізнати себе в
     * списку учасників, а нік у Steam неунікальний і для цього не годиться.
     */
    private void readSelf() {
        try {
            me = new SteamUser(new SteamUserCallback() {}).getSteamID();
        } catch (Throwable t) {
            fail("Не вдалось дізнатись власний SteamID: " + t);
        }
    }

    // ── LobbySession ──────────────────────────────────────────────────────

    @Override public LobbyState getState()   { return published; }
    @Override public boolean    isHost()     { return host; }
    @Override public boolean    isConnected(){ return alive; }

    @Override
    public int getLocalPlayerId() {
        if (host) return HOST_PLAYER_ID;
        LobbyState state = published;
        if (state == null || me == null) return -1;
        String mine = SteamMatchmakingHub.toAddress(me);
        List<SteamID> members = members();
        for (int i = 0; i < members.size(); i++) {
            if (SteamMatchmakingHub.toAddress(members.get(i)).equals(mine)) {
                return playerIdOf(members, i);
            }
        }
        return -1;
    }

    @Override
    public void setReady(boolean ready) {
        if (!alive || host || lobby == null) return;
        hub.api().setLobbyMemberData(lobby, SteamLobbyKeys.READY, ready ? "1" : "0");
        // Власні member-data не завжди повертаються колбеком одразу, а кнопка
        // мусить відгукнутись у тому ж кадрі, коли її натиснули.
        publish();
    }

    @Override
    public void setScenario(String newScenarioId) {
        if (!alive || !host || lobby == null || status != LobbyStatus.WAITING) return;
        scenarioId = newScenarioId;
        hub.api().setLobbyData(lobby, SteamLobbyKeys.SCENARIO, newScenarioId);
        publish();
    }

    @Override
    public void startMatch() {
        if (!alive || !host || lobby == null || status != LobbyStatus.WAITING) return;

        LobbyState state = published;
        if (state == null || !state.allReady()) return;

        status = LobbyStatus.STARTING;
        SteamMatchmaking api = hub.api();

        // Кімната закривається ДО публікації старту: між «усі готові» і
        // «матч почався» ще є кадри, і гість, що встиг зайти саме в них,
        // отримав би склад, якого немає в StartMatch.
        api.setLobbyJoinable(lobby, false);
        api.setLobbyData(lobby, SteamLobbyKeys.STATUS, status.name());

        List<SteamID> members = members();
        api.setLobbyData(lobby, SteamLobbyKeys.MEMBERS, joinIds(members));

        StartMatch start = buildStart(members);
        // Seed із системного часу — єдине місце, де це доречно: до симуляції він
        // не належить, а все, що з нього розгорнеться, буде однаковим у всіх,
        // бо приїде готовим числом.
        api.setLobbyData(lobby, SteamLobbyKeys.START, encodeStart(start));

        matchStarted = true;
        publish();
        events.post(l -> l.onMatchStarting(start));
    }

    @Override
    public void leave() {
        if (!alive) return;
        alive = false;
        if (hub != null) {
            hub.removeListener(this);
            if (lobby != null) hub.api().leaveLobby(lobby);
        }
        events.clear();
    }

    /**
     * Перейти від лоббі до матчу.
     *
     * <p>Склад беруть ОБИДВІ сторони з {@code lb_members}, а не з поточних
     * учасників лоббі: це той самий рядок, зафіксований хостом у мить старту,
     * тож нумерація гравців гарантовано збігається. Свій номер кожен знаходить
     * у ньому за власним SteamID.
     *
     * <p>Сесія лоббі після цього не закривається: вихід із лоббі закриє
     * транспорт через {@code closer}, коли матч завершиться. Поки матч іде,
     * лоббі тримає нас разом — саме через нього Steam знає, що ми в грі.
     */
    @Override
    public MatchTransport openMatch(StartMatch start) {
        if (!alive || lobby == null) return null;

        List<SteamID> members = membersFromMetadata();
        if (members.isEmpty()) {
            SteamMatchmakingHub.log("openMatch: склад матчу порожній — старт неможливий");
            return null;
        }

        int localId = indexOfSelf(members);
        if (localId < 0) {
            SteamMatchmakingHub.log("openMatch: себе немає у складі матчу");
            return null;
        }

        int[] playerIds = new int[members.size()];
        for (int i = 0; i < playerIds.length; i++) playerIds[i] = i;

        status = LobbyStatus.IN_GAME;
        if (host) hub.api().setLobbyData(lobby, SteamLobbyKeys.STATUS, status.name());

        SteamMatchmakingHub.log("матч почався: гравців " + members.size()
            + ", я #" + localId);
        return new SteamMatchTransport(members, localId, playerIds, this::leave);
    }

    /** Склад матчу з {@code lb_members}; порожній список, якщо ключа ще немає. */
    private List<SteamID> membersFromMetadata() {
        List<SteamID> list = new ArrayList<>();
        String raw = hub.getData(lobby, SteamLobbyKeys.MEMBERS);
        if (raw.isEmpty()) return list;

        for (String part : raw.split(",")) {
            SteamID id = SteamMatchmakingHub.fromAddress(part);
            if (id != null && id.isValid()) list.add(id);
        }
        return list;
    }

    private int indexOfSelf(List<SteamID> members) {
        if (me == null) return -1;
        String mine = SteamMatchmakingHub.toAddress(me);
        for (int i = 0; i < members.size(); i++) {
            if (SteamMatchmakingHub.toAddress(members.get(i)).equals(mine)) return i;
        }
        return -1;
    }

    @Override
    public void pump(LobbySession.Listener listener) {
        events.drain(listener);
    }

    // ── Колбеки Steam ─────────────────────────────────────────────────────

    @Override
    public void onLobbyCreated(SteamResult result, SteamID steamIDLobby) {
        if (!alive || !host || lobby != null) return;

        if (result != SteamResult.OK || steamIDLobby == null || !steamIDLobby.isValid()) {
            fail("Не вдалось створити лоббі у Steam: " + result);
            return;
        }

        lobby = steamIDLobby;
        SteamMatchmaking api = hub.api();
        api.setLobbyData(lobby, SteamLobbyKeys.GAME,     SteamLobbyKeys.GAME_VALUE);
        api.setLobbyData(lobby, SteamLobbyKeys.PROTO,    String.valueOf(NetConfig.PROTOCOL_VERSION));
        api.setLobbyData(lobby, SteamLobbyKeys.KEY,      roomKey);
        api.setLobbyData(lobby, SteamLobbyKeys.NAME,     lobbyName);
        api.setLobbyData(lobby, SteamLobbyKeys.NICK,     nick);
        api.setLobbyData(lobby, SteamLobbyKeys.SCENARIO, scenarioId == null ? "" : scenarioId);
        api.setLobbyData(lobby, SteamLobbyKeys.STATUS,   status.name());
        api.setLobbyData(lobby, SteamLobbyKeys.MAX,      String.valueOf(maxPlayers));
        api.setLobbyData(lobby, SteamLobbyKeys.COUNT,    "1");
        api.setLobbyMemberData(lobby, SteamLobbyKeys.NICK, nick);

        SteamMatchmakingHub.log("лоббі створено: " + SteamMatchmakingHub.toAddress(lobby)
            + ", ключ кімнати " + roomKey);
        publish();
    }

    @Override
    public void onLobbyEnter(SteamID steamIDLobby, int chatPermissions, boolean blocked,
                             SteamMatchmaking.ChatRoomEnterResponse response) {
        if (!alive || host) return;

        if (response != SteamMatchmaking.ChatRoomEnterResponse.Success) {
            fail(enterFailure(response));
            return;
        }

        lobby = steamIDLobby;
        // Нік публікується членськими даними: у Steam ім'я персони видно всім,
        // але гравець міг вписати в грі інше, і в лоббі має стояти саме воно.
        hub.api().setLobbyMemberData(lobby, SteamLobbyKeys.NICK,  nick);
        hub.api().setLobbyMemberData(lobby, SteamLobbyKeys.READY, "0");
        publish();
    }

    @Override
    public void onLobbyDataUpdate(SteamID steamIDLobby, SteamID steamIDMember, boolean success) {
        if (!alive || !sameLobby(steamIDLobby)) return;
        publish();
        checkStart();
    }

    @Override
    public void onLobbyChatUpdate(SteamID steamIDLobby, SteamID steamIDUserChanged,
                                  SteamID steamIDMakingChange,
                                  SteamMatchmaking.ChatMemberStateChange stateChange) {
        if (!alive || !sameLobby(steamIDLobby)) return;

        if (host) {
            // Лічильник у метаданих потрібен саме тут: getNumLobbyMembers
            // чесно відповідає лише про лоббі, у якому ти сидиш, а число
            // мусить бачити той, хто лоббі лише ШУКАЄ.
            List<SteamID> members = members();
            hub.api().setLobbyData(lobby, SteamLobbyKeys.COUNT, String.valueOf(members.size()));
            hub.api().setLobbyJoinable(lobby, members.size() < maxPlayers);
        }

        // Вихід ВЛАСНИКА для гостя означає кінець лоббі: Steam призначить
        // нового власника, але метадані веде наша сесія, а її вже немає.
        if (!host && steamIDUserChanged != null && isOwner(steamIDUserChanged)
            && stateChange != SteamMatchmaking.ChatMemberStateChange.Entered) {
            fail("Хост залишив лоббі");
            return;
        }

        publish();
    }

    @Override
    public void onLobbyKicked(SteamID steamIDLobby, SteamID steamIDAdmin, boolean dueToDisconnect) {
        if (!alive || !sameLobby(steamIDLobby)) return;
        fail(dueToDisconnect ? "Зв'язок зі Steam обірвано" : "Хост закрив лоббі");
    }

    // ── Побудова стану ────────────────────────────────────────────────────

    /**
     * Учасники лоббі в КАНОНІЧНОМУ порядку: власник, далі за зростанням
     * SteamID64. Порядок, у якому їх віддає Steam, ніде не обіцяний, а від
     * нього залежать номери гравців — тобто чиї це юніти в симуляції.
     */
    private List<SteamID> members() {
        List<SteamID> list = new ArrayList<>();
        if (lobby == null) return list;

        int count = hub.api().getNumLobbyMembers(lobby);
        for (int i = 0; i < count; i++) {
            SteamID id = hub.api().getLobbyMemberByIndex(lobby, i);
            if (id != null && id.isValid()) list.add(id);
        }

        final SteamID owner = hub.api().getLobbyOwner(lobby);
        final String  ownerKey = owner == null ? "" : SteamMatchmakingHub.toAddress(owner);
        list.sort(new Comparator<SteamID>() {
            @Override public int compare(SteamID a, SteamID b) {
                String ka = SteamMatchmakingHub.toAddress(a);
                String kb = SteamMatchmakingHub.toAddress(b);
                if (ka.equals(ownerKey)) return kb.equals(ownerKey) ? 0 : -1;
                if (kb.equals(ownerKey)) return 1;
                // Порівняння беззнакове: SteamID64 не вміщається в long зі знаком.
                return Long.compareUnsigned(
                    SteamNativeHandle.getNativeHandle(a), SteamNativeHandle.getNativeHandle(b));
            }
        });
        return list;
    }

    private int playerIdOf(List<SteamID> ordered, int index) { return index; }

    private void publish() {
        if (lobby == null) return;

        LobbyState state = new LobbyState();
        state.lobbyName  = host ? lobbyName : hub.getData(lobby, SteamLobbyKeys.NAME);
        state.scenarioId = host ? scenarioId : emptyToNull(hub.getData(lobby, SteamLobbyKeys.SCENARIO));
        state.maxPlayers = host ? maxPlayers : parseInt(hub.getData(lobby, SteamLobbyKeys.MAX), maxPlayers);
        state.status     = status;

        List<SteamID> members = members();
        for (int i = 0; i < members.size(); i++) {
            SteamID id = members.get(i);
            boolean isOwner = isOwner(id);

            String memberNick = hub.api().getLobbyMemberData(lobby, id, SteamLobbyKeys.NICK);
            if (memberNick == null || memberNick.isEmpty()) memberNick = "гравець";

            PlayerSlot slot = new PlayerSlot(playerIdOf(members, i), memberNick, isOwner);
            // Хост «готовий» за побудовою — його готовність це натискання
            // «Старт». Для решти читаємо членські дані.
            slot.ready = isOwner
                || "1".equals(hub.api().getLobbyMemberData(lobby, id, SteamLobbyKeys.READY));
            slot.connected = true;
            state.slots.add(slot);
        }

        published = state;
        events.post(l -> l.onLobbyState(state));
    }

    /** Гість: побачити опублікований хостом старт і перейти в матч. */
    private void checkStart() {
        if (host || matchStarted || lobby == null) return;

        String raw = hub.getData(lobby, SteamLobbyKeys.START);
        if (raw.isEmpty()) return;

        StartMatch start = decodeStart(raw, hub.getData(lobby, SteamLobbyKeys.MEMBERS));
        if (start == null) {
            fail("Хост надіслав незрозумілі параметри матчу");
            return;
        }

        matchStarted = true;
        status = LobbyStatus.STARTING;
        events.post(l -> l.onMatchStarting(start));
    }

    // ── StartMatch у метаданих ────────────────────────────────────────────
    //
    // Плоский рядок, а не серіалізований Kryo: метадані лоббі — це пари
    // рядок→рядок, і base64 від бінарного блоба тут не дав би нічого, крім
    // нечитабельності в діагностиці. Склад гравців у рядок не пишеться —
    // він відновлюється з lb_members, тобто з тих самих SteamID, які обидві
    // сторони вже вміють упорядковувати однаково.

    private StartMatch buildStart(List<SteamID> members) {
        StartMatch start = new StartMatch();
        start.protocolVersion = NetConfig.PROTOCOL_VERSION;
        start.rngSeed    = System.nanoTime() ^ (System.currentTimeMillis() << 21);
        start.scenarioId = scenarioId;
        start.slots      = buildSlots(members);
        start.tickRate              = NetConfig.TICK_RATE;
        start.inputDelayTicks       = NetConfig.INPUT_DELAY_TICKS;
        start.checksumIntervalTicks = NetConfig.getChecksumIntervalTicks();
        return start;
    }

    private ArrayList<PlayerSlot> buildSlots(List<SteamID> members) {
        ArrayList<PlayerSlot> slots = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            SteamID id = members.get(i);
            String memberNick = hub.api().getLobbyMemberData(lobby, id, SteamLobbyKeys.NICK);
            if (memberNick == null || memberNick.isEmpty()) memberNick = "гравець";
            PlayerSlot slot = new PlayerSlot(playerIdOf(members, i), memberNick, isOwner(id));
            slot.ready     = true;
            slot.connected = true;
            slots.add(slot);
        }
        return slots;
    }

    /* Пакетно-видимі, а не приватні: зонд самоперевірки ганяє їх туди-назад.
     * Кодування StartMatch неможливо перевірити на одному акаунті інакше —
     * для справжнього обміну потрібні два Steam-клієнти. */
    String encodeStart(StartMatch start) {
        return start.protocolVersion
            + "|" + start.rngSeed
            + "|" + (start.scenarioId == null ? "" : start.scenarioId)
            + "|" + start.tickRate
            + "|" + start.inputDelayTicks
            + "|" + start.checksumIntervalTicks;
    }

    StartMatch decodeStart(String raw, String memberIds) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 6) return null;
        try {
            StartMatch start = new StartMatch();
            start.protocolVersion = Integer.parseInt(parts[0]);
            start.rngSeed         = Long.parseLong(parts[1]);
            start.scenarioId      = parts[2].isEmpty() ? null : parts[2];
            start.tickRate              = Integer.parseInt(parts[3]);
            start.inputDelayTicks       = Integer.parseInt(parts[4]);
            start.checksumIntervalTicks = Integer.parseInt(parts[5]);
            start.slots = slotsFromIds(memberIds);
            return start.slots.isEmpty() ? null : start;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Склад із {@code lb_members} — зафіксованого хостом списку SteamID.
     *
     * <p>Саме з нього, а не з поточних учасників лоббі: гість, який зайшов між
     * «Старт» і читанням метаданих, зсунув би нумерацію рівно в однієї зі
     * сторін, і симуляція розійшлась би з першого ж наказу.
     */
    private ArrayList<PlayerSlot> slotsFromIds(String memberIds) {
        ArrayList<PlayerSlot> slots = new ArrayList<>();
        if (memberIds == null || memberIds.isEmpty() || lobby == null) return slots;

        String[] ids = memberIds.split(",");
        for (int i = 0; i < ids.length; i++) {
            SteamID id = SteamMatchmakingHub.fromAddress(ids[i]);
            if (id == null || !id.isValid()) continue;

            String memberNick = hub.api().getLobbyMemberData(lobby, id, SteamLobbyKeys.NICK);
            if (memberNick == null || memberNick.isEmpty()) memberNick = "гравець";

            PlayerSlot slot = new PlayerSlot(i, memberNick, i == HOST_PLAYER_ID);
            slot.ready     = true;
            slot.connected = true;
            slots.add(slot);
        }
        return slots;
    }

    private String joinIds(List<SteamID> members) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(SteamMatchmakingHub.toAddress(members.get(i)));
        }
        return sb.toString();
    }

    // ── Дрібне ────────────────────────────────────────────────────────────

    private boolean sameLobby(SteamID other) {
        return lobby != null && other != null
            && SteamMatchmakingHub.toAddress(lobby).equals(SteamMatchmakingHub.toAddress(other));
    }

    private boolean isOwner(SteamID id) {
        if (lobby == null || id == null) return false;
        SteamID owner = hub.api().getLobbyOwner(lobby);
        return owner != null
            && SteamMatchmakingHub.toAddress(owner).equals(SteamMatchmakingHub.toAddress(id));
    }

    private String enterFailure(SteamMatchmaking.ChatRoomEnterResponse response) {
        switch (response) {
            case Full:       return "У лоббі немає місць";
            case DoesntExist:return "Лоббі вже не існує";
            case NotAllowed: return "Хост закрив вхід";
            case Banned:     return "Хост заблокував вас";
            default:         return "Не вдалось увійти в лоббі: " + response;
        }
    }

    private void fail(String reason) {
        if (!alive) return;
        alive = false;
        if (hub != null) hub.removeListener(this);
        events.post(l -> l.onDisconnected(reason));
    }

    private static String emptyToNull(String raw) {
        return raw == null || raw.isEmpty() ? null : raw;
    }

    private static int parseInt(String raw, int fallback) {
        try {
            return raw == null || raw.isEmpty() ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
