package io.jababa.lost_batalion.steam;

import com.badlogic.gdx.Gdx;
import io.jababa.lost_batalion.net.commands.MoveCommand;
import io.jababa.lost_batalion.net.messages.DesyncAlert;
import io.jababa.lost_batalion.net.messages.PlayerDropped;
import io.jababa.lost_batalion.net.messages.ResumeMatch;
import io.jababa.lost_batalion.net.messages.ResyncAck;
import io.jababa.lost_batalion.net.messages.ResyncSnapshot;
import io.jababa.lost_batalion.net.messages.TickChecksum;
import io.jababa.lost_batalion.net.messages.TickCommands;

import java.util.ArrayList;

/**
 * Самоперевірка транспорту матчу: {@code -Dlb.steamProbe=match}.
 *
 * <p>Справжній обмін P2P на одному Steam-акаунті неможливий, і зонд цього не
 * вдає. Але найтихіша поломка транспорту від другого клієнта не залежить:
 * <b>серіалізація</b>. Незареєстрований клас, зсунутий порядок у
 * {@code NetworkProtocol}, поле, яке Kryo не вміє обійти, — усе це виявляється
 * не помилкою, а мовчазним сміттям на іншому кінці або десинхроном за хвилину
 * після старту. Тут кожен тип повідомлення матчу проганяється туди-назад і
 * звіряється поле за полем.
 *
 * <p>Друга перевірка — стеля пакета: {@code ResyncSnapshot} понад 1 МБ мусить
 * ЧЕСНО не запакуватись, а не поїхати обрізаним.
 */
public final class SteamMatchProbe implements SteamProbeTask {

    private final SteamPacketCodec codec = new SteamPacketCodec();
    private final StringBuilder    failures = new StringBuilder();
    private int  failureCount;
    private boolean done;

    @Override
    public void tick() {
        if (done) return;
        done = true;

        checkCommands();
        checkChecksum();
        checkSnapshot();
        checkSmallMessages();
        checkOversize();

        SteamMatchmakingHub.log("ЗОНД: " + (failureCount == 0
            ? "УСПІХ: усі повідомлення матчу переживають обіг"
            : "ПРОВАЛ: " + failures));

        if (Gdx.app != null) Gdx.app.exit();
    }

    // ── Перевірки ─────────────────────────────────────────────────────────

    private void checkCommands() {
        ArrayList<io.jababa.lost_batalion.net.commands.GameCommand> commands = new ArrayList<>();
        commands.add(new MoveCommand(1, new int[]{ 3, 5, 8 }, 0x1234_5678L, -0x9876_5432L));

        TickCommands original = new TickCommands(120, 1, commands, 4);
        TickCommands back = (TickCommands) roundTrip(original, "TickCommands");
        if (back == null) return;

        if (back.tick != 120)        fail("TickCommands.tick " + back.tick);
        if (back.playerId != 1)      fail("TickCommands.playerId " + back.playerId);
        if (back.generation != 4)    fail("TickCommands.generation " + back.generation);
        if (back.commands.size() != 1) {
            fail("наказів " + back.commands.size());
            return;
        }

        MoveCommand move = (MoveCommand) back.commands.get(0);
        if (move.playerId != 1)                  fail("MoveCommand.playerId " + move.playerId);
        if (move.unitIds.length != 3 || move.unitIds[2] != 8) fail("MoveCommand.unitIds зіпсовано");
        // Координати у fixed-point: саме тут помилка серіалізації дала б
        // юнітів, що йдуть у різні точки на різних клієнтах.
        if (move.targetX != 0x1234_5678L)        fail("MoveCommand.targetX " + move.targetX);
        if (move.targetY != -0x9876_5432L)       fail("MoveCommand.targetY " + move.targetY);
    }

    private void checkChecksum() {
        TickChecksum original = new TickChecksum(400, 0, 0xDEAD_BEEF_CAFE_1234L,
            new long[]{ 1, 2, 3, 4, 5, 6, 7, 8 });
        TickChecksum back = (TickChecksum) roundTrip(original, "TickChecksum");
        if (back == null) return;

        if (back.tick != 400)                       fail("TickChecksum.tick " + back.tick);
        if (back.checksum != original.checksum)     fail("TickChecksum.checksum зіпсовано");
        if (back.components == null || back.components.length != 8 || back.components[7] != 8) {
            fail("TickChecksum.components зіпсовано");
        }
    }

