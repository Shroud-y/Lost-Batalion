package io.jababa.lost_batalion.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Книга хешів: зводить свій хеш із чужими і каже, коли вони розійшлись.
 *
 * <h3>Чому не «хост порівняв і всім сказав»</h3>
 * Так теж робиться (див. {@code DesyncAlert}), але це не єдиний механізм.
 * Хеші ретранслюються всім, тож кожен клієнт бачить розбіжність самостійно —
 * і бачить її навіть тоді, коли з хостом уже щось не так. Оголошення хоста
 * лишається для однозначної відповіді «чий стан еталонний».
 *
 * <h3>Про час прибуття</h3>
 * Чужий хеш на тік N може прийти і раніше, і пізніше за момент, коли цей
 * клієнт сам виконає тік N. Тому обидві сторони просто складаються в комірку
 * тіку, а порівняння відбувається щоразу, коли комірка поповнилась. Комірки
 * старші за {@link #KEEP_TICKS} викидаються: тримати всю історію матчу немає
 * потреби, а от текти пам'яттю по 40 разів на секунду — цілком.
 */
public class ChecksumLedger {

    /** Скільки тіків історії тримати. Із запасом на найповільніше з'єднання. */
    private static final int KEEP_TICKS = 40 * 60;   // хвилина гри

    /** tick → (playerId → хеш). */
    private final Map<Integer, Map<Integer, Entry>> byTick = new HashMap<>();

    /** Тіки, на яких порівняння вже відбулось — щоб не сигналити двічі. */
    private final Map<Integer, Boolean> compared = new HashMap<>();

    private Mismatch mismatch;

    /** Один запис: зведений хеш і покомпонентна розбивка (може бути null). */
    public static final class Entry {
        public final long   checksum;
        public final long[] components;
        Entry(long checksum, long[] components) {
            this.checksum   = checksum;
            this.components = components;
        }
    }

    /** Знайдена розбіжність — усе, що потрібно для лога й для UI. */
    public static final class Mismatch {
        public final int tick;
        public final int localPlayerId;
        public final List<Integer> disagreeingPlayers = new ArrayList<>();
        public final long localChecksum;
        public final String componentSummary;

        Mismatch(int tick, int localPlayerId, long localChecksum, String componentSummary) {
            this.tick             = tick;
            this.localPlayerId    = localPlayerId;
            this.localChecksum    = localChecksum;
            this.componentSummary = componentSummary;
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder("десинхрон на тіку ").append(tick).append(": ");
            for (int i = 0; i < disagreeingPlayers.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("гравець #").append(disagreeingPlayers.get(i));
            }
            sb.append(" розійшовся; розбіжні компоненти: ").append(componentSummary);
            return sb.toString();
        }
    }

    /**
     * Записати хеш і спробувати звірити.
     *
     * @return щойно знайдена розбіжність або null
     */
    public Mismatch record(int tick, int playerId, long checksum, long[] components,
                           int localPlayerId, int[] playerIds) {
        byTick.computeIfAbsent(tick, t -> new HashMap<>())
              .putIfAbsent(playerId, new Entry(checksum, components));

        Mismatch found = compare(tick, localPlayerId, playerIds);
        prune(tick);
        return found;
    }

    private Mismatch compare(int tick, int localPlayerId, int[] playerIds) {
        if (Boolean.TRUE.equals(compared.get(tick))) return null;

        Map<Integer, Entry> slot = byTick.get(tick);
        if (slot == null) return null;

        Entry local = slot.get(localPlayerId);
        if (local == null) return null;      // ще не дійшли до цього тіку самі

        // Порівнюємо лише коли зібрались усі: інакше при трьох учасниках можна
        // оголосити винним того, чий хеш просто прийшов першим.
        for (int i = 0; i < playerIds.length; i++) {
            if (!slot.containsKey(playerIds[i])) return null;
        }

        Mismatch found = null;
        for (int i = 0; i < playerIds.length; i++) {
            int pid = playerIds[i];
            if (pid == localPlayerId) continue;
            Entry other = slot.get(pid);
            if (other.checksum == local.checksum) continue;

            if (found == null) {
                found = new Mismatch(tick, localPlayerId, local.checksum,
                    StateChecksum.describeMismatch(local.components, other.components));
            }
            found.disagreeingPlayers.add(pid);
        }

        compared.put(tick, Boolean.TRUE);
        if (found != null && mismatch == null) mismatch = found;
        return found;
    }

    private void prune(int currentTick) {
        if (byTick.size() <= 4) return;   // дешева відсічка: майже завжди тут і виходимо
        int cutoff = currentTick - KEEP_TICKS;
        byTick.keySet().removeIf(t -> t < cutoff);
        compared.keySet().removeIf(t -> t < cutoff);
    }

    /** Перша знайдена розбіжність або null. Далі вона вже не змінюється. */
    public Mismatch getMismatch() { return mismatch; }

    public boolean hasMismatch() { return mismatch != null; }

    /** Позначити розбіжність ззовні — коли її оголосив хост, а ми ще не звірили самі. */
    public void adoptExternal(int tick, int[] desyncedPlayers, long hostChecksum) {
        if (mismatch != null) return;
        Mismatch m = new Mismatch(tick, -1, hostChecksum, "за оголошенням хоста");
        if (desyncedPlayers != null)
            for (int i = 0; i < desyncedPlayers.length; i++) m.disagreeingPlayers.add(desyncedPlayers[i]);
        mismatch = m;
    }

    public void clear() {
        byTick.clear();
        compared.clear();
        mismatch = null;
    }
}
