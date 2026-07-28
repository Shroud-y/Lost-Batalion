package io.jababa.lost_batalion.net;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryonet.EndPoint;
import io.jababa.lost_batalion.net.commands.AttackCommand;
import io.jababa.lost_batalion.net.commands.CurveFormationCommand;
import io.jababa.lost_batalion.net.commands.MoveCommand;
import io.jababa.lost_batalion.net.commands.MoveLineCommand;
import io.jababa.lost_batalion.net.commands.StopCommand;
import io.jababa.lost_batalion.net.messages.*;

import java.util.ArrayList;

/**
 * Реєстрація класів у Kryo.
 *
 * <p>Kryo пише не імена класів, а їхні числові id, які роздаються в порядку
 * реєстрації. Тому порядок у {@link #register(Kryo)} — частина протоколу:
 * переставити два рядки місцями означає, що клієнт зі старим порядком
 * розпакує MoveCommand як AttackCommand і матч розсиплеться найдивнішим чином.
 *
 * <p>Правила зміни цього файлу:
 * <ul>
 *   <li>Нові класи додавати ЛИШЕ в кінець списку.</li>
 *   <li>Нічого не видаляти й не переставляти.</li>
 *   <li>Будь-яка зміна — підняти {@link NetConfig#PROTOCOL_VERSION}.</li>
 * </ul>
 */
public final class NetworkProtocol {

    private NetworkProtocol() {}

    /** Реєстрація на обох кінцях з'єднання. */
    public static void register(EndPoint endPoint) {
        register(endPoint.getKryo());
    }

    public static void register(Kryo kryo) {
        NetLog.init();

        // Незареєстрований клас має падати гучно тут, а не тихо їхати по мережі
        // з іменем класу в заголовку і вилазити боком на іншій платформі.
        kryo.setRegistrationRequired(true);

        // ── Службові типи ──────────────────────────────────────────────────
        kryo.register(int[].class);
        kryo.register(long[].class);
        kryo.register(String[].class);
        kryo.register(byte[].class);
        kryo.register(ArrayList.class);

        // ── Лоббі ──────────────────────────────────────────────────────────
        kryo.register(LobbyStatus.class);
        kryo.register(PlayerSlot.class);
        kryo.register(LobbyInfo.class);
        kryo.register(LobbyState.class);
        kryo.register(JoinRequest.class);
        kryo.register(JoinResponse.class);
        kryo.register(SetReady.class);
        kryo.register(LeaveLobby.class);
        kryo.register(StartMatch.class);

        // ── Команди гравця ─────────────────────────────────────────────────
        kryo.register(MoveCommand.class);
        kryo.register(MoveLineCommand.class);
        kryo.register(CurveFormationCommand.class);
        kryo.register(AttackCommand.class);
        kryo.register(StopCommand.class);

        // ── Хід матчу ──────────────────────────────────────────────────────
        kryo.register(TickCommands.class);
        kryo.register(TickChecksum.class);
        kryo.register(DesyncAlert.class);
        kryo.register(ResyncSnapshot.class);
        kryo.register(ResyncAck.class);
        kryo.register(ResumeMatch.class);

        // ── З'єднання ──────────────────────────────────────────────────────
        kryo.register(ConnectionEventKind.class);
        kryo.register(PlayerConnectionEvent.class);
        kryo.register(PlayerDropped.class);

        // Нові класи — суворо нижче цього рядка.
        kryo.register(io.jababa.lost_batalion.net.commands.PathMoveCommand.class);
        kryo.register(io.jababa.lost_batalion.net.commands.SpawnCommand.class);
        kryo.register(io.jababa.lost_batalion.net.commands.CancelSpawnCommand.class);
    }

    // ── Дискаверi лоббі ───────────────────────────────────────────────────

    /**
     * Окремий Kryo для UDP-дискаверi.
     *
     * <p>Дискаверi обробляється в іншому потоці, ніж ігровий трафік, а Kryo не
     * потокобезпечний — тягнути сюди інстанс з'єднання означало б ловити рідкісні
     * пошкодження буфера саме тоді, коли хтось сканує мережу під час матчу.
     */
    public static Kryo createDiscoveryKryo() {
        Kryo kryo = new Kryo();
        register(kryo);
        return kryo;
    }

