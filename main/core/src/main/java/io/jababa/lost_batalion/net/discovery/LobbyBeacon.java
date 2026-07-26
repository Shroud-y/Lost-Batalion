package io.jababa.lost_batalion.net.discovery;

import io.jababa.lost_batalion.net.NetLog;
import com.esotericsoftware.kryo.Kryo;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.NetworkProtocol;
import io.jababa.lost_batalion.net.messages.LobbyInfo;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Маячок хоста: відповідає на широкомовні запити пошуку лоббі.
 *
 * <p>Пишеться вручну на {@link DatagramSocket}, а не через
 * {@code ServerDiscoveryHandler} з KryoNet, бо сигнатура того інтерфейса
 * посилається на package-private клас {@code UdpConnection} — реалізувати його
 * поза пакетом {@code com.esotericsoftware.kryonet} неможливо взагалі.
 * Свої 80 рядків тут виявились дешевшими за підкладання класу в чужий пакет.
 *
 * <p>Живе у власному потоці; єдине, що з нього чіпають ззовні — це
 * {@link #updateInfo}, тому картка лоббі лежить у volatile-полі.
 */
public class LobbyBeacon {

    private final Thread thread;
    private final Kryo   kryo = NetworkProtocol.createDiscoveryKryo();

    private volatile LobbyInfo info;
    private volatile boolean   running;
    private DatagramSocket socket;

    /**
     * Хто нас нещодавно питав. Саме їм піде прощання при закритті.
     *
     * <p>Розсилати прощання широкомовно не можна: сканер слухає на ефемерному
     * порту, а не на {@link NetConfig#UDP_PORT}, тож його адресу знає лише той,
     * до кого він звертався. Обмеження розміру — щоб довга гра не накопичила
     * список усіх, хто колись зазирав у мережу.
     */
    private final Set<SocketAddress> recentAskers = new LinkedHashSet<>();
    private static final int MAX_REMEMBERED_ASKERS = 16;

    public LobbyBeacon(LobbyInfo initialInfo) {
        this.info = initialInfo;
        this.thread = new Thread(this::run, "lobby-beacon");
        this.thread.setDaemon(true);
    }

    /** Оновити те, що маячок відповідає: кількість гравців, статус, карта. */
    public void updateInfo(LobbyInfo newInfo) {
        this.info = newInfo;
    }

    public void start() {
        if (running) return;
        running = true;
        thread.start();
    }

    public void stop() {
        // Спершу попрощатись, потім закривати сокет — після close() слати нічим.
        sayGoodbye();
        running = false;
        if (socket != null) socket.close();   // розблоковує receive()
    }

    /**
     * Сказати тим, хто нас бачить, що лоббі більше немає.
     *
     * <p>Best-effort і нічого не чекає: якщо пакет загубиться, рядок зникне
     * сам за таймаутом. Це прискорення, а не механізм коректності.
     */
    private void sayGoodbye() {
        DatagramSocket s = socket;
        if (s == null || s.isClosed()) return;

        byte[] bye = NetworkProtocol.buildDiscoveryBye();
        SocketAddress[] targets;
        synchronized (recentAskers) {
            targets = recentAskers.toArray(new SocketAddress[0]);
        }
        for (SocketAddress target : targets) {
            try {
                s.send(new DatagramPacket(bye, bye.length, target));
            } catch (Exception ignored) {
                // Той, хто вже пішов сам, нас не цікавить.
            }
        }
    }

    private void run() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(NetConfig.UDP_PORT));
        } catch (Exception e) {
            // Порт зайнятий іншим екземпляром гри на цій же машині. Лоббі при
            // цьому працює — його просто не видно в списку, треба вводити IP.
            NetLog.error("Не вдалось відкрити порт дискаверi " + NetConfig.UDP_PORT
                + ": " + e.getMessage() + ". Лоббі буде доступне лише за прямою адресою.");
            running = false;
            return;
        }

        byte[] buffer = new byte[NetworkProtocol.DISCOVERY_PACKET_BYTES];

        while (running) {
            try {
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                socket.receive(request);

                byte type = NetworkProtocol.discoveryType(request.getData(), request.getLength());
                if (type != NetworkProtocol.DISCOVERY_REQUEST) continue;

                LobbyInfo snapshot = info;
                if (snapshot == null) continue;

                rememberAsker(request.getSocketAddress());

                byte[] payload = NetworkProtocol.buildDiscoveryResponse(kryo, snapshot);
                socket.send(new DatagramPacket(payload, payload.length,
                                               request.getAddress(), request.getPort()));
            } catch (Exception e) {
                if (!running) break;   // штатне закриття сокета в stop()
                NetLog.error("Збій маячка лоббі: " + e);
            }
        }

        if (socket != null) socket.close();
    }

    private void rememberAsker(SocketAddress address) {
        synchronized (recentAskers) {
            // Повторна вставка має оновлювати позицію, інакше найактивніший
            // сканер першим випаде з обмеженого списку.
            recentAskers.remove(address);
            recentAskers.add(address);
            while (recentAskers.size() > MAX_REMEMBERED_ASKERS) {
                recentAskers.remove(recentAskers.iterator().next());
            }
        }
    }
}
