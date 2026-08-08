package io.jababa.lost_batalion.net.messages;

/** Тільки хост: змінити рівень бота, який уже сидить у лоббі. */
public class SetBotDifficulty {

    public int    playerId;
    public String difficulty;

    public SetBotDifficulty() {}

    public SetBotDifficulty(int playerId, String difficulty) {
        this.playerId   = playerId;
        this.difficulty = difficulty;
    }
}
