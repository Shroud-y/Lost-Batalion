package io.jababa.lost_batalion.capture;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.sim.StateChecksum;
import io.jababa.lost_batalion.sim.TickRate;
import io.jababa.lost_batalion.units.Unit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Точки захоплення матчу: стан і крок симуляції.
 *
 * <h3>Правила</h3>
 * <ul>
 *   <li>У зоні стоять юніти лише однієї сторони → прогрес цієї сторони росте.</li>
 *   <li>У зоні обидві сторони → нічия, прогрес завмирає. Спершу треба вибити.</li>
 *   <li>Зона порожня → прогрес осідає назад, удвічі повільніше, ніж набирався.
 *       Захоплена точка при цьому осідає до СВОГО повного значення, а не до
 *       нуля: втратити село, просто пішовши з нього, було б дивно.</li>
 *   <li>Чужа сторона спершу зриває чуже володіння (прогрес до нуля), і лише
 *       тоді починає набирати своє.</li>
 * </ul>
 *
 * <p>Кількість юнітів у зоні на швидкість не впливає: інакше захоплення
 * зводилося б до того, хто пригнав більший натовп, а не хто втримав місце.
 *
 * <h3>Чому це симуляція, а не візуал</h3>
 * Прогрес — цілочисельний і рахується в ТІКАХ, як усі таймери гри. Він входить
 * у checksum і в знімок для ресинку: два клієнти з різним власником села бачили
 * б різний матч.
 */
public class CaptureManager {

    /** Повний прогрес. Набирається за {@code FULL / GAIN} тіків. */
    public static final int FULL = TickRate.TICKS_PER_SECOND * 12 * 2;   // 960

    /** Приріст за тік, коли в зоні стоїть рівно одна сторона → 12 секунд на захоплення. */
    private static final int GAIN = 2;

    /** Осідання за тік, коли зона порожня — удвічі повільніше за набір. */
    private static final int DECAY = 1;

    private final Array<CapturePoint> points = new Array<>();

    /**
     * Побудувати точки зі сценарію.
     *
     * <p>Зони — константи в коді сценарію, тобто однакові на всіх клієнтах за
     * побудовою; порядок теж заданий списком. Саме тому в знімок стану точки не
     * їдуть цілком — тільки прогрес і власник.
     *
     * <p>Раніше точки виводились із плям VILLAGE у масці лісу, а зоною було
     * коло сталого радіуса 70. Форма села при цьому ні на що не впливала:
     * межа захоплення могла лежати посеред річки або зрізати половину хат,
     * а два хутори, розділені дорогою, доводилось зливати порогом відстані.
     * Тепер межу задає той, хто малює карту.
     *
     * @param zones список зон сценарію; {@code null} або порожній — точок немає
     */
    public CaptureManager(CaptureZone[] zones) {
        if (zones == null) return;
        for (int i = 0; i < zones.length; i++) {
            if (zones[i] != null) points.add(new CapturePoint(zones[i]));
        }
    }

    public Array<CapturePoint> getPoints() { return points; }

    /** Скільки точок утримує сторона. Знадобиться умовам перемоги. */
    public int countOwned(Team team) {
        int n = 0;
        for (int i = 0; i < points.size; i++) if (points.get(i).owner == team) n++;
        return n;
    }

    // ── Крок симуляції ────────────────────────────────────────────────────

    public void tick(Array<Unit> units) {
        for (int i = 0; i < points.size; i++) tickPoint(points.get(i), units);
    }

    private void tickPoint(CapturePoint p, Array<Unit> units) {
        boolean player = false, enemy = false;
        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            if (!u.alive) continue;
            // Зламаний у зоні не стоїть, а біжить крізь неї. Село тримає той,
            // хто там ЛИШИВСЯ: рота, яку зірвало з місця, втрачає точку так
            // само, як втратила б її, полігши. Це найдорожчий наслідок
            // моралі — і головна причина не давати їй догоряти.
            if (u.isBroken()) continue;
            // Видимість тут ні до чого: захоплення — фізична присутність, а не
            // те, що хтось бачить. Інакше точку можна було б утримувати,
            // ховаючись у лісі від власного ж прапора.
            if (!p.contains(u.x, u.y)) continue;
            if (u.team == Team.PLAYER) player = true; else enemy = true;
            if (player && enemy) break;
        }

        if (player && enemy) return;                    // нічия — прогрес завмер

        Team present = player ? Team.PLAYER : (enemy ? Team.ENEMY : null);

        if (present == null) {
            decay(p);
            return;
        }

        if (p.holder == null) p.holder = present;

        if (p.holder == present) {
            p.progress += GAIN;
            if (p.progress >= FULL) {
                p.progress = FULL;
                p.owner    = present;
            }
        } else {
            // Спершу зірвати чуже: зона гасне до нуля і аж потім набирається
            // кольором того, хто прийшов.
            p.progress -= GAIN;
            if (p.progress <= 0) {
                p.progress = 0;
                p.holder   = present;
                p.owner    = null;
            }
        }
    }

    /** Порожня зона: прогрес повзе назад до стану, у якому точка лишалась. */
    private void decay(CapturePoint p) {
        if (p.owner != null && p.holder == p.owner) {
            // Своя ж точка — тримається повною.
            if (p.progress < FULL) p.progress = Math.min(FULL, p.progress + DECAY);
            return;
        }

        p.progress -= DECAY;
        if (p.progress > 0) return;

        p.progress = 0;
        if (p.owner == null) {
            p.holder = null;                 // нейтральна і порожня — усе з нуля
        } else {
            p.holder = p.owner;              // недозахоплення зірвалось, село лишилось чужим
        }
    }

    // ── Checksum і знімок ─────────────────────────────────────────────────

    public long stateDigest(long h) {
        h = StateChecksum.fold(h, points.size);
        for (int i = 0; i < points.size; i++) {
            CapturePoint p = points.get(i);
            h = StateChecksum.fold(h, p.progress);
            h = StateChecksum.fold(h, teamCode(p.owner));
            h = StateChecksum.fold(h, teamCode(p.holder));
        }
        return h;
    }

    /**
     * У знімок їдуть лише прогрес і сторони: центри точок беруться з маски,
     * тобто з ассетів, і розійтись не можуть.
     */
    public void writeSnapshot(DataOutputStream out) throws IOException {
        out.writeInt(points.size);
        for (int i = 0; i < points.size; i++) {
            CapturePoint p = points.get(i);
            out.writeInt(p.progress);
            out.writeByte(teamCode(p.owner));
            out.writeByte(teamCode(p.holder));
        }
    }

    public void readSnapshot(DataInputStream in) throws IOException {
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int  progress = in.readInt();
            Team owner    = teamOf(in.readByte());
            Team holder   = teamOf(in.readByte());
            // Знімок із іншою кількістю точок означає іншу карту — читаємо його
            // до кінця (потік спільний), але застосувати нема куди.
            if (i >= points.size) continue;
            CapturePoint p = points.get(i);
            p.progress = progress;
            p.owner    = owner;
            p.holder   = holder;
        }
    }

    private static int teamCode(Team t) { return t == null ? -1 : t.index(); }

    private static Team teamOf(int code) { return code < 0 ? null : Team.byIndex(code); }
}