    private void checkSnapshot() {
        byte[] state = new byte[128 * 1024];
        for (int i = 0; i < state.length; i++) state[i] = (byte) (i * 31);

        ResyncSnapshot original = new ResyncSnapshot(777, state, 0x0102_0304_0506_0708L);
        ResyncSnapshot back = (ResyncSnapshot) roundTrip(original, "ResyncSnapshot");
        if (back == null) return;

        if (back.tick != 777)                    fail("ResyncSnapshot.tick " + back.tick);
        if (back.checksum != original.checksum)  fail("ResyncSnapshot.checksum зіпсовано");
        if (back.state == null || back.state.length != state.length) {
            fail("ResyncSnapshot.state довжина " + (back.state == null ? "null" : back.state.length));
            return;
        }
        for (int i = 0; i < state.length; i++) {
            if (back.state[i] != state[i]) {
                fail("ResyncSnapshot.state розійшовся на байті " + i);
                return;
            }
        }
    }

    private void checkSmallMessages() {
        DesyncAlert alert = new DesyncAlert(50, new int[]{ 1 }, new String[]{ "гість" }, 42L);
        DesyncAlert alertBack = (DesyncAlert) roundTrip(alert, "DesyncAlert");
        if (alertBack != null) {
            if (alertBack.tick != 50)                    fail("DesyncAlert.tick");
            if (alertBack.hostChecksum != 42L)           fail("DesyncAlert.hostChecksum");
            if (alertBack.desyncedNicks == null
                || !"гість".equals(alertBack.desyncedNicks[0])) {
                // Кирилиця окремо: якби Kryo писав рядки не в UTF-8, це
                // вилізло б саме на ніках, і саме в бою.
                fail("DesyncAlert.desyncedNicks зіпсовано");
            }
        }

        roundTrip(new ResyncAck(), "ResyncAck");
        roundTrip(new ResumeMatch(), "ResumeMatch");
        roundTrip(new PlayerDropped(), "PlayerDropped");
    }

    private void checkOversize() {
        // Удвічі більше за стелю пакета. Має повернутись null, а не виняток і
        // не обрізані байти.
        byte[] huge = new byte[SteamPacketCodec.MAX_PACKET_BYTES * 2];
        byte[] encoded = codec.encode(new ResyncSnapshot(1, huge, 0));
        if (encoded != null) {
            fail("знімок " + huge.length + " Б запакувався, хоча стеля "
                + SteamPacketCodec.MAX_PACKET_BYTES);
        } else {
            SteamMatchmakingHub.log("ЗОНД: знімок понад стелю чесно відмовлено");
        }

        // Найважливіше в цій перевірці — НАСТУПНЕ повідомлення. Провалений
        // пакет лишає буфер Kryo на півслові, і якби encode() не починався з
        // clear(), матч після однієї невдачі возив би сміття далі.
        if (roundTrip(new TickCommands(1, 0, new ArrayList<>(), 0), "TickCommands після відмови") == null) {
            fail("кодек не оговтався після завеликого пакета");
        }
    }

    // ── Дрібне ────────────────────────────────────────────────────────────

    private Object roundTrip(Object message, String name) {
        byte[] data = codec.encode(message);
        if (data == null) {
            fail(name + " не запакувався");
            return null;
        }
        Object back = codec.decode(data, data.length);
        if (back == null) {
            fail(name + " не розпакувався");
            return null;
        }
        if (!back.getClass().equals(message.getClass())) {
            fail(name + " приїхав як " + back.getClass().getSimpleName());
            return null;
        }
        SteamMatchmakingHub.log("ЗОНД: " + name + " — " + data.length + " Б, обіг цілий");
        return back;
    }

    private void fail(String reason) {
        if (failureCount++ > 0) failures.append("; ");
        failures.append(reason);
    }
}
