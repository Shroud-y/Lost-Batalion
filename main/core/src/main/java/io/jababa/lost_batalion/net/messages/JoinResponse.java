package io.jababa.lost_batalion.net.messages;

/** Хост → клієнт у відповідь на {@link JoinRequest}. */
public class JoinResponse {

    public boolean accepted;
    /** Причина відмови для показу гравцеві: лоббі повне, версія протоколу, зайнятий нік. */
    public String  reason;
    /** Присвоєний номер гравця; має сенс лише при accepted == true. */
    public int     assignedPlayerId;
    /** Одразу віддаємо повний стан, щоб клієнт не робив зайвий запит. */
    public LobbyState lobbyState;

    public JoinResponse() {}

    public static JoinResponse accept(int playerId, LobbyState state) {
        JoinResponse r = new JoinResponse();
        r.accepted = true;
        r.assignedPlayerId = playerId;
        r.lobbyState = state;
        return r;
    }

    public static JoinResponse reject(String reason) {
        JoinResponse r = new JoinResponse();
        r.accepted = false;
        r.reason   = reason;
        return r;
    }
}
