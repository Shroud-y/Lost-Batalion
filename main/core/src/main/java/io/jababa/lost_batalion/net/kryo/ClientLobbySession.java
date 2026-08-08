package io.jababa.lost_batalion.net.kryo;

import io.jababa.lost_batalion.net.NetLog;
import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.NetworkProtocol;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.messages.ChatMessage;
import io.jababa.lost_batalion.net.messages.JoinRequest;
import io.jababa.lost_batalion.net.messages.KickPlayer;
import io.jababa.lost_batalion.net.messages.SetTeam;
import io.jababa.lost_batalion.net.messages.JoinResponse;
import io.jababa.lost_batalion.net.messages.LeaveLobby;
import io.jababa.lost_batalion.net.messages.LobbyState;
import io.jababa.lost_batalion.net.messages.SetReady;
import io.jababa.lost_batalion.net.messages.StartMatch;

/**
 * Лоббі з боку гостя.
 *
 * <p>Нічого не вирішує: тримає останній {@link LobbyState}, який прислав хост,
 * і відправляє свої наміри (готовність, вихід). Спроби вгадати стан локально —
 * найкоротший шлях до розбіжності між тим, що бачить гість, і тим, що насправді
 * в лоббі.
 *
 * <p>{@code connect} блокує потік до таймауту, тому підключення йде в окремому
 * потоці: інакше при недоступному хості гра застигала б на кілька секунд.
 */
public class ClientLobbySession implements LobbySession {

    /** Скільки чекати на встановлення TCP-з'єднання. */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final SessionEventQueue events = new SessionEventQueue();
    private final String host;
    private final int    port;
    private final String nick;

    private Client client;
    private volatile LobbyState published;
    private volatile int     localPlayerId = -1;
    private volatile boolean connected;
    /** Після відмови або розриву сесія мертва остаточно — перепідключення тут немає. */
    private volatile boolean closed;

    public ClientLobbySession(String host, int port, String nick) {
        this.host = host;
        this.port = port;
        this.nick = nick;

        try {
            client = new Client(NetConfig.WRITE_BUFFER_BYTES, NetConfig.OBJECT_BUFFER_BYTES);
            NetworkProtocol.register(client);
            client.addListener(new ClientListener());
            client.start();
        } catch (Exception e) {
            NetLog.error("Не вдалось створити мережевий клієнт: " + e);
            events.post(l -> l.onDisconnected("Не вдалось створити мережевий клієнт: " + e.getMessage()));
            return;
        }

        Thread connector = new Thread(this::connect, "lobby-connect");
        connector.setDaemon(true);
        connector.start();
    }

    private void connect() {
        try {
            client.connect(CONNECT_TIMEOUT_MS, host, port);
            connected = true;
            client.sendTCP(new JoinRequest(NetConfig.PROTOCOL_VERSION, nick));
        } catch (Exception e) {
            closed = true;
            events.post(l -> l.onDisconnected(
                "Не вдалось підключитись до " + host + ":" + port + " — " + e.getMessage()));
        }
    }

    // ── LobbySession ──────────────────────────────────────────────────────

    @Override public LobbyState getState()   { return published; }
    @Override public int  getLocalPlayerId() { return localPlayerId; }
    @Override public boolean isHost()        { return false; }
    @Override public boolean isConnected()   { return connected && !closed; }

    @Override
    public void setReady(boolean ready) {
        if (!isConnected()) return;
        client.sendTCP(new SetReady(ready));
    }

    @Override
    public void setTeam(int team, int seat) {
        if (!isConnected()) return;
        // Локально нічого не міняється: місце міг щойно зайняти інший, і
        // єдина правда про склад — та, яку пришле хост.
        client.sendTCP(new SetTeam(team, seat));
    }

    @Override
    public void sendChat(String text) {
        if (!isConnected() || text == null || text.trim().isEmpty()) return;
        client.sendTCP(new ChatMessage(text.trim()));
    }

    // Усе нижче — хостові дії. Гість їх не надсилає взагалі: хост однаково
    // ігнорує такі повідомлення від гостей, а мовчазний метод чесніше показує,
    // що кнопки цих дій гостю просто не малюються.

    /** Карту обирає хост. */
    @Override public void setScenario(String scenarioId) { }

    /** Старт натискає хост. */
    @Override public void startMatch() { }

    @Override public void setSlotClosed(int team, int seat, boolean closed) { }
    @Override public void addBot(int team, int seat, String difficulty) { }
    @Override public void removeBot(int playerId) { }
    @Override public void setBotDifficulty(int playerId, String difficulty) { }
    @Override public void kick(int playerId) { }

