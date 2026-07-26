package io.jababa.lost_batalion.path;

import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.terrain.TerrainQuery;

/**
 * Сітка прохідності для пошуку шляху.
 *
 * <h3>Що це таке</h3>
 * Карта поділена на клітинки {@link #CELL_SIZE} світових одиниць. Для кожної
 * зберігається одне число — у скільки разів дорожче її перетнути порівняно з
 * чистим полем. Ліс, річка й височини не є перешкодами: вони просто дорогі,
 * і пошук сам вирішує, коли вигідніше обійти, а коли пролізти навпростець.
 *
 * <h3>Чому 8 одиниць</h3>
 * Рівно розмір блоку, яким маски вже індексуються ({@code TerrainMaskManager.BLOCK_SIZE}),
 * тож сітка лягає на наявні дані без зсувів. На карті 1440×1440 це 180×180 =
 * 32 400 клітинок — A* по такій сітці коштує частки мілісекунди. Дрібніша сітка
 * нічого не дала б: найменший юніт має 10 одиниць у поперечнику.
 *
 * <h3>Детермінізм</h3>
 * Вартість зчитується з масок у цілочисельних координатах центру клітинки, один
 * раз на матч. Маски однакові у всіх (це файли з ассетів), обхід — за індексами,
 * арифметика — {@link Fixed}. Отже сітка побітово однакова на всіх клієнтах, і
 * в знімок стану її класти не треба: вона відтворюється, а не змінюється.
 */
public final class NavGrid {

    /** Сторона клітинки у світових одиницях. */
    public static final int  CELL_SIZE       = 8;
    public static final long CELL_SIZE_FIXED = Fixed.fromInt(CELL_SIZE);

    /** √2 у Q47.16 — довжина діагонального переходу в клітинках. */
    public static final long SQRT2 = Fixed.fromFloat(1.4142135f);

    private final int width, height;

    /**
     * У скільки разів довше перетинати цю клітинку (Q47.16, {@link Fixed#ONE} =
     * чисте поле). Обернене до множника швидкості місцевості: якщо ліс дає
     * ×0.70 швидкості, то перетнути його коштує ×1.43 часу.
     */
    private final long[] costMultiplier;

    /** Найдешевший множник на всій карті — потрібен евристиці, щоб лишатись допустимою. */
    private final long minCostMultiplier;

    public NavGrid(TerrainQuery terrain, float mapWidth, float mapHeight) {
        this.width  = Math.max(1, (int) Math.ceil(mapWidth  / CELL_SIZE));
        this.height = Math.max(1, (int) Math.ceil(mapHeight / CELL_SIZE));
        this.costMultiplier = new long[width * height];

        long cheapest = Long.MAX_VALUE;
        long half = CELL_SIZE_FIXED >> 1;

        for (int cy = 0; cy < height; cy++) {
            for (int cx = 0; cx < width; cx++) {
                long wx = Fixed.fromInt(cx * CELL_SIZE) + half;
                long wy = Fixed.fromInt(cy * CELL_SIZE) + half;

                long speed = terrain != null ? terrain.movementMultiplierF(wx, wy) : Fixed.ONE;
                // Захист від нуля: множник швидкості 0 означав би нескінченну
                // вартість, а непрохідних клітинок за домовленістю немає.
                if (speed <= 0) speed = Fixed.fromFloat(0.05f);

                long cost = Fixed.div(Fixed.ONE, speed);
                costMultiplier[cy * width + cx] = cost;
                if (cost < cheapest) cheapest = cost;
            }
        }
        this.minCostMultiplier = cheapest == Long.MAX_VALUE ? Fixed.ONE : cheapest;
    }

    public int getWidth()  { return width; }
    public int getHeight() { return height; }
    public int cellCount() { return width * height; }

    public boolean inBounds(int cx, int cy) {
        return cx >= 0 && cy >= 0 && cx < width && cy < height;
    }

    public int index(int cx, int cy) { return cy * width + cx; }
    public int cellX(int index)      { return index % width; }
    public int cellY(int index)      { return index / width; }

    /** Множник вартості клітинки (Q47.16, ≥ ONE). */
    public long costAt(int index) { return costMultiplier[index]; }

    public long minCost() { return minCostMultiplier; }

    // ── Перехід між світом і сіткою ───────────────────────────────────────

    /** Клітинка, у якій лежить світова точка. Координати затискаються в межі карти. */
    public int cellOf(long rawX, long rawY) {
        int cx = clamp(Fixed.toIntFloor(rawX) / CELL_SIZE, 0, width  - 1);
        int cy = clamp(Fixed.toIntFloor(rawY) / CELL_SIZE, 0, height - 1);
        return index(cx, cy);
    }

    /** Центр клітинки у світових координатах (Q47.16). */
    public long centerX(int index) {
        return Fixed.fromInt(cellX(index) * CELL_SIZE) + (CELL_SIZE_FIXED >> 1);
    }

    public long centerY(int index) {
        return Fixed.fromInt(cellY(index) * CELL_SIZE) + (CELL_SIZE_FIXED >> 1);
    }

    /**
     * Вартість проходу відрізком між двома світовими точками.
     *
     * <p>Використовується згладжуванням шляху: зрізати кут можна лише тоді, коли
     * пряма не дорожча за ламану. Крок вибірки — половина клітинки, щоб не
     * перестрибнути вузьку річку.
     */
    public long segmentCost(long x1, long y1, long x2, long y2) {
        long dx = x2 - x1, dy = y2 - y1;
        long dist = Fixed.length(dx, dy);
        if (dist == 0) return 0;

        long step  = CELL_SIZE_FIXED >> 1;
        int  steps = (int) (dist / step);
        if (steps < 1) steps = 1;

        long ux = Fixed.div(dx, dist), uy = Fixed.div(dy, dist);
        long segment = Fixed.divInt(dist, steps);

        long total = 0;
        for (int i = 0; i < steps; i++) {
            // Середина відрізка-кроку: так вибірка не залежить від того, куди
            // потрапили рівно межі клітинок.
            long d  = Fixed.mul(segment, Fixed.fromInt(i * 2 + 1) >> 1);
            long px = x1 + Fixed.mul(ux, d);
            long py = y1 + Fixed.mul(uy, d);
            total += Fixed.mul(segment, costAt(cellOf(px, py)));
        }
        return total;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
