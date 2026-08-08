package io.jababa.lost_batalion.net.messages;

/**
 * Тільки хост: посадити бота на місце.
 *
 * <p>Бот отримує звичайний playerId і звичайний слот — для lockstep він
 * такий самий учасник, як людина, просто його накази рахує хост. Рівень —
 * іменем константи {@code Difficulty}; див. {@link PlayerSlot#botDifficulty}.
 */
public class AddBot {

    public int    team;
    public int    seat;
    public String difficulty;

    public AddBot() {}

    public AddBot(int team, int seat, String difficulty) {
        this.team       = team;
        this.seat       = seat;
        this.difficulty = difficulty;
    }
}
