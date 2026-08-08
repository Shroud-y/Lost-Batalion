package io.jababa.lost_batalion.net.kryo;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
import io.jababa.lost_batalion.ai.BotPlayer;
import io.jababa.lost_batalion.net.NetLog;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.messages.DesyncAlert;
import io.jababa.lost_batalion.net.messages.PlayerDropped;
import io.jababa.lost_batalion.net.messages.ResumeMatch;
import io.jababa.lost_batalion.net.messages.ResyncAck;
import io.jababa.lost_batalion.net.messages.ResyncSnapshot;
import io.jababa.lost_batalion.net.messages.TickChecksum;
import io.jababa.lost_batalion.net.messages.TickCommands;

import java.util.Map;

/**
 * Канал матчу з боку хоста: приймає накази гостей і ретранслює їх усім.
 *
 * <p>Зірка, а не «кожен кожному»: усі команди проходять через хоста. Це дає
 * єдиний порядок доставки й одне місце, де можна перевірити авторство —
 * ціною одного зайвого перескоку затримки. Для 1v1 у локальній мережі це
 * дрібниця, а от розбіжність у порядку команд коштувала б десинхроном.
 *
 * <p>Свої власні накази хост не відправляє собі по мережі, а кладе в чергу
 * напряму — але тим самим шляхом і в той самий момент, що й ретрансльовані,
 * тож послідовність не залежить від того, хто автор.
 */
public class HostMatchTransport implements MatchTransport {

    private static final int HOST_PLAYER_ID = 0;

    private final MatchEventQueue events = new MatchEventQueue();
    private final Server server;
    private final int[]  playerIds;

    /** connectionId → playerId, зафіксовано на момент старту матчу. */
    private final Map<Integer, Integer> connectionToPlayer;

    /** Закриття всієї сесії — транспорт не володіє сервером, лише користується ним. */
    private final Runnable closer;

    private volatile boolean open = true;

    public HostMatchTransport(Server server, int[] playerIds,
                              Map<Integer, Integer> connectionToPlayer, Runnable closer) {
        this.server             = server;
        this.playerIds          = playerIds;
        this.connectionToPlayer = connectionToPlayer;
        this.closer             = closer;

        server.addListener(new MatchListener());
    }

    /**
     * Розібрати повідомлення матчу, що прийшли ДО створення цього каналу.
     *
     * <p>У хоста вікно вужче, ніж у гостя, але воно є: між
     * {@code server.sendToAllTCP(start)} і створенням цього транспорту минає
     * кадр, і швидкий гість устигає відповісти розігрівом. Пакет приходить у
     * серверне з'єднання, слухача матчу ще немає — і він гине мовчки, а матч
     * потім стоїть, чекаючи наказів, які насправді вже приходили.
     *
     * @param connectionIds номери з'єднань, паралельний {@code objects}: автора
     *                      хост визначає ЗА З'ЄДНАННЯМ, а не за полем у пакеті,
     *                      тож відкладене повідомлення без нього нічого не варте
     */
    public void replay(java.util.List<Integer> connectionIds, java.util.List<Object> objects) {
        if (connectionIds == null || objects == null) return;
        int n = Math.min(connectionIds.size(), objects.size());
        for (int i = 0; i < n; i++) handle(connectionIds.get(i), objects.get(i));
    }

    private void handle(int connectionId, Object object) {
        if (!open) return;

        Integer owner = connectionToPlayer.get(connectionId);
        if (owner == null) return;   // з'єднання не з учасника матчу

        if (object instanceof TickCommands) {
            TickCommands message = (TickCommands) object;
            // Автора визначає хост за з'єднанням, а не поле в пакеті: інакше
            // підмінене повідомлення дало б командувати чужою армією.
            message.playerId = owner;

            // Розсилається всім, включно з автором: наказ мусить виконатись у
            // нього рівно тоді ж, коли в решти, а не раніше.
            server.sendToAllTCP(message);
            events.postCommands(message);

        } else if (object instanceof TickChecksum) {
            TickChecksum message = (TickChecksum) object;
            message.playerId = owner;
            // Хеші теж ретранслюються всім: так кожен клієнт бачить
            // розбіжність сам і не залежить від того, чи встиг хост
            // оголосити її, поки з'єднання ще живе.
            server.sendToAllTCP(message);
            events.postChecksum(message);

        } else if (object instanceof ResyncAck) {
            ResyncAck ack = (ResyncAck) object;
            ack.playerId = owner;
            events.postResyncAck(ack);
        }
    }

