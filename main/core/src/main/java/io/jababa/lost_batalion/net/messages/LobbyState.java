package io.jababa.lost_batalion.net.messages;

import io.jababa.lost_batalion.net.NetConfig;

import java.util.ArrayList;

/**
 * Повний стан лоббі. Хост розсилає його всім після кожної зміни
 * (приєднання, вихід, зміна команди, бот, готовність) — клієнти лоббі стану не
 * виводять самі, тільки відображають те, що прислав хост.
 *
 * <p>Список {@link #slots} несе ЛИШЕ зайняті місця — і гравців у командах, і
 * тих, хто чекає ({@code team == TEAM_NONE}). Порожні й закриті місця в ньому
 * не представлені: перші не мають чого нести, а другі — це лише прапорець,
 * тому вони живуть у масках {@link #closedMask}. Так само дешевше по мережі і,
 * головне, немає двох способів записати те саме.
 */
public class LobbyState {

    public String lobbyName;
    public String scenarioId;
    public int    maxPlayers;
    public LobbyStatus status;

    /** Порядок слотів — за playerId, зростанням. Хост гарантує його при відправці. */
    public ArrayList<PlayerSlot> slots = new ArrayList<>();

    /**
     * Закриті місця, по біту на місце: {@code closedMask[team] & (1 << seat)}.
     *
     * <p>Маскою, а не масивом {@code boolean[][]}: Kryo довелось би реєструвати
     * ще два типи масивів, а тут це два числа, які завжди їдуть цілими.
     */
    public int[] closedMask = new int[NetConfig.TEAM_COUNT];

    public LobbyState() {}

    public PlayerSlot findSlot(int playerId) {
        for (int i = 0; i < slots.size(); i++) {
            PlayerSlot s = slots.get(i);
            if (s.playerId == playerId) return s;
        }
        return null;
    }

    /** Хто сидить на цьому місці, або null. */
    public PlayerSlot occupant(int team, int seat) {
        for (int i = 0; i < slots.size(); i++) {
            PlayerSlot s = slots.get(i);
            if (s.team == team && s.seat == seat) return s;
        }
        return null;
    }

    public boolean isClosed(int team, int seat) {
        if (closedMask == null || team < 0 || team >= closedMask.length) return false;
        return (closedMask[team] & (1 << seat)) != 0;
    }

    /** Місце вільне: ніким не зайняте й не закрите хостом. */
    public boolean isOpen(int team, int seat) {
        return !isClosed(team, seat) && occupant(team, seat) == null;
    }

    /** Ті, хто ще не обрав сторону. Порядок — як у {@link #slots}. */
    public ArrayList<PlayerSlot> waitlist() {
        ArrayList<PlayerSlot> list = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).seated()) list.add(slots.get(i));
        }
        return list;
    }

    public int seatedCount(int team) {
        int n = 0;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).seated() && slots.get(i).team == team) n++;
        }
        return n;
    }

    /** Скільки учасників піде в матч — обидві команди разом, без списку очікування. */
    public int seatedCount() {
        int n = 0;
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).seated()) n++;
        return n;
    }

    /**
     * Чи можна стартувати.
     *
     * <p>Три умови, і кожна закриває свій спосіб зіпсувати матч: обидві сторони
     * мають бути заселені (інакше перемога настає на першій же перевірці
     * анігіляції), усі, хто сидить, — підтвердити готовність, і всі бути на
     * зв'язку. Ті, хто в списку очікування, не рахуються НІДЕ: вони в матч не
     * йдуть, і чекати їхньої готовності означало б не стартувати ніколи.
     */
    public boolean allReady() {
        for (int team = 0; team < NetConfig.TEAM_COUNT; team++) {
            if (seatedCount(team) == 0) return false;
        }
        for (int i = 0; i < slots.size(); i++) {
            PlayerSlot s = slots.get(i);
            if (!s.seated()) continue;
            if (!s.ready || !s.connected) return false;
        }
        return true;
    }
}
