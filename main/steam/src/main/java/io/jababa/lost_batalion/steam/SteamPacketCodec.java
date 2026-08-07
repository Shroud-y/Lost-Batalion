package io.jababa.lost_batalion.steam;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.jababa.lost_batalion.net.NetworkProtocol;

/**
 * Повідомлення матчу ⇄ байти.
 *
 * <p>KryoNet возить об'єкти сам і кадрує потік довжинами. Steam P2P натомість
 * пакетний: одне повідомлення — один пакет, кордони зберігає сам сервіс. Тож
 * від KryoNet тут лишається саме серіалізація, а кадрування не потрібне.
 *
 * <p>Реєстр той самий {@link NetworkProtocol}: числові id класів мусять
 * збігатися з локальною мережею, інакше два бекенди мали б два різні
 * протоколи, а {@code PROTOCOL_VERSION} — один на обидва.
 *
 * <p>Kryo не потокобезпечний, і тут це не проблема: увесь трафік Steam
 * читається й пишеться з потоку рендеру.
 */
final class SteamPacketCodec {

    /**
     * Стеля одного пакета. Надійний P2P у Steam фрагментує сам, але не більш
     * ніж до 1 МБ на повідомлення.
     *
     * <p>Єдине, що може до неї наблизитись, — {@code ResyncSnapshot}. Рахунок:
     * знімок пише десятки байтів на юніт, тобто мегабайт це приблизно десять
     * тисяч юнітів. За економікою гри (піхотинець — 50 золота, приріст 5 за
     * 5 с) стільки не буває, тому нарізки на частини тут НЕМАЄ — була б
     * складність і зайвий тип у реєстрі Kryo заради недосяжного випадку.
     * Натомість є голосна перевірка: якщо колись стане досяжним, це видно
     * буде одразу, а не як мовчазний збій ресинку.
     */
    static final int MAX_PACKET_BYTES = 1024 * 1024;

    private final Kryo   kryo = new Kryo();
    private final Output output = new Output(8 * 1024, MAX_PACKET_BYTES);

    SteamPacketCodec() {
        NetworkProtocol.register(kryo);
    }

    /** @return байти повідомлення або {@code null}, якщо воно не влазить у пакет */
    byte[] encode(Object message) {
        // clear(), а не reset(): у Kryo 4, який тягне KryoNet, другого немає.
        output.clear();
        try {
            kryo.writeClassAndObject(output, message);
        } catch (RuntimeException e) {
            // Найімовірніша причина — вихід за MAX_PACKET_BYTES: Output кидає
            // при спробі вирости понад стелю.
            SteamMatchmakingHub.log("не вдалось запакувати "
                + message.getClass().getSimpleName() + ": " + e);
            return null;
        }

        if (output.position() > MAX_PACKET_BYTES) {
            SteamMatchmakingHub.log("повідомлення " + message.getClass().getSimpleName()
                + " завелике для P2P: " + output.position() + " Б");
            return null;
        }
        return output.toBytes();
    }

    /** @return розпаковане повідомлення або {@code null}, якщо пакет чужий чи побитий */
    Object decode(byte[] data, int length) {
        try {
            return kryo.readClassAndObject(new Input(data, 0, length));
        } catch (RuntimeException e) {
            // На AppID 480 у P2P-сесію може прилетіти будь-що. Падати від
            // чужого пакета не можна — його треба просто викинути.
            SteamMatchmakingHub.log("пакет не розпакувався (" + length + " Б): " + e);
            return null;
        }
    }
}
