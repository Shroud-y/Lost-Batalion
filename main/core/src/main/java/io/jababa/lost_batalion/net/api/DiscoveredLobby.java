package io.jababa.lost_batalion.net.api;

import io.jababa.lost_batalion.net.messages.LobbyInfo;

/**
 * Лоббі, знайдене в мережі, разом із тим, звідки воно прийшло.
 *
 * <p>Адреси немає в самому {@link LobbyInfo}, бо хост її не знає: за NAT він
 * бачить свій локальний інтерфейс, а не той, за яким до нього достукались.
 * Тому адресу підставляє приймач із конверта датаграми.
 */
public class DiscoveredLobby {

    public final LobbyInfo info;
    /** IP хоста у вигляді, придатному для {@code Client.connect}. */
    public final String address;
    /**
     * Коли востаннє відповіло. Лоббі, від якого давно тиша, зникає зі списку —
     * інакше в списку назавжди висів би сервер, який хост уже закрив.
     */
    public long lastSeenMillis;

    public DiscoveredLobby(LobbyInfo info, String address, long lastSeenMillis) {
        this.info    = info;
        this.address = address;
        this.lastSeenMillis = lastSeenMillis;
    }

    public boolean isJoinable(int localProtocolVersion) {
        return info != null
            && info.protocolVersion == localProtocolVersion
            && info.status == io.jababa.lost_batalion.net.messages.LobbyStatus.WAITING
            && info.playerCount < info.maxPlayers;
    }

    /** Причина, чому приєднатись не можна — показується у списку сірим. */
    public String unjoinableReason(int localProtocolVersion) {
        if (info == null) return "невідоме лоббі";
        if (info.protocolVersion != localProtocolVersion) return "інша версія гри";
        if (info.status != io.jababa.lost_batalion.net.messages.LobbyStatus.WAITING) return "матч уже почався";
        if (info.playerCount >= info.maxPlayers) return "немає місць";
        return null;
    }
}
