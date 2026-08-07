package io.jababa.lost_batalion.steam;

import com.badlogic.gdx.Gdx;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import com.codedisaster.steamworks.SteamResult;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.DiscoveredLobby;

/**
 * Самоперевірка матчмейкінгу: створити лоббі → опублікувати метадані →
 * знайти його власним пошуком → вийти.
 *
 * <p>Вмикається {@code -Dlb.steamProbe=true} (разом із
 * {@code -Dlb.steamAppId=480}). Потрібна тому, що список лоббі неможливо
 * перевірити наодинці: без чужого хоста порожній список означає і «працює», і
 * «зламано». Тут обидві ролі грає один процес — а перевіряється саме те, що
 * ламається найчастіше: збіг імен ключів і те, що фільтри пошуку справді
 * пропускають наше лоббі.
 *
 * <p>Живе в модулі {@code :steam}, а не в дев-консолі: консоль лежить у
 * {@code core}, якому про Steam знати не можна.
 *
 * <p>Це інструмент розробки. У грі його не видно, поки не задано властивість.
 */
public final class SteamProbe implements SteamProbeTask, SteamMatchmakingCallback {

    /** Скільки чекати кожного кроку, перш ніж визнати провал. */
    private static final long STEP_TIMEOUT_MS = 15_000;

    private enum Phase { CREATE, WAIT_CREATE, SEARCH, WAIT_SEARCH, WAIT_WRONG_KEY, DONE }

    private final SteamMatchmakingHub hub;
    private final SteamLobbyDirectory directory = new SteamLobbyDirectory();
    private final String roomKey = SteamLobbyKeys.generateKey();

    private Phase   phase = Phase.CREATE;
    private long    phaseStartedAt;
    private SteamID lobby;
    /** Скільки пошуків було зроблено до переходу на чужий ключ. */
    private int     wrongKeyScans;

    SteamProbe() {
        this.hub = SteamMatchmakingHub.get();
        hub.addListener(this);
        phaseStartedAt = System.currentTimeMillis();
    }

    /** Кличеться щокадру з {@code SteamBackend.pump()}. */
    @Override
    public void tick() {
        if (phase == Phase.DONE) return;

        if (System.currentTimeMillis() - phaseStartedAt > STEP_TIMEOUT_MS) {
            finish("ПРОВАЛ: крок " + phase + " не завершився за "
                + (STEP_TIMEOUT_MS / 1000) + " с");
            return;
        }

        switch (phase) {
            case CREATE:
                // Public — свідомо: саме публічне лоббі перевіряє шлях пошуку.
                // Див. SteamLobbyKeys.KEY про те, чому ключ не є захистом.
                hub.api().createLobby(SteamMatchmaking.LobbyType.Public, NetConfig.MAX_PLAYERS);
                advance(Phase.WAIT_CREATE);
                break;

            case SEARCH:
                directory.setRoomKey(roomKey);
                directory.start();
                advance(Phase.WAIT_SEARCH);
                break;

            case WAIT_SEARCH:
                // Каталог сам себе не перезапитує — розклад веде той, хто його
                // тримає. У грі це SteamBackend.pump(), тут зонд. Без цього
                // рядка пошук стається РІВНО ОДИН раз, і свіжо створене лоббі,
                // метадані якого ще не розійшлись по серверах Steam, не
                // знаходиться ніколи.
                directory.tick();
                if (!directory.isScanning() && !directory.getLobbies().isEmpty()) {
                    if (!report()) return;

                    // Другий прохід — із свідомо чужим ключем. Без нього
                    // «фільтр працює» лишається припущенням: перший прохід
                    // однаково знайшов би лоббі й тоді, коли lb_key не
                    // фільтрує зовсім.
                    wrongKeyScans = directory.getScanCount();
                    directory.setRoomKey(wrongKey());
                    advance(Phase.WAIT_WRONG_KEY);
                }
                break;

            case WAIT_WRONG_KEY:
                directory.tick();
                if (directory.getScanCount() > wrongKeyScans && !directory.isScanning()) {
                    int leaked = directory.getLobbies().size();
                    finish(leaked == 0
                        ? "УСПІХ: чужий ключ не показує нічого — фільтр діє"
                        : "ПРОВАЛ: за чужим ключем видно " + leaked + " лоббі");
                }
                break;

            default:
                break;   // чекаємо колбека
        }
    }

