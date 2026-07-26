package io.jababa.lost_batalion.net.messages;

/**
 * Хеш повного стану симуляції на контрольному тіку.
 *
 * <p>Маршрут той самий, що й у наказів: клієнт → хост → усі. Кожен звіряє чужі
 * хеші зі своїм і бачить розбіжність самостійно; хост при цьому ще й оголошує
 * її через {@link DesyncAlert}, бо він рівноправний учасник симуляції, але
 * єдиний арбітр.
 *
 * <p>{@code components} — розбивка того самого стану на підсистеми (позиції,
 * здоров'я, таймери, накази, видимість, RNG). Зведений хеш каже лише «щось
 * розійшлось»; розбивка каже «розійшлись позиції, а hp збіглись», і цього
 * майже завжди досить, щоб одразу назвати винну підсистему. Порядок і зміст
 * елементів визначає {@code StateChecksum} — саме тому це просто масив, а не
 * набір іменованих полів.
 */
public class TickChecksum {

    public int  tick;
    public int  playerId;
    public long checksum;

    /** Покомпонентна розбивка. Може бути null — тоді доступне лише зведене порівняння. */
    public long[] components;

    public TickChecksum() {}

    public TickChecksum(int tick, int playerId, long checksum, long[] components) {
        this.tick       = tick;
        this.playerId   = playerId;
        this.checksum   = checksum;
        this.components = components;
    }
}
