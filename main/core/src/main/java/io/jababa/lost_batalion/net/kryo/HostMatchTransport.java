package io.jababa.lost_batalion.net.kryo;

import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Server;
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

    @Override public int getLocalPlayerId() { return HOST_PLAYER_ID; }
    @Override public int[] getPlayerIds()   { return playerIds; }
    @Override public boolean isHost()       { return true; }
    @Override public boolean isConnected()  { return open; }

    @Override
    public void sendCommands(TickCommands commands) {
        if (!open) return;
        commands.playerId = HOST_PLAYER_ID;
        server.sendToAllTCP(commands);
        events.postCommands(commands);
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
            if (!open) return;

            Integer owner = connectionToPlayer.get(connection.getID());
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
