package io.jababa.lost_batalion.sim;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.units.Artillery;
import io.jababa.lost_batalion.units.CombatManager;
import io.jababa.lost_batalion.units.Unit;

/**
 * Хеш стану симуляції — детектор десинхрону.
 *
 * <h3>Навіщо, якщо симуляція вже детермінована</h3>
 * Детермінізм доведений на двох клієнтах в одному процесі й на одній JVM. У
 * реальному матчі це різні машини, різні збірки, іноді різні платформи. Тиха
 * розбіжність — найгірший можливий результат: гравці ще годину бачать різні
 * бої й дізнаються про це, коли один святкує перемогу, а другий поразку.
 * Хеш ловить це за секунду.
 *
 * <h3>Чому не один число, а компоненти</h3>
 * Один хеш відповідає лише «розійшлось». Розбитий на частини — відповідає
 * «розійшлись позиції, а hp збіглись», і це майже завжди одразу вказує на
 * підсистему. Коштує це п'ять зайвих {@code long} раз на секунду, тобто нічого.
 *
 * <h3>Що входить і що ні</h3>
 * Входить усе, що впливає на майбутні тіки: позиції, здоров'я, таймери, накази
 * атаки, снаряди в польоті, видимість ОБОХ сторін і стан RNG. Не входить нічого
 * візуального й нічого локального — виділення юнітів у кожного своє, і різне
 * виділення це не десинхрон.
 *
 * <p>Стан RNG входить окремим компонентом навмисно: якщо розійшлась лише
 * кількість смикань генератора, решта ще збігається, і саме цей компонент
 * назве причину, поки її ще видно.
 */
public final class StateChecksum {

    private StateChecksum() {}

    // ── Компоненти ────────────────────────────────────────────────────────

    public static final int C_POSITIONS  = 0;
    public static final int C_HEALTH     = 1;
    public static final int C_TIMERS     = 2;
    public static final int C_ORDERS     = 3;
    public static final int C_VISIBILITY = 4;
    public static final int C_RNG        = 5;
    public static final int C_POINTS     = 6;
    public static final int C_ECONOMY    = 7;
    public static final int C_VICTORY    = 8;
    /**
     * Мораль окремо від здоров'я навмисно: це інша підсистема з іншими
     * причинами розійтись, і «розійшлась мораль, а hp збіглись» одразу
     * називає винного.
     */
    public static final int C_MORALE     = 9;
    public static final int COMPONENTS   = 10;

    private static final String[] NAMES = {
        "позиції", "здоров'я", "таймери", "накази", "видимість", "RNG",
        "точки", "економіка", "перемога", "мораль"
    };

    public static String componentName(int index) {
        return index >= 0 && index < NAMES.length ? NAMES[index] : "?" + index;
    }

    // ── Обчислення ────────────────────────────────────────────────────────

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME  = 0x100000001b3L;

    /**
     * Домішати значення до хеша.
     *
     * <p>Порядок домішування — частина результату: два клієнти мусять обходити
     * юнітів однаково. Це виконується автоматично, бо обхід іде по індексах
     * {@code Array}, а порядок створення юнітів детермінований.
     */
    private static long mix(long h, long v) {
        h ^= v;
        h *= FNV_PRIME;
        h ^= h >>> 32;
        return h;
    }

    private static long mix(long h, boolean v) { return mix(h, v ? 1L : 0L); }

