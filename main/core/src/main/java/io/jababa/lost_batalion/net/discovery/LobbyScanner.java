package io.jababa.lost_batalion.net.discovery;

import io.jababa.lost_batalion.net.NetLog;
import com.esotericsoftware.kryo.Kryo;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.NetworkProtocol;
import io.jababa.lost_batalion.net.api.DiscoveredLobby;
import io.jababa.lost_batalion.net.api.LobbyDirectory;
import io.jababa.lost_batalion.net.messages.LobbyInfo;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Пошук лоббі в локальній мережі широкомовним UDP.
 *
 * <p>Працює у власному потоці: розсилає запит, слухає відповіді, тримає список
 * знайденого. Екран забирає готовий список — жодного блокування рендеру.
 *
 * <p>Лоббі, від якого давно немає відповіді, зникає зі списку. Хост, що закрив
 * гру, нікого про це не сповіщає, тож єдиний спосіб відрізнити живе лоббі від
 * мертвого — питати повторно і стежити, коли перестали відповідати.
 */
public class LobbyScanner implements LobbyDirectory {

    private final Kryo kryo = NetworkProtocol.createDiscoveryKryo();

    /** Ключ — адреса хоста: одна машина = одне лоббі. */
    private final Map<String, DiscoveredLobby> found = new LinkedHashMap<>();
    private final Object lock = new Object();

    private volatile boolean running;
    private volatile boolean scanning;
    /** Піднімається кнопкою «Оновити», щоб не чекати наступного циклу. */
    private volatile boolean refreshRequested;

    private Thread thread;
    private DatagramSocket socket;

    @Override
    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "lobby-scanner");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        if (socket != null) socket.close();   // розблоковує receive()
    }

    @Override
    public void refresh() {
        // Потік сканування опитує цей прапорець між таймаутами receive(),
        // тож позачергове сканування починається не пізніше ніж через 200 мс.
        refreshRequested = true;
    }

    @Override
    public boolean isScanning() { return scanning; }

    @Override
    public List<DiscoveredLobby> getLobbies() {
        long now = System.currentTimeMillis();
        List<DiscoveredLobby> result = new ArrayList<>();

        synchronized (lock) {
            for (DiscoveredLobby lobby : found.values()) {
                if (now - lobby.lastSeenMillis <= NetConfig.LOBBY_STALE_MS) result.add(lobby);
            }
        }

        // Стабільне сортування: інакше рядки в списку стрибали б місцями
        // щоразу, коли відповіді приходять в іншому порядку.
        Collections.sort(result, new Comparator<DiscoveredLobby>() {
            @Override public int compare(DiscoveredLobby a, DiscoveredLobby b) {
                int byName = String.valueOf(a.info.lobbyName)
                    .compareTo(String.valueOf(b.info.lobbyName));
                return byName != 0 ? byName : a.address.compareTo(b.address);
            }
        });
        return result;
    }

    // ── Потік сканування ──────────────────────────────────────────────────

    private void run() {
        try {
            socket = new DatagramSocket();          // ефемерний порт
            socket.setBroadcast(true);
            socket.setSoTimeout(200);
        } catch (Exception e) {
            NetLog.error("Не вдалось відкрити сокет пошуку лоббі: " + e.getMessage());
            running = false;
            return;
        }

        byte[] buffer = new byte[NetworkProtocol.DISCOVERY_PACKET_BYTES];
        long nextScanAt = 0;

        while (running) {
            long now = System.currentTimeMillis();

            if (now >= nextScanAt || refreshRequested) {
                refreshRequested = false;
                nextScanAt = now + NetConfig.DISCOVERY_INTERVAL_MS;
                scanning = true;
                sendRequests();
            }

            try {
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                handleResponse(response);
            } catch (java.net.SocketTimeoutException timeout) {
                // Штатно: тиша протягом soTimeout. Саме тут гасне індикатор,
                // бо відповіді, якщо вони були, вже прийшли.
                scanning = false;
            } catch (Exception e) {
                if (!running) break;
                scanning = false;
            }

            pruneStale();
        }

        if (socket != null) socket.close();
    }

    private void sendRequests() {
        byte[] request = NetworkProtocol.buildDiscoveryRequest();
        for (InetAddress target : BroadcastAddresses.collect()) {
            try {
                socket.send(new DatagramPacket(request, request.length, target, NetConfig.UDP_PORT));
            } catch (Exception ignored) {
                // Частина інтерфейсів (VPN, віртуальні адаптери) стабільно
                // відмовляє в broadcast — це нормально, решта відпрацює.
            }
        }
    }

    private void handleResponse(DatagramPacket packet) {
        LobbyInfo info = NetworkProtocol.readLobbyInfo(kryo, packet.getData(), packet.getLength());
        if (info == null) return;

        String address = packet.getAddress().getHostAddress();
        synchronized (lock) {
            found.put(address, new DiscoveredLobby(info, address, System.currentTimeMillis()));
        }
    }

    private void pruneStale() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            found.entrySet().removeIf(e -> now - e.getValue().lastSeenMillis > NetConfig.LOBBY_STALE_MS);
        }
    }
}
