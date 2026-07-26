package io.jababa.lost_batalion.net.api;

import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.discovery.LobbyScanner;
import io.jababa.lost_batalion.net.kryo.ClientLobbySession;
import io.jababa.lost_batalion.net.kryo.HostLobbySession;

/**
 * Точка входу в мережу для екранів.
 *
 * <p>Екрани не знають ні про KryoNet, ні про сокети — вони просять тут сесію і
 * далі працюють з {@link LobbySession}. Завдяки цьому мережевий шар можна
 * підмінити (наприклад, на реєстр замість широкомовного пошуку) без правок в UI.
 */
public final class MultiplayerServices {

    private MultiplayerServices() {}

    public static boolean isNetworkingAvailable() { return true; }

    public static LobbyDirectory createDirectory() {
        return new LobbyScanner();
    }

    /** Створити лоббі і стати його хостом. */
    public static LobbySession host(String lobbyName, String nick, String scenarioId, int maxPlayers) {
        return new HostLobbySession(lobbyName, nick, scenarioId, maxPlayers);
    }

    /** Приєднатись до знайденого в мережі лоббі. */
    public static LobbySession join(DiscoveredLobby lobby, String nick) {
        return new ClientLobbySession(lobby.address, NetConfig.TCP_PORT, nick);
    }

    /** Приєднатись за введеною вручну адресою. Порт можна дописати через двокрапку. */
    public static LobbySession joinByAddress(String address, String nick) {
        return new ClientLobbySession(parseHost(address), parsePort(address), nick);
    }

    /** Розбирає "192.168.0.5:54555" на хост і порт; без порту бере дефолтний. */
    public static String parseHost(String address) {
        int colon = address.lastIndexOf(':');
        return colon < 0 ? address.trim() : address.substring(0, colon).trim();
    }

    public static int parsePort(String address) {
        int colon = address.lastIndexOf(':');
        if (colon < 0) return NetConfig.TCP_PORT;
        try {
            return Integer.parseInt(address.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return NetConfig.TCP_PORT;
        }
    }
}
