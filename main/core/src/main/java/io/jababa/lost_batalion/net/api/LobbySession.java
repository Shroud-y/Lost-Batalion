package io.jababa.lost_batalion.net.api;

import io.jababa.lost_batalion.net.messages.LobbyState;
import io.jababa.lost_batalion.net.messages.StartMatch;

/**
 * Участь у конкретному лоббі — з боку хоста або гостя.
 *
 * <p>Обидві ролі за цим інтерфейсом однакові, різниця лише в тому, що
 * {@link #startMatch()} і {@link #setScenario(String)} дозволені тільки хосту.
 * Так екран лоббі не роздвоюється на «хостовий» і «гостьовий».
 *
 * <p>Потоки: KryoNet викликає свої слухачі у власному потоці, а libGDX-віджети
 * можна чіпати лише з потоку рендеру. Тому події не приходять напряму — вони
 * накопичуються всередині і віддаються слухачам під час {@link #pump()},
 * який екран викликає зі свого {@code render}.
 */
public interface LobbySession {

    /** Стан лоббі, як його бачить хост. Може бути null до першого оновлення. */
    LobbyState getState();

    /** Номер локального гравця. Хост завжди 0. */
    int getLocalPlayerId();

    boolean isHost();

    /** Чи з'єднання ще живе. */
    boolean isConnected();

    /** Гість повідомляє про готовність. Для хоста не робить нічого. */
    void setReady(boolean ready);

    /** Тільки хост: змінити карту. */
    void setScenario(String scenarioId);

    /** Тільки хост: запустити матч. Викликати лише коли всі готові. */
    void startMatch();

    /** Свідомий вихід. Звільняє слот одразу, на відміну від обриву зв'язку. */
    void leave();

    /**
     * Перейти від лоббі до матчу: той самий канал, інший набір повідомлень.
     *
     * <p>З'єднання не переустановлюється — воно вже є і вже пройшло перевірку
     * версії протоколу. Склад матчу фіксується тим, що приїхало у
     * {@code start}: далі він не змінюється, і саме за ним lockstep рахує,
     * чиїх наказів чекати.
     *
     * <p>Закриття отриманого транспорту закриває і саму сесію — після матчу
     * лоббі вже нема куди повертатись.
     *
     * @return канал матчу або null, якщо сесія вже мертва
     */
    MatchTransport openMatch(StartMatch start);

    /**
     * Віддати накопичені мережеві події слухачеві. Викликається з потоку рендеру
     * кожен кадр — саме тут, і тільки тут, UI дізнається про зміни.
     */
    void pump(Listener listener);

    interface Listener {
        /** Прийшов новий склад лоббі — перемалювати список гравців. */
        void onLobbyState(LobbyState state);

        /** Хост натиснув Старт: параметри матчу отримані, час завантажувати карту. */
        void onMatchStarting(StartMatch start);

        /** Помилка, після якої лоббі ще живе (наприклад «нік зайнятий»). */
        void onError(String message);

        /** З'єднання втрачено або хост закрив лоббі — далі тільки назад у меню. */
        void onDisconnected(String reason);
    }

    /** Заготовка, щоб екрани перевизначали лише потрібні методи. */
    abstract class Adapter implements Listener {
        @Override public void onLobbyState(LobbyState state) {}
        @Override public void onMatchStarting(StartMatch start) {}
        @Override public void onError(String message) {}
        @Override public void onDisconnected(String reason) {}
    }
}
