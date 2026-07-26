package io.jababa.lost_batalion.net.messages;

/** Стан лоббі, який бачать інші гравці у списку серверів. */
public enum LobbyStatus {
    /** Чекає на гравців, приєднатись можна. */
    WAITING,
    /** Усі готові, хост запускає матч — нові підключення вже не приймаються. */
    STARTING,
    /** Матч триває. */
    IN_GAME
}
