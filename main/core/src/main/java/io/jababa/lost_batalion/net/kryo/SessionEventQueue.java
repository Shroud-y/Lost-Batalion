package io.jababa.lost_batalion.net.kryo;

import io.jababa.lost_batalion.net.api.LobbySession;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Міст між потоком KryoNet і потоком рендеру.
 *
 * <p>KryoNet викликає слухачі у власному потоці, а віджети libGDX можна чіпати
 * лише з того потоку, де живе GL-контекст. Тому мережеві події не виконуються
 * одразу — вони складаються сюди, а екран забирає їх у своєму {@code render}.
 *
 * <p>Без цього кожне приєднання гравця означало б перебудову списку віджетів із
 * чужого потоку: воно навіть працює через раз, і саме тому такі краші
 * найдовше ловляться.
 */
public class SessionEventQueue {

    /** Одна мережева подія, готова до доставки слухачеві. */
    public interface Event {
        void deliver(LobbySession.Listener listener);
    }

    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();

    public void post(Event event) { queue.add(event); }

    /** Викликається з потоку рендеру. */
    public void drain(LobbySession.Listener listener) {
        Event event;
        while ((event = queue.poll()) != null) {
            event.deliver(listener);
        }
    }

    public void clear() { queue.clear(); }
}
