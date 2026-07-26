package io.jababa.lost_batalion.net.kryo;

import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.messages.DesyncAlert;
import io.jababa.lost_batalion.net.messages.PlayerDropped;
import io.jababa.lost_batalion.net.messages.ResumeMatch;
import io.jababa.lost_batalion.net.messages.ResyncAck;
import io.jababa.lost_batalion.net.messages.ResyncSnapshot;
import io.jababa.lost_batalion.net.messages.TickChecksum;
import io.jababa.lost_batalion.net.messages.TickCommands;

/**
 * Канал матчу для одиночної гри: власні накази одразу повертаються назад.
 *
 * <p>Завдяки цьому одиночна гра йде тим самим lockstep-циклом, що й мережева —
 * з тим самим input delay і тим самим порядком виконання. Це не формальність:
 * гілка «а в одиночці зробимо простіше» означала б, що більшість годин гри
 * проходить по коду, який у матчі не працює, і баги вилазять лише вдвох.
 */
public class LocalMatchTransport implements MatchTransport {

    private static final int[] SOLO = { 0 };

    private final MatchEventQueue events = new MatchEventQueue();
    private boolean open = true;

    @Override public int getLocalPlayerId() { return 0; }
    @Override public int[] getPlayerIds()   { return SOLO; }
    @Override public boolean isHost()       { return true; }

    @Override
    public void sendCommands(TickCommands commands) {
        if (open) events.postCommands(commands);
    }

    /**
     * Свій хеш повертається собі ж. Порівняння з самим собою завжди сходиться —
     * і це не марна робота: одиночна гра ганяє той самий код, що й матч, тож
     * поламаний checksum помітно ще до того, як хтось спробує зіграти вдвох.
     */
    @Override
    public void sendChecksum(TickChecksum checksum) {
        if (open) events.postChecksum(checksum);
    }

    /** Оголошувати десинхрон самому собі немає кому. */
    @Override public void sendDesyncAlert(DesyncAlert alert) { }

    // Ресинк в одиночній грі не має сенсу: розходитись нема з ким. Але канал
    // мусить бути замкненим і тут, інакше «продовжити» ніколи не спрацює,
    // якщо хтось таки викличе ресинк локально.
    @Override public void sendResyncSnapshot(ResyncSnapshot snapshot) {
        if (open) events.postSnapshot(snapshot);
    }
    @Override public void sendResyncAck(ResyncAck ack) {
        if (open) events.postResyncAck(ack);
    }
    @Override public void sendResumeMatch(ResumeMatch resume) {
        if (open) events.postResume(resume);
    }

    /** В одиночній грі вибувати нікому. */
    @Override public void sendPlayerDropped(PlayerDropped dropped) { }

    @Override public void pump(Listener listener) { events.drain(listener); }
    @Override public boolean isConnected()        { return open; }

    @Override
    public void close() {
        open = false;
        events.clear();
    }
}
