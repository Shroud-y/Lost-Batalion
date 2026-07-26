package io.jababa.lost_batalion.net.discovery;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Куди слати широкомовний запит пошуку лоббі.
 *
 * <p>Одного 255.255.255.255 недостатньо: Windows часто не пускає його далі
 * першого інтерфейса, а на машині з віртуалками (VirtualBox, WSL, Docker) цим
 * «першим» виявляється саме віртуальний адаптер, за яким нікого немає. Тому
 * пакет летить ще й на broadcast-адресу кожного придатного інтерфейса окремо.
 */
final class BroadcastAddresses {

    private BroadcastAddresses() {}

    static List<InetAddress> collect() {
        List<InetAddress> result = new ArrayList<>();

        try {
            result.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {
            // Немає IPv4-стека — лишаються адреси інтерфейсів нижче.
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback()) continue;

                for (InterfaceAddress addr : nic.getInterfaceAddresses()) {
                    InetAddress broadcast = addr.getBroadcast();
                    if (broadcast != null && !result.contains(broadcast)) {
                        result.add(broadcast);
                    }
                }
            }
        } catch (Exception ignored) {
            // Перелік інтерфейсів може бути недоступний (обмеження ОС) —
            // тоді лишається загальний broadcast.
        }

        return result;
    }
}
