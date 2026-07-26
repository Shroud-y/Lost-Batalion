package io.jababa.lost_batalion.net.messages;

/**
 * Клієнт → хост: вихід із лоббі кнопкою «Назад».
 *
 * <p>Потрібне окремо від обриву TCP: свідомий вихід звільняє слот одразу,
 * тоді як обрив тримає слот під перепідключення (див. RECONNECT_GRACE_MILLIS).
 */
public class LeaveLobby {
    public LeaveLobby() {}
}