    @Override
    public void onLobbyCreated(SteamResult result, SteamID steamIDLobby) {
        if (phase != Phase.WAIT_CREATE) return;

        if (result != SteamResult.OK || steamIDLobby == null || !steamIDLobby.isValid()) {
            finish("ПРОВАЛ: лоббі не створене, результат " + result);
            return;
        }

        lobby = steamIDLobby;
        SteamMatchmaking api = hub.api();
        api.setLobbyData(lobby, SteamLobbyKeys.GAME,     SteamLobbyKeys.GAME_VALUE);
        api.setLobbyData(lobby, SteamLobbyKeys.PROTO,    String.valueOf(NetConfig.PROTOCOL_VERSION));
        api.setLobbyData(lobby, SteamLobbyKeys.KEY,      roomKey);
        api.setLobbyData(lobby, SteamLobbyKeys.NAME,     "Зонд");
        api.setLobbyData(lobby, SteamLobbyKeys.NICK,     "probe");
        api.setLobbyData(lobby, SteamLobbyKeys.SCENARIO, "zhovti_vody");
        api.setLobbyData(lobby, SteamLobbyKeys.STATUS,   "WAITING");
        api.setLobbyData(lobby, SteamLobbyKeys.MAX,      String.valueOf(NetConfig.MAX_PLAYERS));
        api.setLobbyData(lobby, SteamLobbyKeys.COUNT,    "1");

        SteamMatchmakingHub.log("ЗОНД: лоббі " + SteamMatchmakingHub.toAddress(lobby)
            + ", ключ кімнати " + roomKey);
        advance(Phase.SEARCH);
    }

    // ── Підсумок ──────────────────────────────────────────────────────────

    /** @return чи знайшлось власне лоббі; при провалі зонд уже завершено */
    private boolean report() {
        String mine = lobby == null ? "" : SteamMatchmakingHub.toAddress(lobby);
        for (DiscoveredLobby found : directory.getLobbies()) {
            if (!found.address.equals(mine)) continue;

            String reason = found.unjoinableReason(NetConfig.PROTOCOL_VERSION);
            SteamMatchmakingHub.log("ЗОНД: УСПІХ: знайдено власне лоббі — «"
                + found.info.lobbyName + "», хост " + found.info.hostNick
                + ", " + found.info.playerCount + "/" + found.info.maxPlayers
                + ", сценарій " + found.info.scenarioId
                + ", стан " + found.info.status
                + (reason == null ? ", можна приєднатись" : ", НЕ приєднатись: " + reason));
            return true;
        }
        finish("ПРОВАЛ: пошук повернув " + directory.getLobbies().size()
            + " лоббі, але власного серед них немає");
        return false;
    }

    /** Ключ тієї ж форми, але завідомо не наш. */
    private String wrongKey() {
        String other;
        do {
            other = SteamLobbyKeys.generateKey();
        } while (other.equals(roomKey));
        return other;
    }

    private void advance(Phase next) {
        phase = next;
        phaseStartedAt = System.currentTimeMillis();
    }

    private void finish(String message) {
        SteamMatchmakingHub.log("ЗОНД: " + message);
        phase = Phase.DONE;

        directory.stop();
        hub.removeListener(this);
        if (lobby != null) hub.api().leaveLobby(lobby);

        // Лоббі живе, поки в ньому є учасники: вихід останнього його розпускає.
        // Без цього кожен прогін зонда лишав би по собі порожню кімнату.
        if (Gdx.app != null) Gdx.app.exit();
    }
}
