package io.jababa.lost_batalion.net.api;

import io.jababa.lost_batalion.net.messages.DesyncAlert;
import io.jababa.lost_batalion.net.messages.PlayerDropped;
import io.jababa.lost_batalion.net.messages.ResumeMatch;
import io.jababa.lost_batalion.net.messages.ResyncAck;
import io.jababa.lost_batalion.net.messages.ResyncSnapshot;
import io.jababa.lost_batalion.net.messages.TickChecksum;
import io.jababa.lost_batalion.net.messages.TickCommands;

/**
 * Канал матчу: розсилка наказів, звірка хешів і отримання чужих.
 *
 * <p>Свідомо вужчий за {@link LobbySession} — під час матчу склад лоббі вже не
 * змінюється, а от кожен тік їздять команди. Завдяки окремому інтерфейсу
 * lockstep-цикл не знає, чи він у мережевому матчі, чи в одиночній грі: у
 * другому випадку підставляється петля, яка повертає власні команди назад.
 *
 * <p>Потоки — як у лоббі: KryoNet кладе прийняте у власному потоці, а
 * {@link #pump(Listener)} віддає накопичене з потоку рендеру.
 */
public interface MatchTransport {

    /** Номер локального гравця. Хост завжди 0. */
    int getLocalPlayerId();

    /**
     * Номери всіх учасників матчу, зафіксовані на старті.
     *
     * <p>Саме за цим списком lockstep вирішує, чиїх команд ще бракує, щоб
     * виконати тік. Склад під час матчу не змінюється: гравець, що вийшов,
     * лишається в списку, і його зникнення — окрема історія (етап 9).
     */
    int[] getPlayerIds();

    /**
     * Чи ця сторона — хост.
     *
     * <p>Розбіжність хешів бачить кожен клієнт самостійно, бо хеші
     * ретранслюються всім. Але оголошує десинхрон саме хост: інакше при трьох
     * і більше учасниках кожен назвав би винним усіх інших, і з'ясувати, чий
     * стан еталонний, стало б неможливо.
     */
    boolean isHost();

    /** Розіслати свої накази на конкретний тік. Викликається кожен тік, навіть порожнім. */
    void sendCommands(TickCommands commands);

    /** Розіслати свій хеш стану на контрольному тіку. */
    void sendChecksum(TickChecksum checksum);

    /** Тільки хост: оголосити розбіжність. У решти виклик нічого не робить. */
    void sendDesyncAlert(DesyncAlert alert);

    /** Тільки хост: роздати еталонний стан. */
    void sendResyncSnapshot(ResyncSnapshot snapshot);

    /** Клієнт → хост: знімок застосовано (або не застосовано). */
    void sendResyncAck(ResyncAck ack);

    /** Тільки хост: усі синхронізовані, продовжуємо. */
    void sendResumeMatch(ResumeMatch resume);

    /** Тільки хост: гравця більше немає в матчі з указаного тіку. */
    void sendPlayerDropped(PlayerDropped dropped);

    /** Віддати прийняте слухачеві. Викликається з потоку рендеру. */
    void pump(Listener listener);

    /** Чи канал ще живий. */
    boolean isConnected();

    /** Закрити канал і звільнити ресурси. */
    void close();

    interface Listener {
        /** Прийшли накази одного гравця на один тік (можливо, порожні). */
        void onTickCommands(TickCommands commands);

        /** Прийшов чужий хеш стану — є що звірити. */
        void onChecksum(TickChecksum checksum);

        /** Хост оголосив десинхрон: далі грати немає сенсу. */
        void onDesyncAlert(DesyncAlert alert);

        /** Прийшов еталонний стан — треба застосувати й підтвердити. */
        void onResyncSnapshot(ResyncSnapshot snapshot);

        /** Тільки хост чує: хтось підтвердив застосування знімка. */
        void onResyncAck(ResyncAck ack);

        /** Дозвіл продовжувати з нового покоління. */
        void onResumeMatch(ResumeMatch resume);

        /**
         * Тільки хост чує: чиєсь з'єднання обірвалось.
         *
         * <p>Це ще НЕ виключення з матчу — лише факт розриву. Рішення й
         * спільний тік призначає хост окремо, через {@link PlayerDropped}.
         */
        void onPlayerLost(int playerId);

        /** Гравця виключено з матчу з тіку {@code effectiveTick}. */
        void onPlayerDropped(PlayerDropped dropped);

        /**
         * ВЛАСНИЙ канал обірвався — далі тіків не буде.
         *
         * <p>Не плутати з {@link #onPlayerLost}: там зник хтось інший і матч
         * може тривати, тут зник шлях назовні. Для гостя це означає, що хост
         * пропав, а хост — це ще й ретранслятор, тож грати нема з ким.
         */
        void onDisconnected(String reason);
    }
}
