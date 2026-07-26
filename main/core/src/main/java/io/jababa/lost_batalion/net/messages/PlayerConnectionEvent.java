package io.jababa.lost_batalion.net.messages;

/**
 * Хост → усім: зміна стану з'єднання одного з гравців.
 *
 * <p>Свідомо окреме від {@link DesyncAlert}: «гравця вибило з мережі» і
 * «гравець рахує гру інакше» — різні проблеми з різними кнопками в UI, і
 * плутати їх в одному вікні означає плутати гравця.
 */
public class PlayerConnectionEvent {

    public int    playerId;
    public String nick;
    public ConnectionEventKind kind;

    /** Скільки мілісекунд лишилось до автоматичного дропу. Має сенс для DISCONNECTED. */
    public int graceMillisLeft;

    public PlayerConnectionEvent() {}

    public PlayerConnectionEvent(int playerId, String nick, ConnectionEventKind kind) {
        this.playerId = playerId;
        this.nick     = nick;
        this.kind     = kind;
    }
}
