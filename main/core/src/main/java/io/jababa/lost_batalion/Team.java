package io.jababa.lost_batalion;

/**
 * Сторона конфлікту. У матчі 1v1 сторін рівно дві, і кожна прив'язана до
 * номера гравця з лоббі: хост (playerId 0) грає за {@link #PLAYER}, гість
 * (playerId 1) — за {@link #ENEMY}.
 *
 * <p>Назви лишились із одиночної гри й тепер трохи вводять в оману: для гостя
 * «ENEMY» — це його власна армія. Перейменування зачепило б асети і половину
 * рендеру, тому замість нього є {@link #forPlayer(int)} і {@link #playerId()} —
 * єдине місце, де ця відповідність зафіксована.
 */
public enum Team {
    PLAYER,
    ENEMY;

    /** Команда, якою командує гравець із цим номером. */
    public static Team forPlayer(int playerId) {
        return playerId == 0 ? PLAYER : ENEMY;
    }

    /** Номер гравця, що володіє цією командою. */
    public int playerId() {
        return this == PLAYER ? 0 : 1;
    }

    /** Протилежна сторона. У 1v1 вона єдина. */
    public Team opponent() {
        return this == PLAYER ? ENEMY : PLAYER;
    }
}
