package io.jababa.lost_batalion.capture;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.StateChecksum;
import io.jababa.lost_batalion.sim.TickRate;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.units.Unit;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Точки захоплення матчу: пошук, стан і крок симуляції.
 *
 * <h3>Правила</h3>
 * <ul>
 *   <li>У колі стоять юніти лише однієї сторони → прогрес цієї сторони росте.</li>
 *   <li>У колі обидві сторони → нічия, прогрес завмирає. Спершу треба вибити.</li>
 *   <li>Коло порожнє → прогрес осідає назад, удвічі повільніше, ніж набирався.
 *       Захоплена точка при цьому осідає до СВОГО повного значення, а не до
 *       нуля: втратити село, просто пішовши з нього, було б дивно.</li>
 *   <li>Чужа сторона спершу зриває чуже володіння (прогрес до нуля), і лише
 *       тоді починає набирати своє.</li>
 * </ul>
 *
 * <p>Кількість юнітів у колі на швидкість не впливає: інакше захоплення
 * зводилося б до того, хто пригнав більший натовп, а не хто втримав місце.
 *
 * <h3>Чому це симуляція, а не візуал</h3>
 * Прогрес — цілочисельний і рахується в ТІКАХ, як усі таймери гри. Він входить
 * у checksum і в знімок для ресинку: два клієнти з різним власником села бачили
 * б різний матч.
 */
public class CaptureManager {

    /**
     * Радіус кола точки у світових пікселях.
     *
     * <p>Найширше село Жовтих Вод (після злиття сусідніх плям) має 114 пікселів
     * у поперечнику, тобто 57 від центру. 70 накриває його цілком і лишає обід,
     * у якому видно, що юніт зайшов, — але не розповзається на півдолини.
     */
    public static final long RADIUS = Fixed.fromInt(70);

    /** Квадрат радіуса — перевірка «юніт у колі» обходиться без кореня. */
    private static final long RADIUS_SQ = Fixed.mul(RADIUS, RADIUS);

    /** Повний прогрес. Набирається за {@code FULL / GAIN} тіків. */
    public static final int FULL = TickRate.TICKS_PER_SECOND * 12 * 2;   // 960

    /** Приріст за тік, коли в колі стоїть рівно одна сторона → 12 секунд на захоплення. */
    private static final int GAIN = 2;

    /** Осідання за тік, коли коло порожнє — удвічі повільніше за набір. */
    private static final int DECAY = 1;

    private final Array<CapturePoint> points = new Array<>();

    /**
     * Побудувати точки з маски сценарію.
     *
     * <p>Маска — ассет, однаковий у всіх, а обхід детермінований, тож список
     * точок і їхній порядок збігаються на всіх клієнтах. Саме тому точки не
     * входять у знімок стану цілком — тільки їхній прогрес і власник.
     */
    public CaptureManager(TerrainQuery terrain) {
        if (terrain == null) return;
        int[] centers = merge(terrain.villageCenters());
        for (int i = 0; i + 1 < centers.length; i += 2) {
            points.add(new CapturePoint(Fixed.fromInt(centers[i]),
                                        Fixed.fromInt(centers[i + 1])));
        }
    }

    /**
     * Наскільки близькі плями вважаються одним селом (світові пікселі).
     *
     * <p>Село в масці не завжди одна пляма: на Жовтих Водах хутір із двома
     * купками хат розділений дорогою і дає дві плями за 81 піксель одна від
     * одної. Дві точки на такій відстані — це два кола, що накривають одне
     * одного більшою частиною площі, і юніт стоїть одразу в обох.
     *
     * <p>Поріг заданий незалежно від {@link #RADIUS}: він про те, де закінчується
     * одне село, а не про те, як його намальовано.
     */
    private static final int MERGE_DISTANCE = 135;

    /**
     * Злити плями, що лежать ближче за {@link #MERGE_DISTANCE}, в одну точку.
     *
     * <p>Центр групи — середнє її плям, і воно перераховується на кожному
     * додаванні, тож результат залежить лише від порядку плям — а він
     * детермінований, бо задається обходом маски.
     */
    private static int[] merge(int[] centers) {
        int n = centers.length / 2;
        int[] sumX = new int[n], sumY = new int[n], count = new int[n];
        int groups = 0;

        for (int i = 0; i < n; i++) {
            int x = centers[i * 2], y = centers[i * 2 + 1];

            int hit = -1;
            for (int g = 0; g < groups && hit < 0; g++) {
                int dx = sumX[g] / count[g] - x;
                int dy = sumY[g] / count[g] - y;
                if (dx * dx + dy * dy <= MERGE_DISTANCE * MERGE_DISTANCE) hit = g;
            }
            if (hit < 0) hit = groups++;

            sumX[hit] += x;
            sumY[hit] += y;
            count[hit]++;
        }

        int[] out = new int[groups * 2];
        for (int g = 0; g < groups; g++) {
            out[g * 2]     = sumX[g] / count[g];
            out[g * 2 + 1] = sumY[g] / count[g];
        }
        return out;
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
            // Видимість тут ні до чого: захоплення — фізична присутність, а не
            // те, що хтось бачить. Інакше точку можна було б утримувати,
            // ховаючись у лісі від власного ж прапора.
            if (Fixed.dstSq(u.x, u.y, p.x, p.y) > RADIUS_SQ) continue;
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
            // Спершу зірвати чуже: коло гасне до нуля і аж потім набирається
            // кольором того, хто прийшов.
            p.progress -= GAIN;
            if (p.progress <= 0) {
                p.progress = 0;
                p.holder   = present;
                p.owner    = null;
            }
        }
    }

    /** Порожнє коло: прогрес повзе назад до стану, у якому точка лишалась. */
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

    private static int teamCode(Team t) { return t == null ? -1 : t.playerId(); }

    private static Team teamOf(int code) { return code < 0 ? null : Team.forPlayer(code); }
}
