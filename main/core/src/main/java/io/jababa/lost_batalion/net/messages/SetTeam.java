package io.jababa.lost_batalion.net.messages;

/**
 * Клієнт → хост: хочу сісти на це місце.
 *
 * <p>Саме «хочу», а не «сів»: місце міг щойно зайняти інший або закрити хост,
 * і рішення лишається за хостом — клієнт побачить результат наступним
 * {@link LobbyState}. Хост шле це повідомлення сам собі не по мережі, а
 * викликом методу.
 *
 * <p>{@code team == PlayerSlot.TEAM_NONE} означає «назад у список очікування».
 */
public class SetTeam {

    public int team;
    public int seat;

    public SetTeam() {}

    public SetTeam(int team, int seat) {
        this.team = team;
        this.seat = seat;
    }
}
