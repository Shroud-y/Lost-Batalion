package io.jababa.lost_batalion.net.messages;

/**
 * Візитівка лоббі — те, що хост відповідає на широкомовний UDP-запит і що
 * клієнт показує у списку серверів.
 *
 * <p>Свідомо мала і без списку гравців: летить у одному UDP-датаграмі, який не
 * можна фрагментувати, тож тримаємо її в межах сотні байтів. Повний склад
 * гравців приходить уже після TCP-підключення, у {@link LobbyState}.
 *
 * <p>Адреса хоста тут не зберігається: її бере клієнт із конверта датаграми
 * (див. клієнтський клас-обгортку зі списку лоббі).
 */
public class LobbyInfo {

    /** Клієнт із іншою версією не показує це лоббі як придатне. */
    public int protocolVersion;

    public String lobbyName;
    public String hostNick;
    public int    playerCount;
    public int    maxPlayers;
    public LobbyStatus status;
    /** Id сценарію, обраного хостом (див. ScenarioCard.id). */
    public String scenarioId;

    public LobbyInfo() {}

    public LobbyInfo(int protocolVersion, String lobbyName, String hostNick,
                     int playerCount, int maxPlayers, LobbyStatus status, String scenarioId) {
        this.protocolVersion = protocolVersion;
        this.lobbyName   = lobbyName;
        this.hostNick    = hostNick;
        this.playerCount = playerCount;
        this.maxPlayers  = maxPlayers;
        this.status      = status;
        this.scenarioId  = scenarioId;
    }
}
