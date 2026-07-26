package io.jababa.lost_batalion.net.messages;

import java.util.ArrayList;

/**
 * Повний стан лоббі. Хост розсилає його всім після кожної зміни
 * (приєднання, вихід, зміна готовності) — клієнти лоббі стану не виводять самі,
 * тільки відображають те, що прислав хост.
 */
public class LobbyState {

    public String lobbyName;
    public String scenarioId;
    public int    maxPlayers;
    public LobbyStatus status;

    /** Порядок слотів — за playerId, зростанням. Хост гарантує його при відправці. */
    public ArrayList<PlayerSlot> slots = new ArrayList<>();

    public LobbyState() {}

    public PlayerSlot findSlot(int playerId) {
        for (int i = 0; i < slots.size(); i++) {
            PlayerSlot s = slots.get(i);
            if (s.playerId == playerId) return s;
        }
        return null;
    }

    /** Чи всі не-хости підтвердили готовність (хост завжди ready). */
    public boolean allReady() {
        if (slots.size() < 2) return false;
        for (int i = 0; i < slots.size(); i++) {
            if (!slots.get(i).ready) return false;
        }
        return true;
    }
}