    /**
     * Порахувати компоненти хеша у переданий масив довжини {@link #COMPONENTS}.
     *
     * @return зведений хеш усіх компонентів разом
     */
    public static long compute(GameSimulation sim, long[] out) {
        long positions = FNV_OFFSET;
        long health    = FNV_OFFSET;
        long timers    = FNV_OFFSET;
        long visible   = FNV_OFFSET;
        long morale    = FNV_OFFSET;

        Array<Unit> all = sim.getUnitManager().getAllUnits();
        for (int i = 0; i < all.size; i++) {
            Unit u = all.get(i);

            // Id домішується в кожен компонент, щоб перестановка юнітів
            // місцями не давала того самого хеша.
            positions = mix(positions, u.id);
            positions = mix(positions, u.x);
            positions = mix(positions, u.y);
            positions = mix(positions, u.isMoving());
            // Напрямок — теж стан: поки гармата не довернулась, вона не їде
            // і не стріляє.
            positions = mix(positions, u.facing);
            // Маршрут — теж стан: два клієнти з однаковими позиціями, але
            // різними шляхами розійдуться вже наступного тіку, і чекати на це
            // означало б ловити розбіжність із запізненням.
            positions = mix(positions, u.getTargetX());
            positions = mix(positions, u.getTargetY());
            positions = mix(positions, u.getPathIndex());
            long[] route = u.getPath();
            positions = mix(positions, route == null ? -1 : route.length);
            if (route != null) {
                for (int p = 0; p < route.length; p++) positions = mix(positions, route[p]);
            }

            health = mix(health, u.id);
            health = mix(health, u.hp);
            health = mix(health, u.alive);

            timers = mix(timers, u.id);
            timers = mix(timers, u.attackTimerTicks);
            if (u instanceof Artillery) {
                Artillery a = (Artillery) u;
                timers = mix(timers, a.aimTicks);
                timers = mix(timers, a.reloadTicks);
                // Ручна ціль теж стан: від неї залежить, куди полетить наступний снаряд.
                timers = mix(timers, a.manualTarget == null ? -1 : a.manualTarget.id);
                // Ціль наведення теж стан: вона тримається між тіками, і саме
                // від неї залежить, куди полетить наступний снаряд.
                timers = mix(timers, a.aimTarget == null ? -1 : a.aimTarget.id);
                // Батарея: від числа стволів залежить урон залпу, а від наміру
                // об'єднатись — куди гармата поїде наступного тіку.
                timers = mix(timers, a.stack);
                timers = mix(timers, a.mergeWithId);
                timers = mix(timers, a.mergeRepathTick);
                timers = mix(timers, a.absorbed);
            }

            visible = mix(visible, u.id);
            // Власник — теж стан: від нього залежить, чий наказ юніт виконає.
            // Розбіжність тут означала б, що в двох клієнтів різні армії при
            // однакових позиціях, і виявилась би вона аж на першому наказі.
            visible = mix(visible, u.owner);
            visible = mix(visible, u.isVisibleTo(Team.PLAYER));
            visible = mix(visible, u.isVisibleTo(Team.ENEMY));

            // Усі три поля, а не лише саму мораль: лічильник спокою вирішує,
            // коли почнеться відновлення, а лічильник втечі — коли юніт
            // повернеться в стрій. Розбіжність у будь-якому з них розводить
            // клієнтів через секунди.
            morale = mix(morale, u.id);
            morale = mix(morale, u.morale);
            morale = mix(morale, u.calmTicks);
            morale = mix(morale, u.routTicks);
        }

        CombatManager combat = sim.getCombatManager();
        long orders = combat == null ? FNV_OFFSET : combat.stateDigest(FNV_OFFSET);

        long rng = FNV_OFFSET;
        rng = mix(rng, sim.getRandom().getState());
        rng = mix(rng, sim.getRandom().getCalls());

        out[C_POSITIONS]  = positions;
        out[C_HEALTH]     = health;
        out[C_TIMERS]     = timers;
        out[C_ORDERS]     = orders;
        out[C_VISIBILITY] = visible;
        out[C_MORALE]     = morale;
        out[C_RNG]        = rng;
        out[C_POINTS]     = sim.getCapturePoints() == null
                          ? FNV_OFFSET
                          : sim.getCapturePoints().stateDigest(FNV_OFFSET);

        // Золото і черга замовлень — один компонент: розійтись поодинці вони
        // не можуть, бо кожне замовлення це і списання, і рядок у черзі.
        long money = sim.getEconomy().stateDigest(FNV_OFFSET);
        out[C_ECONOMY]    = sim.getSpawnQueue().stateDigest(money);

        // Окремим компонентом, а не всередині економіки: якщо сторони розійшлись
        // саме в переможці, це треба назвати прямо — розбіжність тут означає, що
        // одному гравцю вже показали результат, а іншому ще ні.
        out[C_VICTORY]    = sim.getVictory() == null
                          ? FNV_OFFSET
                          : sim.getVictory().stateDigest(FNV_OFFSET);

        long total = mix(FNV_OFFSET, sim.getTickNumber());
        for (int i = 0; i < COMPONENTS; i++) total = mix(total, out[i]);
        return total;
    }

    /** Те саме, коли компоненти не потрібні. */
    public static long compute(GameSimulation sim) {
        return compute(sim, new long[COMPONENTS]);
    }

    /** Домішування для підсистем, що рахують свою частину самі. */
    public static long fold(long h, long v)    { return mix(h, v); }
    public static long fold(long h, boolean v) { return mix(h, v); }

    /**
     * Назви компонентів, що розійшлися між двома наборами.
     *
     * @return людський опис для лога; порожній рядок, якщо все збіглось
     */
    public static String describeMismatch(long[] mine, long[] theirs) {
        if (mine == null || theirs == null) return "компоненти невідомі";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < COMPONENTS && i < mine.length && i < theirs.length; i++) {
            if (mine[i] == theirs[i]) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(componentName(i));
        }
        return sb.length() == 0 ? "усі компоненти збіглись (розійшовся лише зведений хеш)"
                                : sb.toString();
    }
}
