package io.jababa.lost_batalion.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNetworking;
import com.codedisaster.steamworks.SteamNetworkingCallback;
import io.jababa.lost_batalion.ai.BotPlayer;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.kryo.MatchEventQueue;
import io.jababa.lost_batalion.net.messages.DesyncAlert;
import io.jababa.lost_batalion.net.messages.PlayerDropped;
import io.jababa.lost_batalion.net.messages.ResumeMatch;
import io.jababa.lost_batalion.net.messages.ResyncAck;
import io.jababa.lost_batalion.net.messages.ResyncSnapshot;
import io.jababa.lost_batalion.net.messages.TickChecksum;
import io.jababa.lost_batalion.net.messages.TickCommands;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Канал матчу поверх P2P Steam.
 *
 * <p><b>Топологія та сама, що в локальній мережі: зірка через хоста.</b> Гість
 * шле лише хосту, хост ретранслює всім і кладе собі в чергу тим самим шляхом.
 * У 1v1 пряме з'єднання виглядало б дешевшим, але порядок доставки тоді
 * визначався б окремо в кожного, а {@code MatchRunner} однаковості порядку
 * вимагає. Зайвий перескок дешевший за десинхрон.
 *
 * <p><b>Ніяких потоків.</b> KryoNet приймає в своєму потоці, і саме через це в
 * ядрі є {@link MatchEventQueue}. Тут пакети читаються з {@link #pump} —
 * тобто вже з потоку симуляції, — і черга лишається тільки заради контракту
 * та однакового порядку між своїми й чужими повідомленнями.
 *
 * <p><b>Чужі не пролізуть.</b> На AppID 480 P2P-сесію може попросити будь-хто;
 * приймаються лише учасники цього матчу ({@link #peers}).
 */
public class SteamMatchTransport implements MatchTransport, SteamNetworkingCallback {

    private static final int HOST_PLAYER_ID = 0;

    /**
     * Канал P2P. Не нульовий свідомо: нуль — те, що бере будь-який приклад зі
     * Spacewar, а на спільному AppID зайвий шанс поплутатись ні до чого.
     */
    private static final int CHANNEL = 7;

    private final MatchEventQueue  events = new MatchEventQueue();
    private final SteamPacketCodec codec  = new SteamPacketCodec();

    private final SteamNetworking networking;
    private final int      localPlayerId;
    private final int[]    playerIds;
    private final boolean  host;
    private final Runnable closer;

    /** Учасники матчу, крім себе: {@code playerId} → SteamID. */
    private final List<Peer> peers = new ArrayList<>();

    /** Прийомний буфер. Прямий — інакше Steamworks не приймає. */
    private ByteBuffer inbox = ByteBuffer.allocateDirect(64 * 1024);

    /**
     * Буфер відправки — ОДИН на транспорт, а не новий на кожен пакет.
     *
     * <p>Накази йдуть 40 разів на секунду, і {@code allocateDirect} на кожен —
     * це і системний виклик, і прямa пам'ять, яку звільняє лише збирач сміття.
     * За кілька хвилин матчу так вичерпується ліміт прямої пам'яті.
     */
    private ByteBuffer outbox = ByteBuffer.allocateDirect(64 * 1024);

    private boolean open = true;

    /**
     * Скільки НЕДАВНІХ пакетів наказів довкладається до кожної відправки.
     *
     * <p>Це і є ліки від поодиноких ривків. Надійна доставка Steam гарантує,
     * що пакет дійде, але робить це ретрансмітом через час обігу — і, гірше,
     * тримає за собою всі наступні пакети потоку (head-of-line). Одна
     * загублена датаграма означає застиглий матч на кілька сотень
     * мілісекунд, після чого симуляція надолужує пачкою тіків. Саме так
     * ривок і виглядає зсередини.
     *
     * <p>Тому накази йдуть ДВІЧІ: ненадійно (швидко, повз чергу надійного
     * потоку) з копіями двох попередніх тіків, і надійно — як остаточна
     * гарантія. Зазвичай перемагає ненадійна копія, і затримки не видно
     * взагалі; якщо ж загубились усі три поспіль, матч урятує надійна.
     *
     * <p>Ціна: замість 40 пакетів на секунду близько 160, по три десятки
     * байтів. Дублікати нешкідливі — {@code CommandBuffer.receive} бере лише
     * перше повідомлення на пару (тік, гравець).
     */
    private static final int REDUNDANT_COPIES = 2;

    /**
     * Ненадійне повідомлення в Steam обмежене приблизно 1200 байтами. Більший
     * пакет (наказ на сотню юнітів) поїде лише надійним шляхом.
     */
    private static final int UNRELIABLE_LIMIT = 1100;

    /** Останні відправлені пакети наказів — найсвіжіший у кінці. */
    private final java.util.ArrayDeque<byte[]> recentCommands = new java.util.ArrayDeque<>();

    /**
     * Скільки пакетів тримати в черзі надлишковості.
     *
     * <p>Не константа саме через ботів: за один тік хост відправляє не один
     * пакет, а {@code 1 + кількість ботів}, і при сталій межі в дві штуки черга
     * встигала б наповнитись накзами ботів ТОГО САМОГО тіку. Тобто дублювався б
     * той самий тік замість двох попередніх, і захист від поодинокої втрати
     * зникав би рівно там, де боти його не стосуються.
     */
    private int redundancyBudget() { return (1 + bots.length) * REDUNDANT_COPIES; }

    private static final class Peer {
        final int     playerId;
        final SteamID id;
        final String  key;
        Peer(int playerId, SteamID id) {
            this.playerId = playerId;
            this.id       = id;
            this.key      = SteamMatchmakingHub.toAddress(id);
        }
    }

    /**
     * @param members усі учасники в канонічному порядку (власник лоббі перший)
     * @param localPlayerId номер цієї сторони — індекс у {@code members}
     */
    public SteamMatchTransport(List<SteamID> members, int localPlayerId,
                               int[] playerIds, Runnable closer) {
        this.localPlayerId = localPlayerId;
        this.playerIds     = playerIds;
        this.host          = localPlayerId == HOST_PLAYER_ID;
        this.closer        = closer;
        this.networking    = new SteamNetworking(this);

        for (int i = 0; i < members.size(); i++) {
            if (i == localPlayerId) continue;
            // Гість тримає в сусідах ЛИШЕ хоста: слати комусь іще він не має
            // права, інакше зірка перетворилась би на сітку з власним
            // порядком доставки в кожного.
            if (!host && i != HOST_PLAYER_ID) continue;
            peers.add(new Peer(i, members.get(i)));
        }

        // Ретрансляція через сервери Valve, коли прямий канал не піднімається
        // (симетричний NAT). Повільніше, але це різниця між «грає» і «не грає».
        networking.allowP2PPacketRelay(true);
    }

    // ── MatchTransport ────────────────────────────────────────────────────

    @Override public int     getLocalPlayerId() { return localPlayerId; }
    @Override public int[]   getPlayerIds()     { return playerIds; }
    @Override public boolean isHost()           { return host; }
    @Override public boolean isConnected()      { return open; }

    /** Боти, за яких відповідає хост. Порожньо в гостя і в матчі без ботів. */
    private BotPlayer[] bots = NO_BOTS;

    private static final BotPlayer[] NO_BOTS = new BotPlayer[0];

    @Override
    public void setBots(BotPlayer[] next) {
        this.bots = next == null ? NO_BOTS : next;
    }

    @Override
    public void sendCommands(TickCommands commands) {
        if (!open) return;
        commands.playerId = localPlayerId;
        sendWithRedundancy(commands);
        // Свої накази — у власну чергу тим самим шляхом і в ту саму мить, що й
        // ретрансльовані: послідовність не має залежати від авторства.
        events.postCommands(commands);

        // Накази ботів народжуються РАЗОМ із цим флешем і на той самий тік —
        // див. MatchTransport.setBots. Для гостя вони нічим не відрізняються від
        // людських, тож ідуть тим самим шляхом, включно з надлишковими копіями.
        for (int i = 0; i < bots.length; i++) {
            TickCommands botCommands = bots[i].commandsFor(commands.tick, commands.generation);
            sendWithRedundancy(botCommands);
            events.postCommands(botCommands);
        }
    }

    /**
     * Надіслати накази надійно ОДИН раз і ненадійно — разом із копіями
     * попередніх тіків. Див. {@link #REDUNDANT_COPIES}.
     */
    private void sendWithRedundancy(Object commands) {
        byte[] data = codec.encode(commands);
        if (data == null) {
            events.postDisconnect("Не вдалось надіслати накази — матч неможливо продовжити");
            return;
        }

        for (Peer peer : peers) send(peer, data, SteamNetworking.P2PSend.Reliable);

        if (data.length <= UNRELIABLE_LIMIT) {
            for (Peer peer : peers) {
                send(peer, data, SteamNetworking.P2PSend.UnreliableNoDelay);
                for (byte[] old : recentCommands) {
                    send(peer, old, SteamNetworking.P2PSend.UnreliableNoDelay);
                }
            }

            recentCommands.addLast(data);
            while (recentCommands.size() > redundancyBudget()) recentCommands.removeFirst();
        }
    }

    @Override
    public void sendChecksum(TickChecksum checksum) {
        if (!open) return;
        checksum.playerId = localPlayerId;
        broadcast(checksum);
        events.postChecksum(checksum);
    }

    @Override
    public void sendDesyncAlert(DesyncAlert alert) {
        if (!open || !host) return;
        broadcast(alert);
        events.postDesyncAlert(alert);
    }

    @Override
    public void sendResyncSnapshot(ResyncSnapshot snapshot) {
        if (!open || !host) return;
        // Хост собі знімок не застосовує — він і є його джерелом.
        broadcast(snapshot);
    }

    @Override
    public void sendResyncAck(ResyncAck ack) {
        if (!open) return;
        if (host) {
            events.postResyncAck(ack);   // хост підтверджує сам собі
        } else {
            ack.playerId = localPlayerId;
            broadcast(ack);
        }
    }

    @Override
    public void sendResumeMatch(ResumeMatch resume) {
        if (!open || !host) return;
        broadcast(resume);
        events.postResume(resume);
    }

    @Override
    public void sendPlayerDropped(PlayerDropped dropped) {
        if (!open || !host) return;
        broadcast(dropped);
        events.postPlayerDropped(dropped);
    }

    @Override
    public void pump(Listener listener) {
        receive();
        events.drain(listener);
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        for (Peer peer : peers) networking.closeP2PSessionWithUser(peer.id);
        events.clear();
        if (closer != null) closer.run();
    }

    // ── Передача ──────────────────────────────────────────────────────────

    private void broadcast(Object message) {
        byte[] data = codec.encode(message);
        if (data == null) {
            // Запакувати не вдалось — мовчки продовжити не можна: у lockstep
            // пропущене повідомлення це зупинка матчу назавжди.
            events.postDisconnect("Не вдалось надіслати "
                + message.getClass().getSimpleName() + " — матч неможливо продовжити");
            return;
        }
        for (Peer peer : peers) send(peer, data, SteamNetworking.P2PSend.Reliable);
    }

    private void send(Peer peer, byte[] data, SteamNetworking.P2PSend mode) {
        try {
            if (outbox.capacity() < data.length) {
                outbox = ByteBuffer.allocateDirect(
                    Math.min(Math.max(data.length, outbox.capacity() * 2),
                             SteamPacketCodec.MAX_PACKET_BYTES));
            }
            outbox.clear();
            outbox.put(data, 0, data.length);
            outbox.flip();
            networking.sendP2PPacket(peer.id, outbox, mode, CHANNEL);
        } catch (Exception e) {
            SteamMatchmakingHub.log("не вдалось надіслати гравцю " + peer.playerId + ": " + e);
        }
    }

    // ── Прийом ────────────────────────────────────────────────────────────

    private void receive() {
        if (!open) return;

        int[] size = new int[1];
        while (networking.isP2PPacketAvailable(CHANNEL, size)) {
            ensureCapacity(size[0]);
            inbox.clear();

            SteamID sender = new SteamID();
            int read;
            try {
                read = networking.readP2PPacket(sender, inbox, CHANNEL);
            } catch (Exception e) {
                SteamMatchmakingHub.log("помилка читання P2P: " + e);
                return;
            }
            if (read <= 0) continue;

            Peer peer = findPeer(sender);
            if (peer == null) continue;   // не учасник матчу — викидаємо

            byte[] data = new byte[read];
            inbox.position(0);
            inbox.get(data, 0, read);

            Object message = codec.decode(data, read);
            if (message != null) dispatch(peer, message);
        }
    }

    /**
     * Розкласти прийняте.
     *
     * <p>Автора підставляє ПРИЙМАЧ за відправником пакета, а не поле в
     * повідомленні: інакше підміненим полем можна було б командувати чужою
     * армією. Те саме правило, що в хостовому транспорті KryoNet.
     */
    private void dispatch(Peer peer, Object message) {
        if (message instanceof TickCommands) {
            TickCommands commands = (TickCommands) message;
            commands.playerId = peer.playerId;
            // Ретрансляція чужих наказів теж надлишкова: у грі на трьох
            // втрата на ділянці «хост → інший гість» коштувала б рівно того
            // самого ривка, що й на першій.
            if (host && peers.size() > 1) relay(peer, commands);
            events.postCommands(commands);

        } else if (message instanceof TickChecksum) {
            TickChecksum checksum = (TickChecksum) message;
            checksum.playerId = peer.playerId;
            if (host && peers.size() > 1) relay(peer, checksum);
            events.postChecksum(checksum);

        } else if (message instanceof ResyncAck) {
            ResyncAck ack = (ResyncAck) message;
            ack.playerId = peer.playerId;
            if (host) events.postResyncAck(ack);

        } else if (!host) {
            // Решта — виключно хостові оголошення, і слухає їх лише гість.
            if (message instanceof DesyncAlert)         events.postDesyncAlert((DesyncAlert) message);
            else if (message instanceof ResyncSnapshot) events.postSnapshot((ResyncSnapshot) message);
            else if (message instanceof ResumeMatch)    events.postResume((ResumeMatch) message);
            else if (message instanceof PlayerDropped)  events.postPlayerDropped((PlayerDropped) message);
        }
    }

    /** Ретрансляція решті — тому, від кого прийшло, назад не шлемо. */
    private void relay(Peer from, Object message) {
        if (peers.size() < 2) return;
        byte[] data = codec.encode(message);
        if (data == null) return;
        for (Peer peer : peers) {
            if (peer.playerId == from.playerId) continue;
            send(peer, data, SteamNetworking.P2PSend.Reliable);
            if (data.length <= UNRELIABLE_LIMIT) {
                send(peer, data, SteamNetworking.P2PSend.UnreliableNoDelay);
            }
        }
    }

    private Peer findPeer(SteamID sender) {
        String key = SteamMatchmakingHub.toAddress(sender);
        for (Peer peer : peers) {
            if (peer.key.equals(key)) return peer;
        }
        return null;
    }

    private void ensureCapacity(int size) {
        if (inbox.capacity() >= size) return;
        // Ростемо з запасом: найбільше повідомлення матчу — знімок стану, і
        // перевиділяти буфер на кожен його байт немає сенсу.
        int capacity = Math.max(size, inbox.capacity() * 2);
        inbox = ByteBuffer.allocateDirect(Math.min(capacity, SteamPacketCodec.MAX_PACKET_BYTES));
    }

    // ── Колбеки Steam ─────────────────────────────────────────────────────

    @Override
    public void onP2PSessionConnectFail(SteamID steamIDRemote,
                                        SteamNetworking.P2PSessionError sessionError) {
        Peer peer = findPeer(steamIDRemote);
        if (peer == null) return;

        if (host) {
            // У хоста канал живий, зник лише один учасник. Що з цим робити і
            // з якого тіку — вирішує MatchRunner.
            events.postPlayerLost(peer.playerId);
        } else {
            events.postDisconnect("Зв'язок із хостом втрачено: " + sessionError);
        }
    }

    @Override
    public void onP2PSessionRequest(SteamID steamIDRemote) {
        // Приймаємо ТІЛЬКИ учасників матчу. На спільному AppID 480 сесію може
        // попросити будь-хто, а прийнята сесія — це відкритий канал у нашу
        // симуляцію.
        if (findPeer(steamIDRemote) == null) {
            SteamMatchmakingHub.log("відхилено P2P від стороннього "
                + SteamMatchmakingHub.toAddress(steamIDRemote));
            return;
        }
        networking.acceptP2PSessionWithUser(steamIDRemote);
    }
}
