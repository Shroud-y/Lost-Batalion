package io.jababa.lost_batalion.net.kryo;

import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.messages.DesyncAlert;
import io.jababa.lost_batalion.net.messages.PlayerDropped;
import io.jababa.lost_batalion.net.messages.ResumeMatch;
import io.jababa.lost_batalion.net.messages.ResyncAck;
import io.jababa.lost_batalion.net.messages.ResyncSnapshot;
import io.jababa.lost_batalion.net.messages.TickChecksum;
import io.jababa.lost_batalion.net.messages.TickCommands;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Міст між потоком KryoNet і потоком рендеру для трафіку матчу.
 *
 * <p>Те саме, що {@link SessionEventQueue} робить для лоббі, але для трафіку
 * матчу: він приходить у мережевому потоці, а розкладати його по тіках і
 * виконувати можна лише там, де живе симуляція. Окрема черга, а не спільна з
 * лоббі, бо під час матчу подій на два порядки більше — 40 повідомлень на
 * секунду від кожного гравця, — і змішувати їх із рідкісними подіями складу
 * нема сенсу.
 *
 * <p>Черга ОДНА на всі види повідомлень навмисно: накази і хеші мусять
 * доставлятись у тому ж порядку, у якому прийшли. Дві черги дозволили б хешу
 * тіку 40 обігнати накази того ж тіку, і звірка порахувала б розбіжність там,
 * де її насправді немає.
 */
public class MatchEventQueue {

    private final ConcurrentLinkedQueue<Object> inbox = new ConcurrentLinkedQueue<>();
    private volatile String disconnectReason;
    private volatile boolean disconnectDelivered;

    public void postCommands(TickCommands message)  { inbox.add(message); }
    public void postChecksum(TickChecksum message)  { inbox.add(message); }
    public void postDesyncAlert(DesyncAlert alert)  { inbox.add(alert); }
    public void postSnapshot(ResyncSnapshot snap)   { inbox.add(snap); }
    public void postResyncAck(ResyncAck ack)        { inbox.add(ack); }
    public void postResume(ResumeMatch resume)      { inbox.add(resume); }
    public void postPlayerDropped(PlayerDropped d)  { inbox.add(d); }

    /** Обгортка, щоб «зник гравець» їхало тією ж чергою, що й решта подій. */
    private static final class PlayerLost {
        final int playerId;
        PlayerLost(int playerId) { this.playerId = playerId; }
    }

    public void postPlayerLost(int playerId) { inbox.add(new PlayerLost(playerId)); }

    public void postDisconnect(String reason) {
        if (disconnectReason == null) disconnectReason = reason;
    }

    /** Викликається з потоку рендеру. */
    public void drain(MatchTransport.Listener listener) {
        Object message;
        while ((message = inbox.poll()) != null) {
            if (message instanceof TickCommands)      listener.onTickCommands((TickCommands) message);
            else if (message instanceof TickChecksum) listener.onChecksum((TickChecksum) message);
            else if (message instanceof DesyncAlert)  listener.onDesyncAlert((DesyncAlert) message);
            else if (message instanceof ResyncSnapshot) listener.onResyncSnapshot((ResyncSnapshot) message);
            else if (message instanceof ResyncAck)      listener.onResyncAck((ResyncAck) message);
            else if (message instanceof ResumeMatch)    listener.onResumeMatch((ResumeMatch) message);
            else if (message instanceof PlayerDropped)  listener.onPlayerDropped((PlayerDropped) message);
            else if (message instanceof PlayerLost)     listener.onPlayerLost(((PlayerLost) message).playerId);
        }

        // Розрив доставляється після всього прийнятого: команди, що вже
        // долетіли, лишаються чинними — на них ще можна дограти кілька тіків.
        if (disconnectReason != null && !disconnectDelivered) {
            disconnectDelivered = true;
            listener.onDisconnected(disconnectReason);
        }
    }

    public void clear() { inbox.clear(); }
}
