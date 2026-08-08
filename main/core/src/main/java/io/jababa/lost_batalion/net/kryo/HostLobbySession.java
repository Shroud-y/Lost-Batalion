package io.jababa.lost_batalion.net.kryo;

import io.jababa.lost_batalion.ai.Difficulty;
import io.jababa.lost_batalion.net.NetLog;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.NetworkProtocol;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.discovery.LobbyBeacon;
import io.jababa.lost_batalion.net.messages.AddBot;
import io.jababa.lost_batalion.net.messages.ChatMessage;
import io.jababa.lost_batalion.net.messages.JoinRequest;
import io.jababa.lost_batalion.net.messages.KickPlayer;
import io.jababa.lost_batalion.net.messages.RemoveBot;
import io.jababa.lost_batalion.net.messages.SetBotDifficulty;
import io.jababa.lost_batalion.net.messages.SetSlotClosed;
import io.jababa.lost_batalion.net.messages.SetTeam;
import io.jababa.lost_batalion.net.messages.JoinResponse;
import io.jababa.lost_batalion.net.messages.LeaveLobby;
import io.jababa.lost_batalion.net.messages.LobbyInfo;
import io.jababa.lost_batalion.net.messages.LobbyState;
import io.jababa.lost_batalion.net.messages.LobbyStatus;
import io.jababa.lost_batalion.net.messages.PlayerSlot;
import io.jababa.lost_batalion.net.messages.SetReady;
import io.jababa.lost_batalion.net.messages.StartMatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Лоббі з боку хоста: приймає підключення, веде склад гравців, стартує матч.
 *
 * <p>Хост — джерело істини. Клієнти нічого не виводять самі: вони показують
 * той {@link LobbyState}, який прислав хост. Навіть власний список хост бере
 * з опублікованої копії, а не з робочих полів — інакше хост і гість малювали б
 * лоббі за різними даними.
 *
 * <p>Потоки: робочі поля міняються і з потоку KryoNet (приєднання, готовність,
 * розриви), і з потоку рендеру ({@link #setScenario}, {@link #startMatch}).
 * Тому всі зміни йдуть під {@link #lock}, а назовні віддається незмінна копія.
 */
public class HostLobbySession implements LobbySession {

    private static final int HOST_PLAYER_ID = 0;

    private final Object lock = new Object();
    private final SessionEventQueue events = new SessionEventQueue();

    // Робочий стан — тільки під lock
    private final List<PlayerSlot> slots = new ArrayList<>();
    private final Map<Integer, Integer> connectionToPlayer = new LinkedHashMap<>();
    private final String lobbyName;
    private final int    maxPlayers;
    private String scenarioId;
    private LobbyStatus status = LobbyStatus.WAITING;
    /** Закриті хостом місця, по біту на місце. Див. {@link LobbyState#closedMask}. */
    private final int[] closedMask = new int[NetConfig.TEAM_COUNT];

    /** Остання опублікована копія — те, що читає UI. */
    private volatile LobbyState published;

    private Server      server;
    private LobbyBeacon beacon;
    private volatile boolean alive;

    public HostLobbySession(String lobbyName, String hostNick, String scenarioId, int maxPlayers) {
        this.lobbyName  = lobbyName;
        this.scenarioId = scenarioId;
        this.maxPlayers = Math.max(2, Math.min(maxPlayers, NetConfig.MAX_PLAYERS));

        PlayerSlot hostSlot = new PlayerSlot(HOST_PLAYER_ID, hostNick, true);
        hostSlot.team       = 0;
        hostSlot.seat       = 0;
        hostSlot.colorIndex = HOST_PLAYER_ID;
        slots.add(hostSlot);

        if (startServer()) {
            startBeacon();
            alive = true;
        }
        publish();
    }

    private boolean startServer() {
        try {
            server = new Server(NetConfig.WRITE_BUFFER_BYTES, NetConfig.OBJECT_BUFFER_BYTES);
            NetworkProtocol.register(server);
            server.addListener(new HostListener());
            server.bind(NetConfig.TCP_PORT);
            server.start();
            return true;
        } catch (Exception e) {
            NetLog.error("Не вдалось підняти хост на порту " + NetConfig.TCP_PORT + ": " + e);
            events.post(l -> l.onDisconnected(
                "Не вдалось відкрити порт " + NetConfig.TCP_PORT
                    + ". Можливо, гра вже запущена в іншому вікні."));
            server = null;
            return false;
        }
    }

    private void startBeacon() {
        beacon = new LobbyBeacon(buildInfo());
        beacon.start();
    }

    // ── LobbySession ──────────────────────────────────────────────────────

    @Override public LobbyState getState()   { return published; }
    @Override public int  getLocalPlayerId() { return HOST_PLAYER_ID; }
    @Override public boolean isHost()        { return true; }
    @Override public boolean isConnected()   { return alive; }

    /** Хост завжди готовий — його готовність виражається натисканням «Старт». */
    @Override public void setReady(boolean ready) { }

    @Override
    public void setScenario(String newScenarioId) {
        synchronized (lock) {
            if (status != LobbyStatus.WAITING) return;
            scenarioId = newScenarioId;
        }
        publish();
    }

    // ── Склад лоббі ───────────────────────────────────────────────────────
    //
    // Усі зміни складу — і свої, і чужі — проходять ЧЕРЕЗ ЦІ методи. Гість
    // надсилає повідомлення, хост викликає той самий метод напряму; окремої
    // «хостової» гілки з іншими правилами немає навмисно: саме так у першій
    // версії лоббі й розходились те, що бачить хост, і те, що насправді в
    // кімнаті.

    @Override
    public void setTeam(int team, int seat) {
        applyTeam(HOST_PLAYER_ID, team, seat);
    }

    /**
     * Посадити гравця на місце або вивести в список очікування.
     *
     * <p>Зайняте чи закрите місце — не помилка, а звичайна гонка: двоє могли
     * клацнути по одному рядку в тому самому кадрі. Прохання просто не
     * виконується, і гравець бачить це наступним станом.
     */
    private void applyTeam(int playerId, int team, int seat) {
        boolean changed = false;
        synchronized (lock) {
            if (status != LobbyStatus.WAITING) return;
            PlayerSlot slot = find(playerId);
            if (slot == null || slot.bot) return;

            if (team == PlayerSlot.TEAM_NONE) {
                changed = slot.seated();
                slot.team = PlayerSlot.TEAM_NONE;
                slot.seat = -1;
                // Хто вийшов зі складу, той більше не «готовий»: інакше він
                // тримав би готовність, повернувшись у команду перед стартом.
                slot.ready = slot.host;
            } else if (seatFree(team, seat)) {
                slot.team = team;
                slot.seat = seat;
                changed = true;
            }
        }
        if (changed) publish();
    }

    @Override
    public void setSlotClosed(int team, int seat, boolean closed) {
        boolean changed = false;
        synchronized (lock) {
            if (status != LobbyStatus.WAITING) return;
            if (!validSeat(team, seat)) return;
            // Закрити зайняте місце не можна: це був би кік чужими руками, без
            // повідомлення тому, кого прибрали. Спершу кік, потім замок.
            if (closed && occupant(team, seat) != null) return;

            int before = closedMask[team];
            if (closed) closedMask[team] |=  (1 << seat);
            else        closedMask[team] &= ~(1 << seat);
            changed = closedMask[team] != before;
        }
        if (changed) publish();
    }

    @Override
    public void addBot(int team, int seat, String difficulty) {
        boolean changed = false;
        synchronized (lock) {
            if (status != LobbyStatus.WAITING) return;
            if (!validSeat(team, seat) || !seatFree(team, seat)) return;
            if (slots.size() >= maxPlayers) return;

            int id = nextFreePlayerId();
            if (id < 0) return;

            // Бот теж отримує звичайний playerId: для lockstep він такий самий
            // учасник, як людина, просто його накази рахує хост.
            PlayerSlot bot = PlayerSlot.bot(id, botName(difficulty), team, seat, difficulty);
            bot.colorIndex = id;
            slots.add(bot);
            sortSlots();
            changed = true;
        }
        if (changed) publish();
    }

    @Override
    public void removeBot(int playerId) {
        boolean changed = false;
        synchronized (lock) {
            if (status != LobbyStatus.WAITING) return;
            for (int i = slots.size() - 1; i >= 0; i--) {
                if (slots.get(i).playerId == playerId && slots.get(i).bot) {
                    slots.remove(i);
                    changed = true;
                }
            }
        }
        if (changed) publish();
    }

    @Override
    public void setBotDifficulty(int playerId, String difficulty) {
        boolean changed = false;
        synchronized (lock) {
            if (status != LobbyStatus.WAITING) return;
            PlayerSlot slot = find(playerId);
            if (slot == null || !slot.bot) return;
            slot.botDifficulty = difficulty;
            slot.nick          = botName(difficulty);
            changed = true;
        }
        if (changed) publish();
    }

    @Override
    public void kick(int playerId) {
        if (playerId == HOST_PLAYER_ID) return;   // хост сам себе не виганяє

        Integer connectionId = null;
        boolean changed = false;
        synchronized (lock) {
            if (status != LobbyStatus.WAITING) return;
            PlayerSlot slot = find(playerId);
            if (slot == null) return;
            if (slot.bot) { removeBotLocked(playerId); changed = true; }
            else {
                for (Map.Entry<Integer, Integer> e : connectionToPlayer.entrySet()) {
                    if (e.getValue() == playerId) { connectionId = e.getKey(); break; }
                }
            }
        }

        // Повідомлення ПЕРЕД розривом: інакше вигнаний побачив би звичайне
        // «зв'язок із хостом втрачено» і думав би на свою мережу.
        if (connectionId != null && server != null) {
            Connection target = findConnection(connectionId);
            if (target != null) {
                try { target.sendTCP(new KickPlayer(playerId, "Хост виключив тебе з лоббі.")); }
                catch (Exception ignored) {}
                target.close();
            }
            removeByConnection(connectionId);
            return;
        }
        if (changed) publish();
    }

    private void removeBotLocked(int playerId) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            if (slots.get(i).playerId == playerId) slots.remove(i);
        }
    }

    private Connection findConnection(int connectionId) {
        Connection[] all = server.getConnections();
        for (int i = 0; i < all.length; i++) {
            if (all[i].getID() == connectionId) return all[i];
        }
        return null;
    }

    @Override
    public void sendChat(String text) {
        relayChat(HOST_PLAYER_ID, text);
    }

    /**
     * Розіслати повідомлення чату всім.
     *
     * <p>Автор підставляється ТУТ, зі списку слотів: полю {@code playerId} у
     * присланому повідомленні довіряти не можна — інакше будь-хто пише від
     * чужого імені. Порожні й задовгі рядки відсікаються так само на хості,
     * бо клієнт міг бути й не наш.
     */
    private void relayChat(int playerId, String text) {
        if (text == null) return;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return;
        if (trimmed.length() > NetConfig.MAX_CHAT_LENGTH) {
            trimmed = trimmed.substring(0, NetConfig.MAX_CHAT_LENGTH);
        }

        String nick;
        synchronized (lock) {
            PlayerSlot slot = find(playerId);
            if (slot == null) return;
            nick = slot.nick;
        }

        ChatMessage message = new ChatMessage(playerId, nick, trimmed);
        if (server != null) server.sendToAllTCP(message);
        events.post(l -> l.onChat(message));
    }

    // ── Дрібні помічники складу (усі під lock) ────────────────────────────

    private PlayerSlot find(int playerId) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).playerId == playerId) return slots.get(i);
        }
        return null;
    }

    private PlayerSlot occupant(int team, int seat) {
        for (int i = 0; i < slots.size(); i++) {
            PlayerSlot s = slots.get(i);
            if (s.team == team && s.seat == seat) return s;
        }
        return null;
    }

    private boolean validSeat(int team, int seat) {
        return team >= 0 && team < NetConfig.TEAM_COUNT
            && seat >= 0 && seat < NetConfig.TEAM_SIZE;
    }

    private boolean seatFree(int team, int seat) {
        return validSeat(team, seat)
            && (closedMask[team] & (1 << seat)) == 0
            && occupant(team, seat) == null;
    }

    /**
     * Порядок слотів — за playerId. Його обіцяє {@link LobbyState}, і на нього
     * спирається {@code PlayerRoster}: бот, доданий після виходу гостя,
     * отримує звільнений номер і мусить стати на своє місце в списку.
     */
    private void sortSlots() {
        slots.sort((a, b) -> Integer.compare(a.playerId, b.playerId));
    }

    /** Ім'я бота в списку — рівень, а не «Бот 3»: рівень і є те, що про нього треба знати. */
    private static String botName(String difficulty) {
        Difficulty level = Difficulty.byName(difficulty);
        return "БОТ · " + (level == null ? "?" : level.title);
    }

    @Override
    public void startMatch() {
        StartMatch start;
        synchronized (lock) {
            if (status != LobbyStatus.WAITING) return;
            if (!allReady()) return;
            status = LobbyStatus.STARTING;

            start = new StartMatch();
            start.protocolVersion = NetConfig.PROTOCOL_VERSION;
            // Seed береться з системного часу — це єдине місце, де він доречний:
            // сам seed до симуляції не належить, а от усе, що з нього
            // розгорнеться, буде однаковим у всіх, бо приїде готовим числом.
            start.rngSeed    = System.nanoTime() ^ (System.currentTimeMillis() << 21);
            start.scenarioId = scenarioId;
            start.slots      = copySeatedSlots();
            start.tickRate              = NetConfig.TICK_RATE;
            start.inputDelayTicks       = NetConfig.getInputDelayTicks();
            start.checksumIntervalTicks = NetConfig.getChecksumIntervalTicks();
        }

        publish();
        if (server != null) server.sendToAllTCP(start);
        events.post(l -> l.onMatchStarting(start));
    }

    @Override
    public void leave() {
        alive = false;
        if (beacon != null) beacon.stop();
        if (server != null) {
            server.close();
            server.stop();
        }
        events.clear();
    }

    @Override
    public void pump(LobbySession.Listener listener) {
        events.drain(listener);
    }

    /**
     * Трафік матчу, що прийшов ДО створення каналу матчу.
     *
     * <p>У хоста вікно вужче, ніж у гостя, але воно є: {@code startMatch()}
     * розсилає {@code StartMatch} одразу, а транспорт створюється вже з екрана,
     * наступним кадром. Швидкий гість устигає відповісти розігрівом у цей
     * проміжок — і без цієї схованки пакет гине мовчки, а матч потім стоїть,
     * чекаючи наказів, які насправді вже приходили.
     */
    private final ArrayList<Object>  matchBacklog      = new ArrayList<>();
    private final ArrayList<Integer> matchBacklogConns = new ArrayList<>();
    private boolean matchOpened;

    @Override
    public MatchTransport openMatch(StartMatch start) {
        if (server == null || !alive) return null;

        int[] playerIds;
        Map<Integer, Integer> connections;
        synchronized (lock) {
            status = LobbyStatus.IN_GAME;
            playerIds = new int[start.slots.size()];
            for (int i = 0; i < start.slots.size(); i++) playerIds[i] = start.slots.get(i).playerId;
            // Знімок, а не живе посилання: мапа далі змінюється з мережевого
            // потоку при розривах, а транспорт читає її на кожне повідомлення.
            connections = new LinkedHashMap<>(connectionToPlayer);
        }
        publish();

        HostMatchTransport transport =
            new HostMatchTransport(server, playerIds, connections, this::leave);

        synchronized (matchBacklog) {
            matchOpened = true;
            transport.replay(matchBacklogConns, matchBacklog);
            matchBacklog.clear();
            matchBacklogConns.clear();
        }
        return transport;
    }

    // ── Внутрішнє ─────────────────────────────────────────────────────────

    /**
     * Умови старту. Правило одне для хоста й для UI, тому воно живе в
     * {@link LobbyState#allReady()}, а тут лише зібрано робочий стан у стан
     * лоббі: розійтись цим двом не можна — кнопка «Старт» показувала б одне, а
     * хост робив би інше.
     */
    private boolean allReady() {
        return snapshot().allReady();
    }

    private ArrayList<PlayerSlot> copySlots() {
        ArrayList<PlayerSlot> copy = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) copy.add(slots.get(i).copy());
        return copy;
    }

    /**
     * Тільки ті, хто справді йде в матч.
     *
     * <p>Список очікування в {@code StartMatch} потрапити не може: lockstep
     * чекає наказів ВІД КОЖНОГО зі складу, і глядач, що не має симуляції,
     * зупинив би матч на першому ж тіку.
     */
    private ArrayList<PlayerSlot> copySeatedSlots() {
        ArrayList<PlayerSlot> copy = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).seated()) copy.add(slots.get(i).copy());
        }
        return copy;
    }

    /** Копія стану під {@link #lock}, якщо його ще не тримають. */
    private LobbyState snapshot() {
        synchronized (lock) {
            return snapshotLocked();
        }
    }

    private LobbyState snapshotLocked() {
        LobbyState state = new LobbyState();
        state.lobbyName  = lobbyName;
        state.scenarioId = scenarioId;
        state.maxPlayers = maxPlayers;
        state.status     = status;
        state.slots      = copySlots();
        state.closedMask = closedMask.clone();
        return state;
    }

    private LobbyInfo buildInfo() {
        synchronized (lock) {
            return new LobbyInfo(NetConfig.PROTOCOL_VERSION, lobbyName,
                                 slots.isEmpty() ? "" : slots.get(0).nick,
                                 slots.size(), maxPlayers, status, scenarioId);
        }
    }

    /**
     * Зібрати копію стану, розіслати її всім і показати собі.
     *
     * <p>Розсилається саме копія: KryoNet серіалізує в своєму потоці, і якби
     * туди пішов живий список, паралельне приєднання гравця псувало б пакет
     * на середині запису.
     */
    private void publish() {
        LobbyState snapshot = snapshot();
        published = snapshot;

        if (server != null) server.sendToAllTCP(snapshot);
        if (beacon != null) beacon.updateInfo(buildInfo());
        events.post(l -> l.onLobbyState(snapshot));
    }

    /** Найменший вільний номер. Хост завжди 0, гості заповнюють дірки після виходів. */
    private int nextFreePlayerId() {
        for (int candidate = 0; candidate < maxPlayers; candidate++) {
            boolean taken = false;
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).playerId == candidate) { taken = true; break; }
            }
            if (!taken) return candidate;
        }
        return -1;
    }

    private void removeByConnection(int connectionId) {
        boolean changed = false;
        synchronized (lock) {
            Integer playerId = connectionToPlayer.remove(connectionId);
            if (playerId != null) {
                for (int i = slots.size() - 1; i >= 0; i--) {
                    if (slots.get(i).playerId == playerId) { slots.remove(i); changed = true; }
                }
            }
        }
        if (changed) publish();
    }

    // ── Мережеві події ────────────────────────────────────────────────────

    // Ім'я Listener тут неоднозначне: LobbySession несе вкладений інтерфейс із
    // такою ж назвою, і успадкований член перекриває імпорт. Тому клас KryoNet
    // вказується повним іменем.
    private final class HostListener extends com.esotericsoftware.kryonet.Listener {

        @Override
        public void received(Connection connection, Object object) {
            if (object instanceof JoinRequest) {
                handleJoin(connection, (JoinRequest) object);
            } else if (object instanceof SetReady) {
                handleReady(connection, (SetReady) object);
            } else if (object instanceof LeaveLobby) {
                removeByConnection(connection.getID());
            } else if (object instanceof SetTeam) {
                SetTeam m = (SetTeam) object;
                Integer playerId = playerOf(connection);
                if (playerId != null) applyTeam(playerId, m.team, m.seat);
            } else if (object instanceof ChatMessage) {
                Integer playerId = playerOf(connection);
                if (playerId != null) relayChat(playerId, ((ChatMessage) object).text);
            } else if (object instanceof SetSlotClosed || object instanceof AddBot
                    || object instanceof RemoveBot   || object instanceof SetBotDifficulty
                    || object instanceof KickPlayer) {
                // Ці дії — тільки хостові, і хост не надсилає їх собі по мережі.
                // Отже, прийшли вони від гостя, який або зібраний із чужої
                // збірки, або пробує керувати чужим лоббі. Мовчки ігноруємо:
                // відповідати на такі повідомлення означає підказувати.
                NetLog.error("Гість надіслав хостову команду лоббі — проігноровано");
            } else {
                // Решта — трафік МАТЧУ, що випередив створення його каналу.
                // Складаємо вбік разом із номером з'єднання: автора хост
                // визначає саме за ним. Див. matchBacklog.
                synchronized (matchBacklog) {
                    if (!matchOpened) {
                        matchBacklogConns.add(connection.getID());
                        matchBacklog.add(object);
                    }
                }
            }
        }

        @Override
        public void disconnected(Connection connection) {
            // У лоббі розрив звільняє слот одразу. Пільговий час на
            // перепідключення має сенс лише в матчі, де гравця чекає симуляція.
            removeByConnection(connection.getID());
        }

        private Integer playerOf(Connection connection) {
            synchronized (lock) {
                return connectionToPlayer.get(connection.getID());
            }
        }

        private void handleJoin(Connection connection, JoinRequest request) {
            String rejection = null;
            int assignedId = -1;

            synchronized (lock) {
                String nick = request.nick == null ? "" : request.nick.trim();

                if (request.protocolVersion != NetConfig.PROTOCOL_VERSION) {
                    rejection = "Інша версія гри (у хоста " + NetConfig.PROTOCOL_VERSION
                              + ", у тебе " + request.protocolVersion + ").";
                } else if (status != LobbyStatus.WAITING) {
                    rejection = "Матч уже почався.";
                } else if (nick.isEmpty()) {
                    rejection = "Порожній нік.";
                } else if (slots.size() >= maxPlayers) {
                    rejection = "Лоббі повне.";
                } else if (nickTaken(nick)) {
                    rejection = "Нік «" + nick + "» уже зайнятий у цьому лоббі.";
                } else {
                    assignedId = nextFreePlayerId();
                    if (assignedId < 0) {
                        rejection = "Немає вільних слотів.";
                    } else {
                        // Новачок потрапляє в СПИСОК ОЧІКУВАННЯ, а не одразу в
                        // команду: сторону обирає він сам, і автоматична
                        // посадка означала б, що двоє друзів, які зайшли
                        // одночасно, опиняються по різні боки й мусять
                        // мінятись назад.
                        PlayerSlot slot = new PlayerSlot(assignedId, nick, false);
                        slot.colorIndex = assignedId;
                        slots.add(slot);
                        sortSlots();
                        connectionToPlayer.put(connection.getID(), assignedId);
                    }
                }
            }

            if (rejection != null) {
                connection.sendTCP(JoinResponse.reject(rejection));
                connection.close();
                return;
            }

            // Спершу персональна відповідь із номером гравця, потім спільний
            // стан усім — щоб новачок дізнався свій id раніше, ніж побачить
            // себе у списку і почав шукати, котрий рядок його.
            connection.sendTCP(JoinResponse.accept(assignedId, snapshot()));
            publish();
        }

        private boolean nickTaken(String nick) {
            for (int i = 0; i < slots.size(); i++) {
                if (nick.equalsIgnoreCase(slots.get(i).nick)) return true;
            }
            return false;
        }

        private void handleReady(Connection connection, SetReady message) {
            boolean changed = false;
            synchronized (lock) {
                Integer playerId = connectionToPlayer.get(connection.getID());
                if (playerId != null) {
                    for (int i = 0; i < slots.size(); i++) {
                        if (slots.get(i).playerId == playerId) {
                            slots.get(i).ready = message.ready;
                            changed = true;
                        }
                    }
                }
            }
            if (changed) publish();
        }
    }
}
