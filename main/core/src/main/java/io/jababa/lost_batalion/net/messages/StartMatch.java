package io.jababa.lost_batalion.net.messages;

import java.util.ArrayList;

/**
 * Хост → усім: матч починається.
 *
 * <p>Крім складу гравців несе всі параметри, від яких залежить симуляція. Вони
 * дублюють константи NetConfig навмисно: якщо у когось збірка з іншим tick rate
 * або іншим input delay, це видно одразу при старті, а не через десинхрон на
 * сотому тіку.
 */
public class StartMatch {

    public int protocolVersion;

    /** Seed єдиного ігрового RNG. Один на матч, однаковий у всіх. */
    public long rngSeed;

    public String scenarioId;

    /** Склад гравців, зафіксований на момент старту. Порядок — за playerId. */
    public ArrayList<PlayerSlot> slots = new ArrayList<>();

    public int tickRate;
    public int inputDelayTicks;
    public int checksumIntervalTicks;

    public StartMatch() {}
}
