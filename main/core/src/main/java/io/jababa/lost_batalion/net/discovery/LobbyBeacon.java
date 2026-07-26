package io.jababa.lost_batalion.net.discovery;

import io.jababa.lost_batalion.net.NetLog;
import com.esotericsoftware.kryo.Kryo;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.NetworkProtocol;
import io.jababa.lost_batalion.net.messages.LobbyInfo;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

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
        running = false;
        if (socket != null) socket.close();   // розблоковує receive()
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
}
