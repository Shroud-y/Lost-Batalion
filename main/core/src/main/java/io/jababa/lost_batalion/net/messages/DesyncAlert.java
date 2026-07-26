package io.jababa.lost_batalion.net.messages;

/**
 * Хост → усім: чийсь хеш розійшовся з хостовим.
 *
 * <p>Отримавши це, КОЖЕН клієнт (включно з тими, чий хеш збігся) ставить
 * симуляцію на паузу і показує модальне вікно: продовжувати матч із розбіжним
 * станом не можна, бо далі розбіжність лише зростатиме.
 */
public class DesyncAlert {

    /** Контрольний тік, на якому виявлено розбіжність. */
    public int tick;

    /** Хто розійшовся. Може бути кілька, якщо клієнтів більше двох. */
    public int[]    desyncedPlayerIds;
    /** Ніки тих самих гравців — щоб UI не ліз у стан лоббі за розшифровкою. */
    public String[] desyncedNicks;

    /** Еталонний хеш хоста — тільки для логів і діагностики. */
    public long hostChecksum;

    public DesyncAlert() {}

    public DesyncAlert(int tick, int[] desyncedPlayerIds, String[] desyncedNicks, long hostChecksum) {
        this.tick = tick;
        this.desyncedPlayerIds = desyncedPlayerIds;
        this.desyncedNicks     = desyncedNicks;
        this.hostChecksum      = hostChecksum;
    }
}
