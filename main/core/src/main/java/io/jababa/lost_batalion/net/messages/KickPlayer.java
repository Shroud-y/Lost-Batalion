package io.jababa.lost_batalion.net.messages;

/**
 * Тільки хост: виключити гравця з лоббі.
 *
 * <p>Летить у два боки: хост шле це самому вигнаному (щоб той показав причину,
 * а не просто «зв'язок втрачено»), після чого закриває з'єднання. Решта
 * дізнається зі звичайного {@link LobbyState}.
 *
 * <p>Не плутати з {@code PlayerDropped}: те — про матч, який уже йде, і несе
 * тік, з якого гравця немає в симуляції. Тут симуляції ще немає.
 */
public class KickPlayer {

    public int    playerId;
    public String reason;

    public KickPlayer() {}

    public KickPlayer(int playerId, String reason) {
        this.playerId = playerId;
        this.reason   = reason;
    }
}
