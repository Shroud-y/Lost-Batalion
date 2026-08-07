package io.jababa.lost_batalion.steam;

import com.badlogic.gdx.Gdx;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.DiscoveredLobby;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.messages.LobbyState;
import io.jababa.lost_batalion.net.messages.PlayerSlot;
import io.jababa.lost_batalion.net.messages.StartMatch;

import java.util.ArrayList;

/**
 * Самоперевірка лоббі-сесії: {@code -Dlb.steamProbe=session}.
 *
 * <p>Один Steam-акаунт не може зайти у власне лоббі другим гравцем, тож усе,
 * що потребує двох сторін, тут перевірити НЕМОЖЛИВО — і зонд цього не вдає.
 * Перевіряється рівно те, що робить одна сторона:
 *
 * <ol>
 *   <li>лоббі створюється, і {@link LobbySession} віддає {@link LobbyState}
 *       з коректним слотом хоста;</li>
 *   <li>зміна сценарію доїжджає до метаданих;</li>
 *   <li>власний пошук бачить лоббі, зібране сесією (а не зондом списку);</li>
 *   <li>«Старт» на самоті ВІДМОВЛЯЄ — інакше матч почався б без суперника;</li>
 *   <li>кодування {@code StartMatch} переживає обіг туди-назад.</li>
 * </ol>
 *
 * <p>Пункт 5 — єдиний спосіб перевірити формат старту на одному акаунті:
 * справжній обмін ним вимагає двох клієнтів Steam.
 */
public final class SteamSessionProbe implements SteamProbeTask {

    private static final long STEP_TIMEOUT_MS = 20_000;

    private enum Phase { WAIT_LOBBY, SCENARIO, WAIT_SEARCH, DONE }

    private final SteamLobbyDirectory directory = new SteamLobbyDirectory();
    private final SteamLobbySession   session;
    private final String              roomKey = SteamLobbyKeys.generateKey();

    private final List failures = new List();

    private Phase phase = Phase.WAIT_LOBBY;
    private long  phaseStartedAt = System.currentTimeMillis();
    private LobbyState state;
    private String     lobbyAddress = "";

    SteamSessionProbe() {
        session = new SteamLobbySession("Зонд сесії", "probe-host", "zhovti_vody", 2, roomKey);
    }

    @Override
    public void tick() {
        if (phase == Phase.DONE) return;

        // Події сесії приходять тільки через pump — так само, як їх забирає
        // екран лоббі. Зонд навмисно ходить тим самим шляхом, що й UI.
        session.pump(new LobbySession.Adapter() {
            @Override public void onLobbyState(LobbyState s) { state = s; }
            @Override public void onDisconnected(String reason) {
                finish("ПРОВАЛ: сесія обірвалась — " + reason);
            }
        });

        if (phase == Phase.DONE) return;

        if (System.currentTimeMillis() - phaseStartedAt > STEP_TIMEOUT_MS) {
            finish("ПРОВАЛ: крок " + phase + " не завершився за " + (STEP_TIMEOUT_MS / 1000) + " с");
            return;
        }

        switch (phase) {
            case WAIT_LOBBY:
                if (state != null) {
                    checkHostSlot();
                    advance(Phase.SCENARIO);
                }
                break;

            case SCENARIO:
                session.setScenario("zhovti_vody_2");
                directory.setRoomKey(roomKey);
                directory.start();
                advance(Phase.WAIT_SEARCH);
                break;

            case WAIT_SEARCH:
                directory.tick();
                if (!directory.isScanning() && !directory.getLobbies().isEmpty()) {
                    checkVisible();
                    checkStartRefused();
                    checkStartCodec();
                    finish(failures.isEmpty()
                        ? "УСПІХ: усі перевірки сесії пройдено"
                        : "ПРОВАЛ: " + failures);
                }
                break;

            default:
                break;
        }
    }

    // ── Перевірки ─────────────────────────────────────────────────────────

