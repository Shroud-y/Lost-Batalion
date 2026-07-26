package io.jababa.lost_batalion.net.kryo;

import io.jababa.lost_batalion.net.NetLog;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.NetworkProtocol;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.discovery.LobbyBeacon;
import io.jababa.lost_batalion.net.messages.JoinRequest;
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

    /** Остання опублікована копія — те, що читає UI. */
    private volatile LobbyState published;

    private Server      server;
    private LobbyBeacon beacon;
    private volatile boolean alive;

    public HostLobbySession(String lobbyName, String hostNick, String scenarioId, int maxPlayers) {
        this.lobbyName  = lobbyName;
        this.scenarioId = scenarioId;
        this.maxPlayers = Math.max(2, Math.min(maxPlayers, NetConfig.MAX_PLAYERS));
        slots.add(new PlayerSlot(HOST_PLAYER_ID, hostNick, true));

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
            start.slots      = copySlots();
            start.tickRate              = NetConfig.TICK_RATE;
            start.inputDelayTicks       = NetConfig.INPUT_DELAY_TICKS;
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

        return new HostMatchTransport(server, playerIds, connections, this::leave);
    }

    // ── Внутрішнє ─────────────────────────────────────────────────────────

    private boolean allReady() {
        if (slots.size() < 2) return false;
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).ready || !slots.get(i).connected) return false;
        }
        return true;
    }

    private ArrayList<PlayerSlot> copySlots() {
        ArrayList<PlayerSlot> copy = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            PlayerSlot src = slots.get(i);
            PlayerSlot dst = new PlayerSlot(src.playerId, src.nick, src.host);
            dst.ready     = src.ready;
            dst.connected = src.connected;
            copy.add(dst);
        }
        return copy;
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
        LobbyState snapshot = new LobbyState();
        synchronized (lock) {
            snapshot.lobbyName  = lobbyName;
            snapshot.scenarioId = scenarioId;
            snapshot.maxPlayers = maxPlayers;
            snapshot.status     = status;
            snapshot.slots      = copySlots();
        }
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
            }
        }

        @Override
        public void disconnected(Connection connection) {
            // У лоббі розрив звільняє слот одразу. Пільговий час на
            // перепідключення має сенс лише в матчі, де гравця чекає симуляція.
            removeByConnection(connection.getID());
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
                        slots.add(new PlayerSlot(assignedId, nick, false));
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
            LobbyState snapshot = new LobbyState();
            synchronized (lock) {
                snapshot.lobbyName  = lobbyName;
                snapshot.scenarioId = scenarioId;
                snapshot.maxPlayers = maxPlayers;
                snapshot.status     = status;
                snapshot.slots      = copySlots();
            }
            connection.sendTCP(JoinResponse.accept(assignedId, snapshot));
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
