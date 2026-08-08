package io.jababa.lost_batalion.net.api;

import io.jababa.lost_batalion.net.NetConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Точка входу в мережу для екранів і реєстр доступних бекендів.
 *
 * <p>Екрани не знають ні про KryoNet, ні про Steam — вони просять тут сесію і
 * далі працюють з {@link LobbySession}. Раніше цей клас створював Kryo-класи
 * напряму; тепер він делегує ОБРАНОМУ {@link NetBackend}, а самі бекенди
 * реєструються ззовні.
 *
 * <p>Чому реєстр, а не {@code if (steam)}: реалізація Steam тягне нативні
 * бібліотеки, яких немає на Android, а модуль {@code :android} залежить від
 * {@code core}. Реєстрація ззовні лишає core чистим — десктопний лаунчер додає
 * свій бекенд, мобільний не додає нічого й отримує саму лише локальну мережу.
 *
 * <p>Локальна мережа зареєстрована завжди і першою. Вона ж — запасний варіант:
 * {@link #select(String)} на недоступний бекенд мовчки лишає поточний, тож гра
 * не може опинитись узагалі без мережі через те, що Steam не запустився.
 */
public final class MultiplayerServices {

    private MultiplayerServices() {}

    private static final List<NetBackend> backends = new ArrayList<>();
    private static NetBackend current;

    static {
        register(new LanBackend());
    }

    // ── Реєстр ────────────────────────────────────────────────────────────

    /**
     * Додати бекенд. Викликається з лаунчера ДО створення гри.
     *
     * <p>Повторна реєстрація того самого {@code id} замінює попередню, а не
     * додає другий рядок у меню.
     */
    public static void register(NetBackend backend) {
        if (backend == null) return;
        for (int i = 0; i < backends.size(); i++) {
            if (backends.get(i).id().equals(backend.id())) {
                backends.set(i, backend);
                if (current != null && current.id().equals(backend.id())) current = backend;
                return;
            }
        }
        backends.add(backend);
        if (current == null) current = backend;
    }

    /**
     * Звільнити аватарки ВСІХ бекендів, а не лише обраного.
     *
     * <p>Гравець міг перемкнути мережу посеред сеансу, і текстури, набрані
     * попереднім бекендом, лишились би висіти. Кличеться один раз, з
     * {@code LostBatalion.dispose()}.
     */
    public static void disposeAvatars() {
        for (int i = 0; i < backends.size(); i++) backends.get(i).avatars().dispose();
    }

    /** Усі зареєстровані, у порядку реєстрації. Локальна мережа завжди перша. */
    public static List<NetBackend> getBackends() {
        return java.util.Collections.unmodifiableList(backends);
    }

    public static NetBackend current() {
        // Порожнім список бути не може — LanBackend реєструється в static-блоці.
        return current != null ? current : backends.get(0);
    }

    public static NetBackend find(String id) {
        if (id == null) return null;
        for (int i = 0; i < backends.size(); i++) {
            if (backends.get(i).id().equals(id)) return backends.get(i);
        }
        return null;
    }

    /**
     * Перемкнути активний бекенд.
     *
     * @return чи вдалось. Невідомий або недоступний id лишає поточний —
     *         збережений вибір «steam» на збірці без Steam має тихо відкотитись
     *         до локальної мережі, а не залишити гравця з мертвим меню.
     */
    public static boolean select(String id) {
        NetBackend found = find(id);
        if (found == null || !found.isAvailable()) return false;
        current = found;
        return true;
    }

    /** Такт бекенда, раз на кадр. Див. {@link NetBackend#pump()}. */
    public static void pumpBackend() {
        for (int i = 0; i < backends.size(); i++) {
            // Такт дістається ВСІМ, а не лише активному: Steam мусить крутити
            // свої колбеки навіть коли в меню обрана локальна мережа, інакше
            // він не дізнається ні про запрошення, ні про власний стан.
            backends.get(i).pump();
        }
    }

    // ── Делегування (сумісність із наявними екранами) ─────────────────────

    public static boolean isNetworkingAvailable() { return current().isAvailable(); }

    public static LobbyDirectory createDirectory() { return current().createDirectory(); }

    /**
     * Створити лоббі і стати його хостом.
     *
     * <p>Тут же фіксується затримка вводу цього матчу: її вимагає МЕРЕЖА, а
     * розсилає всім хост у {@code StartMatch}. Робиться саме при створенні
     * лоббі, а не при старті матчу, щоб хост і сам грав із тим числом, яке
     * розіслав.
     */
    public static LobbySession host(String lobbyName, String nick, String scenarioId, int maxPlayers) {
        NetConfig.setInputDelayTicks(current().inputDelayTicks());
        return current().host(lobbyName, nick, scenarioId, maxPlayers);
    }

    /** Приєднатись до знайденого в мережі лоббі. */
    public static LobbySession join(DiscoveredLobby lobby, String nick) {
        return current().join(lobby, nick);
    }

    /** Приєднатись за введеним вручну рядком. Що він означає — вирішує бекенд. */
    public static LobbySession joinByAddress(String address, String nick) {
        return current().joinByAddress(address, nick);
    }

    // ── Розбір адреси (потрібен LanBackend і екранам) ─────────────────────

    /** Розбирає "192.168.0.5:54555" на хост і порт; без порту бере дефолтний. */
    public static String parseHost(String address) {
        int colon = address.lastIndexOf(':');
        return colon < 0 ? address.trim() : address.substring(0, colon).trim();
    }

    public static int parsePort(String address) {
        int colon = address.lastIndexOf(':');
        if (colon < 0) return NetConfig.TCP_PORT;
        try {
            return Integer.parseInt(address.substring(colon + 1).trim());
        } catch (NumberFormatException e) {
            return NetConfig.TCP_PORT;
        }
    }
}
