package io.jababa.lost_batalion.net.messages;

/** Один гравець у лоббі/матчі. */
public class PlayerSlot {

    /**
     * Номер гравця, 0..MAX_PLAYERS-1. Хост завжди 0.
     * Це той самий id, за яким симуляція визначає власника юніта, тож він не
     * змінюється від приєднання до кінця матчу.
     */
    public int playerId;

    public String  nick;
    public boolean ready;
    public boolean host;

    /** false, поки гравець у стані розриву зв'язку (слот ще тримається під перепідключення). */
    public boolean connected = true;

    public PlayerSlot() {}

    public PlayerSlot(int playerId, String nick, boolean host) {
        this.playerId = playerId;
        this.nick     = nick;
        this.host     = host;
        this.ready    = host;   // хост завжди «готовий», він натискає Старт
    }

    @Override
    public String toString() {
        return "#" + playerId + " " + nick + (host ? " (host)" : "") + (ready ? " [ready]" : "");
    }
}
