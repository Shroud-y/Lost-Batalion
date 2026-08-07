package io.jababa.lost_batalion.steam;

import com.badlogic.gdx.Gdx;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Один на процес {@link SteamMatchmaking} і розсилка його подій.
 *
 * <p>Чому один: інтерфейс Steamworks створюється РАЗОМ із колбеком, а слухачів
 * у нас двоє одночасно — список лоббі й сама сесія лоббі. Два незалежні
 * {@code SteamMatchmaking} означали б два реєстри колбеків на ту саму подію, і
 * порядок їх виклику ніде не визначений. Тут натомість один інтерфейс і явний
 * список слухачів.
 *
 * <p>Потоки: усі колбеки Steamworks приходять зсередини
 * {@code SteamAPI.runCallbacks()}, тобто з потоку рендеру. Синхронізація не
 * потрібна — на відміну від KryoNet, де під це зроблені цілі черги подій.
 *
 * <p>Слухачі — {@link SteamMatchmakingCallback} із самими лише потрібними
 * методами: усі методи цього інтерфейсу мають реалізацію за замовчуванням.
 */
public final class SteamMatchmakingHub {

    private static SteamMatchmakingHub instance;

    private final SteamMatchmaking matchmaking;
    private final List<SteamMatchmakingCallback> listeners = new ArrayList<>();

    private SteamMatchmakingHub() {
        matchmaking = new SteamMatchmaking(new Dispatcher());
    }

    /** @return хаб або {@code null}, якщо Steamworks не піднявся */
    public static synchronized SteamMatchmakingHub get() {
        if (!SteamBoot.ensureBooted()) return null;
        if (instance == null) instance = new SteamMatchmakingHub();
        return instance;
    }

    public SteamMatchmaking api() { return matchmaking; }

    public void addListener(SteamMatchmakingCallback listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(SteamMatchmakingCallback listener) {
        listeners.remove(listener);
    }

    // ── Дрібні зручності ──────────────────────────────────────────────────

    /** Метадані лоббі; ніколи не {@code null} — відсутній ключ дає порожній рядок. */
    public String getData(SteamID lobby, String key) {
        String value = matchmaking.getLobbyData(lobby, key);
        return value == null ? "" : value;
    }

    /** SteamID64 у вигляді рядка — саме він їде в {@code DiscoveredLobby.address}. */
    public static String toAddress(SteamID id) {
        return Long.toUnsignedString(SteamNativeHandle.getNativeHandle(id));
    }

    /** Зворотне перетворення. {@code null}, якщо рядок не SteamID64. */
    public static SteamID fromAddress(String address) {
        if (address == null) return null;
        try {
            return SteamID.createFromNativeHandle(Long.parseUnsignedLong(address.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Розсилка ──────────────────────────────────────────────────────────

    /**
     * Копія списку на кожній події навмисно: слухач цілком може зняти себе
     * прямо з обробника (список лоббі — при закритті екрана), а це
     * {@code ConcurrentModificationException} посеред колбека Steamworks.
     */
    private List<SteamMatchmakingCallback> snapshot() {
        return new ArrayList<>(listeners);
    }

    private final class Dispatcher implements SteamMatchmakingCallback {

        @Override
        public void onLobbyMatchList(int lobbiesMatching) {
            for (SteamMatchmakingCallback l : snapshot()) l.onLobbyMatchList(lobbiesMatching);
        }

        @Override
        public void onLobbyCreated(SteamResult result, SteamID steamIDLobby) {
            for (SteamMatchmakingCallback l : snapshot()) l.onLobbyCreated(result, steamIDLobby);
        }

        @Override
        public void onLobbyEnter(SteamID steamIDLobby, int chatPermissions, boolean blocked,
                                 SteamMatchmaking.ChatRoomEnterResponse response) {
            for (SteamMatchmakingCallback l : snapshot()) {
                l.onLobbyEnter(steamIDLobby, chatPermissions, blocked, response);
            }
        }

        @Override
        public void onLobbyDataUpdate(SteamID steamIDLobby, SteamID steamIDMember, boolean success) {
            for (SteamMatchmakingCallback l : snapshot()) {
                l.onLobbyDataUpdate(steamIDLobby, steamIDMember, success);
            }
        }

        @Override
        public void onLobbyChatUpdate(SteamID steamIDLobby, SteamID steamIDUserChanged,
                                      SteamID steamIDMakingChange,
                                      SteamMatchmaking.ChatMemberStateChange stateChange) {
            for (SteamMatchmakingCallback l : snapshot()) {
                l.onLobbyChatUpdate(steamIDLobby, steamIDUserChanged, steamIDMakingChange, stateChange);
            }
        }

        @Override
        public void onLobbyChatMessage(SteamID steamIDLobby, SteamID steamIDUser,
                                       SteamMatchmaking.ChatEntryType entryType, int chatID) {
            for (SteamMatchmakingCallback l : snapshot()) {
                l.onLobbyChatMessage(steamIDLobby, steamIDUser, entryType, chatID);
            }
        }

        @Override
        public void onLobbyInvite(SteamID steamIDUser, SteamID steamIDLobby, long gameID) {
            for (SteamMatchmakingCallback l : snapshot()) {
                l.onLobbyInvite(steamIDUser, steamIDLobby, gameID);
            }
        }

        @Override
        public void onLobbyKicked(SteamID steamIDLobby, SteamID steamIDAdmin, boolean dueToDisconnect) {
            for (SteamMatchmakingCallback l : snapshot()) {
                l.onLobbyKicked(steamIDLobby, steamIDAdmin, dueToDisconnect);
            }
        }
    }

    static void log(String message) {
        if (Gdx.app != null) Gdx.app.log("STEAM", message);
        else System.out.println("[STEAM] " + message);
    }
}
