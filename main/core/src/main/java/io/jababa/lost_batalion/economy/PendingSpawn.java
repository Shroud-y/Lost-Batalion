package io.jababa.lost_batalion.economy;

import io.jababa.lost_batalion.units.UnitType;

/**
 * Замовлене, але ще не прибуле військо.
 *
 * <p>Живе рівно {@link SpawnQueue#HOLD_TICKS} тіків: поки таймер іде, гравець
 * бачить на карті напівпрозорий силует і може передумати — клік по ньому
 * скасовує замовлення й повертає золото. Коли таймер вичерпано, юніт з'являється
 * на краю карти біля свого гравця й вирушає в цю точку.
 *
 * <p>Це стан симуляції: від нього залежать і золото, і поява юніта.
 */
public class PendingSpawn {

    /** Номер замовлення. Роздає {@link SpawnQueue}; ним же скасовують. */
    public final int id;

    public final int      playerId;
    public final UnitType type;

    /** Куди військо має прийти, Q47.16. */
    public final long x, y;

    /** Скільки тіків лишилось до появи. */
    public int ticksLeft;

    public PendingSpawn(int id, int playerId, UnitType type, long x, long y, int ticksLeft) {
        this.id        = id;
        this.playerId  = playerId;
        this.type      = type;
        this.x         = x;
        this.y         = y;
        this.ticksLeft = ticksLeft;
    }

    /** Частка очікування, що вже минула, 0..1 — лише для рендеру. */
    public float elapsed() {
        return 1f - ticksLeft / (float) SpawnQueue.HOLD_TICKS;
    }
}
