package io.jababa.lost_batalion.net.api;

import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.discovery.LobbyScanner;
import io.jababa.lost_batalion.net.kryo.ClientLobbySession;
import io.jababa.lost_batalion.net.kryo.HostLobbySession;

/**
 * Гра в локальній мережі: KryoNet по TCP плюс широкомовний UDP-пошук лоббі.
 *
 * <p>Вбудований і завжди доступний — жодних зовнішніх сервісів йому не треба,
 * тож він же і запасний варіант, коли Steam не піднявся. Тіла методів
 * переїхали сюди з {@link MultiplayerServices} без змін.
 */
public final class LanBackend implements NetBackend {

    public static final String ID = "lan";

    @Override public String  id()             { return ID; }
    @Override public String  label()          { return "ЛОКАЛЬНА МЕРЕЖА"; }
    @Override public boolean isAvailable()    { return true; }
    @Override public String  unavailableReason() { return null; }
    @Override public String  addressHint()    { return "192.168.0.5"; }

    /** Нік у локальній мережі взяти нізвідки — його вводить гравець. */
    @Override public String  defaultNick()    { return ""; }

    @Override
    public String emptyListHint() {
        return "Лоббі не знайдено. Хост має бути в тій самій локальній мережі —\n"
             + "інакше підключайся напряму за IP.";
    }

    /** KryoNet крутить власні потоки; кадрового такту йому не потрібно. */
    @Override public void    pump()           { }

    @Override
    public LobbyDirectory createDirectory() {
        return new LobbyScanner();
    }

    @Override
    public LobbySession host(String lobbyName, String nick, String scenarioId, int maxPlayers) {
        return new HostLobbySession(lobbyName, nick, scenarioId, maxPlayers);
    }

    @Override
    public LobbySession join(DiscoveredLobby lobby, String nick) {
        return new ClientLobbySession(lobby.address, NetConfig.TCP_PORT, nick);
    }

    @Override
    public LobbySession joinByAddress(String address, String nick) {
        return new ClientLobbySession(
            MultiplayerServices.parseHost(address),
            MultiplayerServices.parsePort(address),
            nick);
    }
}
