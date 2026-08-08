package io.jababa.lost_batalion.net.kryo;

import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.messages.DesyncAlert;
import io.jababa.lost_batalion.net.messages.PlayerDropped;
import io.jababa.lost_batalion.net.messages.ResumeMatch;
import io.jababa.lost_batalion.net.messages.ResyncAck;
import io.jababa.lost_batalion.net.messages.ResyncSnapshot;
import io.jababa.lost_batalion.net.messages.TickChecksum;
import io.jababa.lost_batalion.net.messages.TickCommands;

/**
 * Канал матчу з боку гостя: свої накази — хосту, чужі — з ретрансляції.
 *
 * <p>Власні накази теж приходять назад від хоста, і саме та копія потрапляє в
 * буфер. Локальне «я ж знаю, що натиснув» тут навмисно не використовується:
 * якби гість застосував наказ раніше за ретрансляцію, його гра пішла б попереду
 * хостової.
 */
public class ClientMatchTransport implements MatchTransport {

    private final MatchEventQueue events = new MatchEventQueue();
    private final Client client;
    private final int    localPlayerId;
    private final int[]  playerIds;
    private final Runnable closer;

    private volatile boolean open = true;

    public ClientMatchTransport(Client client, int localPlayerId, int[] playerIds, Runnable closer) {
        this.client        = client;
        this.localPlayerId = localPlayerId;
        this.playerIds     = playerIds;
        this.closer        = closer;

        client.addListener(new MatchListener());
    }

    /**
     * Розібрати повідомлення матчу, що прийшли ДО створення цього каналу.
     *
     * <p>Між тим, як лоббі-сесія побачила {@code StartMatch}, і тим, як екран
     * лоббі створив цей транспорт, минає щонайменше кадр — а хост за цей час уже
     * міг надіслати свій розігрів. Ті пакети приходять у те саме з'єднання, але
     * слухача матчу ще немає, і вони гинуть мовчки: гість чекає на тіки, яких
     * більше ніхто не надішле, і матч не починається НІКОЛИ. Тому лоббі-сесія
     * складає їх убік, а тут вони доганяють чергу — у тому ж порядку, у якому
     * прийшли по TCP.
     *
     * <p>Дублікат тут нешкідливий: {@code CommandBuffer.receive} бере лише перше
     * повідомлення на пару (тік, гравець), а книга хешів — {@code putIfAbsent}.
     */
    public void replay(java.util.List<Object> backlog) {
        if (backlog == null) return;
        for (int i = 0; i < backlog.size(); i++) dispatch(backlog.get(i));
    }

    private void dispatch(Object object) {
        if (object instanceof TickCommands)      events.postCommands((TickCommands) object);
        else if (object instanceof TickChecksum) events.postChecksum((TickChecksum) object);
        else if (object instanceof DesyncAlert)  events.postDesyncAlert((DesyncAlert) object);
        else if (object instanceof ResyncSnapshot) events.postSnapshot((ResyncSnapshot) object);
        else if (object instanceof ResumeMatch)    events.postResume((ResumeMatch) object);
        else if (object instanceof PlayerDropped)  events.postPlayerDropped((PlayerDropped) object);
    }

    @Override public int getLocalPlayerId() { return localPlayerId; }
    @Override public int[] getPlayerIds()   { return playerIds; }
    @Override public boolean isHost()       { return false; }
    @Override public boolean isConnected()  { return open; }

    @Override
    public void sendCommands(TickCommands commands) {
        if (!open) return;
        commands.playerId = localPlayerId;
        try {
            client.sendTCP(commands);
        } catch (Exception e) {
            events.postDisconnect("Зв'язок із хостом втрачено: " + e.getMessage());
        }
    }

    @Override
    public void sendChecksum(TickChecksum checksum) {
        if (!open) return;
        checksum.playerId = localPlayerId;
        try {
            client.sendTCP(checksum);
        } catch (Exception e) {
            events.postDisconnect("Зв'язок із хостом втрачено: " + e.getMessage());
        }
    }

    /** Оголошення десинхрону, роздача знімка і дозвіл продовжити — справа хоста. */
    @Override public void sendDesyncAlert(DesyncAlert alert)         { }
    @Override public void sendResyncSnapshot(ResyncSnapshot snapshot) { }
    @Override public void sendResumeMatch(ResumeMatch resume)         { }
    @Override public void sendPlayerDropped(PlayerDropped dropped)    { }

    @Override
    public void sendResyncAck(ResyncAck ack) {
        if (!open) return;
        ack.playerId = localPlayerId;
        try {
            client.sendTCP(ack);
        } catch (Exception e) {
            events.postDisconnect("Зв'язок із хостом втрачено: " + e.getMessage());
        }
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
            dispatch(object);
        }

        @Override
        public void disconnected(Connection connection) {
            if (!open) return;
            events.postDisconnect("Зв'язок із хостом втрачено.");
        }
    }
}
