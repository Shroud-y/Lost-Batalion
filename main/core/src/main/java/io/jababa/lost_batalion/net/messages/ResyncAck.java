package io.jababa.lost_batalion.net.messages;

/**
 * Клієнт → хост: знімок застосовано.
 *
 * <p>Хост знімає паузу лише коли підтвердили всі — інакше той, хто ще
 * розпаковує знімок, відстане і десинхрон повториться одразу ж.
 */
public class ResyncAck {

    public int     playerId;
    public int     tick;
    /** false, якщо знімок не розпакувався або хеш не зійшовся. */
    public boolean success;

    public ResyncAck() {}

    public ResyncAck(int playerId, int tick, boolean success) {
        this.playerId = playerId;
        this.tick     = tick;
        this.success  = success;
    }
}