    /** Максимальний розмір датаграми — з запасом, але в межах одного пакета без фрагментації. */
    public static final int DISCOVERY_PACKET_BYTES = 512;

    /**
     * Заголовок наших датаграм. На широкомовний порт може прилетіти будь-що —
     * від чужої гри до службового трафіку мережі, — тож пакети без цього
     * префікса відкидаються ще до спроби розпакувати їх Kryo.
     */
    public static final byte[] DISCOVERY_MAGIC = { 'L', 'B', 'T', 'N' };

    public static final byte DISCOVERY_REQUEST  = 0x01;
    public static final byte DISCOVERY_RESPONSE = 0x02;
    /**
     * Хост іде з мережі. Не обов'язковий: UDP не гарантує доставки, тож
     * зникнення лоббі однаково підстраховане таймаутом за
     * {@link NetConfig#LOBBY_STALE_MS}. Але коли пакет доходить — а в
     * локальній мережі він доходить майже завжди — рядок зникає одразу,
     * а не висить шість секунд, наче гра підвисла.
     */
    public static final byte DISCOVERY_BYE      = 0x03;

    /** Запит «хто тут є?»: магія + тип + версія протоколу. */
    public static byte[] buildDiscoveryRequest() {
        byte[] packet = new byte[DISCOVERY_MAGIC.length + 2];
        System.arraycopy(DISCOVERY_MAGIC, 0, packet, 0, DISCOVERY_MAGIC.length);
        packet[DISCOVERY_MAGIC.length]     = DISCOVERY_REQUEST;
        packet[DISCOVERY_MAGIC.length + 1] = (byte) NetConfig.PROTOCOL_VERSION;
        return packet;
    }

    /** Прощання хоста: магія + тип + версія. Тіла не потребує — досить адреси. */
    public static byte[] buildDiscoveryBye() {
        byte[] packet = new byte[DISCOVERY_MAGIC.length + 2];
        System.arraycopy(DISCOVERY_MAGIC, 0, packet, 0, DISCOVERY_MAGIC.length);
        packet[DISCOVERY_MAGIC.length]     = DISCOVERY_BYE;
        packet[DISCOVERY_MAGIC.length + 1] = (byte) NetConfig.PROTOCOL_VERSION;
        return packet;
    }

    /** Відповідь хоста: магія + тип + серіалізована візитівка. */
    public static byte[] buildDiscoveryResponse(Kryo kryo, LobbyInfo info) {
        Output out = new Output(DISCOVERY_PACKET_BYTES);
        out.writeBytes(DISCOVERY_MAGIC);
        out.writeByte(DISCOVERY_RESPONSE);
        kryo.writeObject(out, info);
        out.flush();
        return out.toBytes();
    }

    /** @return тип пакета ({@link #DISCOVERY_REQUEST}/{@link #DISCOVERY_RESPONSE}) або 0, якщо чужий */
    public static byte discoveryType(byte[] data, int length) {
        if (length < DISCOVERY_MAGIC.length + 1) return 0;
        for (int i = 0; i < DISCOVERY_MAGIC.length; i++) {
            if (data[i] != DISCOVERY_MAGIC[i]) return 0;
        }
        return data[DISCOVERY_MAGIC.length];
    }

    /** @return розпакована візитівка або null, якщо датаграма чужа/пошкоджена */
    public static LobbyInfo readLobbyInfo(Kryo kryo, byte[] data, int length) {
        if (discoveryType(data, length) != DISCOVERY_RESPONSE) return null;
        int offset = DISCOVERY_MAGIC.length + 1;
        try {
            return kryo.readObject(new Input(data, offset, length - offset), LobbyInfo.class);
        } catch (RuntimeException e) {
            // Пакет із правильним префіксом, але поламаним вмістом — не привід падати.
            return null;
        }
    }
}
