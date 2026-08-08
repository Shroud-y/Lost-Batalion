package io.jababa.lost_batalion.net.messages;

/** Тільки хост: прибрати бота з лоббі. */
public class RemoveBot {

    public int playerId;

    public RemoveBot() {}

    public RemoveBot(int playerId) { this.playerId = playerId; }
}