    @Override
    public void leave() {
        if (client == null) return;
        // Свідомий вихід повідомляється явно: хост звільнить слот одразу,
        // не чекаючи, поки TCP помітить зниклого клієнта.
        if (isConnected()) {
            try { client.sendTCP(new LeaveLobby()); } catch (Exception ignored) {}
        }
        closed = true;
        connected = false;
        client.close();
        client.stop();
        events.clear();
    }

    @Override
    public void pump(LobbySession.Listener listener) {
        events.drain(listener);
    }

    /**
     * Повідомлення матчу, що прийшли ДО того, як екран створив канал матчу.
     *
     * <p>Між прибуттям {@code StartMatch} і створенням {@code
     * ClientMatchTransport} минає щонайменше кадр: подія лоббі спершу лягає в
     * чергу, а розбирає її вже екран у своєму {@code render}. Хост за цей час
     * встигає надіслати розігрів — і той приходить у це саме з'єднання, коли
     * слухача матчу ще немає.
     *
     * <p><b>Це не теоретична гонка — вона стріляла.</b> Виміряно на двох живих
     * клієнтах: хост надсилав розігрів на 19 мс раніше, ніж гість реєстрував
     * слухача. Пакети гинули мовчки, гість назавжди лишався на «Очікуємо
     * суперника», а хост — на «гравець гальмує матч». Жодної помилки при цьому
     * ніде не з'являлось.
     */
    private final java.util.ArrayList<Object> matchBacklog = new java.util.ArrayList<>();
    /** Чи канал матчу вже існує — далі складати вбік нема потреби. */
    private boolean matchOpened;

    @Override
    public MatchTransport openMatch(StartMatch start) {
        if (client == null || !isConnected()) return null;

        int[] playerIds = new int[start.slots.size()];
        for (int i = 0; i < start.slots.size(); i++) playerIds[i] = start.slots.get(i).playerId;

        ClientMatchTransport transport =
            new ClientMatchTransport(client, localPlayerId, playerIds, this::leave);

        // Слухач уже стоїть, тож пакет, що прийде саме зараз, може продублюватись
        // із відкладеним. Це нешкідливо: буфер команд бере лише перше
        // повідомлення на пару (тік, гравець).
        synchronized (matchBacklog) {
            matchOpened = true;
            transport.replay(matchBacklog);
            matchBacklog.clear();
        }
        return transport;
    }

    // ── Мережеві події ────────────────────────────────────────────────────

    // Ім'я Listener тут неоднозначне: LobbySession несе вкладений інтерфейс із
    // такою ж назвою, і успадкований член перекриває імпорт. Тому клас KryoNet
    // вказується повним іменем.
    private final class ClientListener extends com.esotericsoftware.kryonet.Listener {

        @Override
        public void received(Connection connection, Object object) {
            if (object instanceof JoinResponse) {
                handleJoinResponse((JoinResponse) object);
            } else if (object instanceof LobbyState) {
                LobbyState state = (LobbyState) object;
                published = state;
                events.post(l -> l.onLobbyState(state));
            } else if (object instanceof StartMatch) {
                StartMatch start = (StartMatch) object;
                events.post(l -> l.onMatchStarting(start));
            } else if (object instanceof ChatMessage) {
                ChatMessage message = (ChatMessage) object;
                events.post(l -> l.onChat(message));
            } else if (object instanceof KickPlayer) {
                // Причина приходить ДО розриву — інакше вигнаний побачив би
                // звичайне «зв'язок втрачено» і думав би на свою мережу.
                // closed виставляється тут-таки, щоб наступний disconnected
                // не перезаписав повідомлення.
                KickPlayer kick = (KickPlayer) object;
                closed = true;
                connected = false;
                String reason = kick.reason == null || kick.reason.isEmpty()
                              ? "Хост виключив тебе з лоббі." : kick.reason;
                events.post(l -> l.onDisconnected(reason));
            } else {
                // Усе інше — це вже трафік МАТЧУ, який випередив створення його
                // каналу. Складаємо вбік, див. matchBacklog.
                synchronized (matchBacklog) {
                    if (!matchOpened) matchBacklog.add(object);
                }
            }
        }

        @Override
        public void disconnected(Connection connection) {
            connected = false;
            if (closed) return;   // ми самі вийшли — повідомляти нема про що
            closed = true;
            events.post(l -> l.onDisconnected("Зв'язок із хостом втрачено."));
        }

        private void handleJoinResponse(JoinResponse response) {
            if (!response.accepted) {
                closed = true;
                connected = false;
                events.post(l -> l.onDisconnected(response.reason));
                return;
            }
            localPlayerId = response.assignedPlayerId;
            published     = response.lobbyState;
            events.post(l -> l.onLobbyState(response.lobbyState));
        }
    }
}
