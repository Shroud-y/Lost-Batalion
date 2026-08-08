package io.jababa.lost_batalion.net.messages;

/**
 * Тільки хост: закрити або відкрити місце.
 *
 * <p>Закрите місце — не те саме, що зайняте: у нього не можна сісти, і воно
 * лишається закритим, коли сусід виходить. Так хост робить матч 2 на 2 в
 * лоббі на п'ятьох, не покладаючись на те, що зайві просто не прийдуть.
 */
public class SetSlotClosed {

    public int     team;
    public int     seat;
    public boolean closed;

    public SetSlotClosed() {}

    public SetSlotClosed(int team, int seat, boolean closed) {
        this.team   = team;
        this.seat   = seat;
        this.closed = closed;
    }
}