    private void checkHostSlot() {
        if (state.slots.size() != 1) {
            failures.add("слотів " + state.slots.size() + ", очікувався 1");
            return;
        }
        PlayerSlot host = state.slots.get(0);
        if (host.playerId != 0)             failures.add("playerId хоста " + host.playerId);
        if (!host.host)                     failures.add("хост не позначений хостом");
        if (!host.ready)                    failures.add("хост не «готовий»");
        if (!"probe-host".equals(host.nick)) failures.add("нік хоста «" + host.nick + "»");
        if (state.allReady())               failures.add("allReady на самоті");

        SteamMatchmakingHub.log("ЗОНД: слот хоста — " + host);
    }

    private void checkVisible() {
        for (DiscoveredLobby found : directory.getLobbies()) {
            lobbyAddress = found.address;
            if (!"Зонд сесії".equals(found.info.lobbyName)) {
                failures.add("назва в пошуку «" + found.info.lobbyName + "»");
            }
            if (!"probe-host".equals(found.info.hostNick)) {
                failures.add("нік хоста в пошуку «" + found.info.hostNick + "»");
            }
            // Сценарій міняли ПІСЛЯ створення — так перевіряється, що
            // setScenario справді пише в метадані, а не лише в поле сесії.
            if (!"zhovti_vody_2".equals(found.info.scenarioId)) {
                failures.add("сценарій у пошуку «" + found.info.scenarioId + "»");
            }
            SteamMatchmakingHub.log("ЗОНД: у пошуку — «" + found.info.lobbyName
                + "», " + found.info.playerCount + "/" + found.info.maxPlayers
                + ", сценарій " + found.info.scenarioId);
            return;
        }
        failures.add("власного лоббі немає в пошуку");
    }

    private void checkStartRefused() {
        session.startMatch();
        if (state != null && state.status != io.jababa.lost_batalion.net.messages.LobbyStatus.WAITING) {
            failures.add("матч стартував із одним гравцем");
        } else {
            SteamMatchmakingHub.log("ЗОНД: старт на самоті відхилено — правильно");
        }
    }

    private void checkStartCodec() {
        StartMatch original = new StartMatch();
        original.protocolVersion = NetConfig.PROTOCOL_VERSION;
        original.rngSeed         = 0x0BADC0DEDEADBEEFL;
        original.scenarioId      = "zhovti_vody_2";
        original.tickRate              = NetConfig.TICK_RATE;
        original.inputDelayTicks       = NetConfig.INPUT_DELAY_TICKS;
        original.checksumIntervalTicks = NetConfig.getChecksumIntervalTicks();
        original.slots = new ArrayList<>();

        String encoded = session.encodeStart(original);
        StartMatch back = session.decodeStart(encoded, lobbyAddress);

        if (back == null) {
            failures.add("StartMatch не розібрався з «" + encoded + "»");
            return;
        }
        if (back.rngSeed != original.rngSeed)          failures.add("seed зіпсовано");
        if (!"zhovti_vody_2".equals(back.scenarioId))  failures.add("сценарій зіпсовано");
        if (back.tickRate != original.tickRate)        failures.add("темп зіпсовано");
        if (back.inputDelayTicks != original.inputDelayTicks) failures.add("затримку вводу зіпсовано");
        if (back.checksumIntervalTicks != original.checksumIntervalTicks) {
            failures.add("інтервал звірки зіпсовано");
        }
        if (back.slots.size() != 1 || back.slots.get(0).playerId != 0 || !back.slots.get(0).host) {
            failures.add("склад із lb_members відновився неправильно");
        }
        SteamMatchmakingHub.log("ЗОНД: StartMatch туди-назад: " + encoded);
    }

    // ── Дрібне ────────────────────────────────────────────────────────────

    private void advance(Phase next) {
        phase = next;
        phaseStartedAt = System.currentTimeMillis();
    }

    private void finish(String message) {
        SteamMatchmakingHub.log("ЗОНД: " + message);
        phase = Phase.DONE;
        directory.stop();
        session.leave();
        if (Gdx.app != null) Gdx.app.exit();
    }

    /** Найпростіший збирач причин провалу — щоб побачити ВСІ, а не першу. */
    private static final class List {
        private final StringBuilder sb = new StringBuilder();
        private int count;

        void add(String reason) {
            if (count++ > 0) sb.append("; ");
            sb.append(reason);
        }

        boolean isEmpty() { return count == 0; }

        @Override public String toString() { return sb.toString(); }
    }
}