    @Override public int getLocalPlayerId() { return HOST_PLAYER_ID; }
    @Override public int[] getPlayerIds()   { return playerIds; }
    @Override public boolean isHost()       { return true; }
    @Override public boolean isConnected()  { return open; }

    /**
     * Боти, за яких відповідає хост. Порожньо, поки їх не дали — і в матчі без
     * ботів цей масив лишається порожнім назавжди.
     */
    private BotPlayer[] bots = NO_BOTS;

    private static final BotPlayer[] NO_BOTS = new BotPlayer[0];

    @Override
    public void setBots(BotPlayer[] next) {
        this.bots = next == null ? NO_BOTS : next;
    }

    /**
     * Накази хоста — усім, і РАЗОМ із ними накази кожного бота на той самий тік.
     *
     * <p>Гість не відрізняє їх від людських і не мусить: у нього це просто ще
     * один {@code playerId}, від якого щотіку приходить повідомлення. Саме тому
     * етап не потребував ані нового типу повідомлення, ані версії протоколу.
     */
    @Override
    public void sendCommands(TickCommands commands) {
        if (!open) return;
        commands.playerId = HOST_PLAYER_ID;
        server.sendToAllTCP(commands);
        events.postCommands(commands);

        for (int i = 0; i < bots.length; i++) {
            // Тік і покоління беруться з повідомлення хоста, а не рахуються
            // окремо: власний лічильник розійшовся б із ресинком, який скидає
            // покоління й починає нумерацію тіків розігріву заново.
            TickCommands botCommands = bots[i].commandsFor(commands.tick, commands.generation);
            server.sendToAllTCP(botCommands);
            events.postCommands(botCommands);
        }
    }

    @Override
    public void sendChecksum(TickChecksum checksum) {
        if (!open) return;
        checksum.playerId = HOST_PLAYER_ID;
        server.sendToAllTCP(checksum);
        events.postChecksum(checksum);
    }

    @Override
    public void sendDesyncAlert(DesyncAlert alert) {
        if (!open) return;
        server.sendToAllTCP(alert);
        events.postDesyncAlert(alert);
    }

    @Override
    public void sendResyncSnapshot(ResyncSnapshot snapshot) {
        if (!open) return;
        // Хост собі знімок не застосовує — він і є його джерелом.
        server.sendToAllTCP(snapshot);
    }

    /** Хост підтверджує сам собі: свій стан він уже має. */
    @Override
    public void sendResyncAck(ResyncAck ack) {
        if (open) events.postResyncAck(ack);
    }

    @Override
    public void sendResumeMatch(ResumeMatch resume) {
        if (!open) return;
        server.sendToAllTCP(resume);
        events.postResume(resume);
    }

    @Override
    public void sendPlayerDropped(PlayerDropped dropped) {
        if (!open) return;
        server.sendToAllTCP(dropped);
        events.postPlayerDropped(dropped);
    }

    @Override public void pump(Listener listener) { events.drain(listener); }

    @Override
    public void close() {
        open = false;
        events.clear();
        if (closer != null) closer.run();
    }

    private final class MatchListener extends com.esotericsoftware.kryonet.Listener {

        @Override
        public void received(Connection connection, Object object) {
            handle(connection.getID(), object);
        }

        @Override
        public void disconnected(Connection connection) {
            if (!open) return;
            Integer owner = connectionToPlayer.get(connection.getID());
            if (owner == null) return;
            NetLog.error("Гравець " + owner + " відпав під час матчу.");
            // Не onDisconnected: у хоста канал живий, зник лише один учасник.
            // Що з цим робити і з якого тіку — вирішує MatchRunner.
            events.postPlayerLost(owner);
        }
    }
}
