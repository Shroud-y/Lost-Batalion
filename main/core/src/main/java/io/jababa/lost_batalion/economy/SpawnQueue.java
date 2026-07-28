package io.jababa.lost_batalion.economy;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.sim.StateChecksum;
import io.jababa.lost_batalion.sim.TickRate;
import io.jababa.lost_batalion.units.UnitType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Черга замовлених військ.
 *
 * <p>Замовлення не перетворюється на юніта одразу: дві секунди воно стоїть на
 * карті напівпрозорим силуетом, і за цей час його можна скасувати з поверненням
 * золота. Пауза потрібна не для балансу, а для промаху: точка висадки
 * задається одним кліком по карті, і клік легко зробити не туди.
 *
 * <p>Черга — стан симуляції. Номери замовлень роздаються лічильником, який
 * рухається лише при виконанні команд, а команди виконуються в однаковому
 * порядку в усіх, тож id збігаються на всіх клієнтах — інакше скасування
 * вбивало б різні замовлення у різних гравців.
 */
public class SpawnQueue {

    /** Скільки триває вікно скасування — 2 секунди. */
    public static final int HOLD_TICKS = TickRate.TICKS_PER_SECOND * 2;

    private final Array<PendingSpawn> pending = new Array<>();

    /** Готові до появи цього тіку. Одне поле на чергу — щоб не смітити щотіку. */
    private final Array<PendingSpawn> ready = new Array<>();

    private int nextId;

    public Array<PendingSpawn> getPending() { return pending; }

    /** Замовлення в чергу. Золото списує викликач — черга про нього не знає. */
    public PendingSpawn add(int playerId, UnitType type, long x, long y) {
        PendingSpawn spawn = new PendingSpawn(nextId++, playerId, type, x, y, HOLD_TICKS);
        pending.add(spawn);
        return spawn;
    }

    /**
     * Зняти замовлення.
     *
     * <p>Чуже замовлення зняти не можна: id їздять по мережі, і без перевірки
     * власника підроблене повідомлення скасовувало б чужі війська.
     *
     * @return зняте замовлення або {@code null}, якщо його вже немає
     */
    public PendingSpawn cancel(int playerId, int spawnId) {
        for (int i = 0; i < pending.size; i++) {
            PendingSpawn s = pending.get(i);
            if (s.id != spawnId || s.playerId != playerId) continue;
            pending.removeIndex(i);
            return s;
        }
        return null;
    }

    /**
     * Крок черги.
     *
     * @return замовлення, чий час вийшов цього тіку; список дійсний до
     *         наступного виклику
     */
    public Array<PendingSpawn> tick() {
        ready.clear();
        for (int i = pending.size - 1; i >= 0; i--) {
            PendingSpawn s = pending.get(i);
            if (--s.ticksLeft > 0) continue;
            pending.removeIndex(i);
            ready.add(s);
        }
        // Знято з кінця — відновлюємо порядок замовлення, бо від нього залежать
        // id новостворених юнітів.
        ready.reverse();
        return ready;
    }

    // ── Checksum і знімок ─────────────────────────────────────────────────

    public long stateDigest(long h) {
        h = StateChecksum.fold(h, nextId);
        h = StateChecksum.fold(h, pending.size);
        for (int i = 0; i < pending.size; i++) {
            PendingSpawn s = pending.get(i);
            h = StateChecksum.fold(h, s.id);
            h = StateChecksum.fold(h, s.playerId);
            h = StateChecksum.fold(h, s.type.ordinal());
            h = StateChecksum.fold(h, s.x);
            h = StateChecksum.fold(h, s.y);
            h = StateChecksum.fold(h, s.ticksLeft);
        }
        return h;
    }

    public void writeSnapshot(DataOutputStream out) throws IOException {
        out.writeInt(nextId);
        out.writeInt(pending.size);
        for (int i = 0; i < pending.size; i++) {
            PendingSpawn s = pending.get(i);
            out.writeInt(s.id);
            out.writeInt(s.playerId);
            out.writeInt(s.type.ordinal());
            out.writeLong(s.x);
            out.writeLong(s.y);
            out.writeInt(s.ticksLeft);
        }
    }

    public void readSnapshot(DataInputStream in) throws IOException {
        pending.clear();
        nextId = in.readInt();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int  id       = in.readInt();
            int  playerId = in.readInt();
            UnitType type = UnitType.byOrdinal(in.readInt());
            long x        = in.readLong();
            long y        = in.readLong();
            int  ticks    = in.readInt();
            if (type == null) continue;   // тип із чужої збірки — просто зникає
            pending.add(new PendingSpawn(id, playerId, type, x, y, ticks));
        }
    }
}
