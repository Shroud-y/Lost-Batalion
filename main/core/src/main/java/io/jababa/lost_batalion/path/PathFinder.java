package io.jababa.lost_batalion.path;

import io.jababa.lost_batalion.math.Fixed;

/**
 * A* по {@link NavGrid} — цілочисельний і детермінований.
 *
 * <h3>Що робить детермінізм можливим</h3>
 * <ul>
 *   <li>вартості й евристика — {@link Fixed}, жодного {@code Math.sqrt}
 *       і жодного {@code float};</li>
 *   <li>сусіди обходяться в СТАЛОМУ порядку з масиву-константи, тому
 *       послідовність вставок у чергу однакова скрізь;</li>
 *   <li>черга з пріоритетом порівнює трійку (f, h, індекс) — повний порядок
 *       без нічиїх. Навіть якщо колись зміниться порядок обходу сусідів,
 *       результат лишиться тим самим;</li>
 *   <li>жодних {@code HashMap}/{@code HashSet}: усе — масиви за індексом
 *       клітинки, а їхній обхід не залежить ні від хешів, ні від JVM.</li>
 * </ul>
 *
 * <h3>Про пам'ять</h3>
 * Робочі масиви виділяються один раз і перевикористовуються між пошуками.
 * Замість очищення на 32 400 клітинок — лічильник поколінь: запис зі старого
 * покоління вважається порожнім. Один пошук не залишає слідів у наступному.
 *
 * <p>Клас НЕ потокобезпечний і не має бути: пошук виконується всередині тіку
 * симуляції. Фоновий потік дав би результат на різних тіках у різних гравців —
 * тобто гарантований десинхрон.
 */
public final class PathFinder {

    /** Сусіди: 4 прямі, потім 4 діагоналі. Порядок — частина результату. */
    private static final int[] NEIGHBOUR_DX = {  1, -1,  0,  0,  1,  1, -1, -1 };
    private static final int[] NEIGHBOUR_DY = {  0,  0,  1, -1,  1, -1,  1, -1 };

    private final NavGrid grid;

    private final long[] gScore;
    private final long[] hScore;
    private final int[]  cameFrom;
    private final int[]  stamp;        // покоління, у якому клітинку востаннє чіпали
    /**
     * Покоління, у якому клітинку закрили.
     *
     * <p>Саме покоління, а не {@code boolean}: масив прапорців треба було б
     * чистити між пошуками, а 32 400 записів на кожен наказ — це помітно.
     * Перша версія цього масиву була булевою й не чистилась узагалі, через що
     * ДРУГИЙ пошук тим самим об'єктом бачив усі клітинки закритими і здавався
     * на першому ж кроці.
     */
    private final int[] closedIn;

    private final IntHeap open;
    private int generation;

    /**
     * Стеля розкриттів. Закритий список гарантує, що кожна клітинка
     * розкривається щонайбільше раз, тож більше за кількість клітинок пошук
     * витратити не може — це не «розумне обмеження», а точна межа.
     *
     * <p>Перша версія ставила тут кругле 20 000 при 32 400 клітинках, і
     * найдовші маршрути через усю карту просто не знаходились: пошук
     * обривався й повертав null, а юніти йшли навпростець.
     */
    private final int maxExpansions;

    /** Скільки вузлів розкрив останній пошук — для профілювання й тестів. */
    private int lastExpansions;

    public PathFinder(NavGrid grid) {
        this.grid = grid;
        int n = grid.cellCount();
        this.gScore   = new long[n];
        this.hScore   = new long[n];
        this.cameFrom = new int[n];
        this.stamp    = new int[n];
        this.closedIn = new int[n];
        this.open     = new IntHeap(n);
        this.maxExpansions = n + 1;
    }

    public int getLastExpansions() { return lastExpansions; }

    /**
     * Знайти шлях від однієї світової точки до іншої.
     *
     * @return пари координат підряд (x0, y0, x1, y1, …) у Q47.16, від першої
     *         проміжної точки до цілі включно; {@code null}, якщо шлях не
     *         потрібен (ціль у тій самій клітинці) або не знайдений
     */
    public long[] findPath(long fromX, long fromY, long toX, long toY) {
        int start = grid.cellOf(fromX, fromY);
        int goal  = grid.cellOf(toX, toY);
        lastExpansions = 0;

        if (start == goal) return null;

        generation++;
        open.clear();

        setG(start, 0);
        hScore[start]   = heuristic(start, goal);
        cameFrom[start] = -1;
        open.push(start, hScore[start], hScore[start]);

        boolean reached = false;

        while (!open.isEmpty()) {
            int current = open.pop();
            if (isClosed(current)) continue;
            closedIn[current] = generation;

            if (current == goal) { reached = true; break; }

            if (++lastExpansions > maxExpansions) break;

            int cx = grid.cellX(current), cy = grid.cellY(current);

            for (int i = 0; i < NEIGHBOUR_DX.length; i++) {
                int nx = cx + NEIGHBOUR_DX[i];
                int ny = cy + NEIGHBOUR_DY[i];
                if (!grid.inBounds(nx, ny)) continue;

                int next = grid.index(nx, ny);
                if (isClosed(next)) continue;

                boolean diagonal = NEIGHBOUR_DX[i] != 0 && NEIGHBOUR_DY[i] != 0;
                long stepLength  = diagonal
                    ? Fixed.mul(NavGrid.CELL_SIZE_FIXED, NavGrid.SQRT2)
                    : NavGrid.CELL_SIZE_FIXED;

                // Платимо за вхід у клітинку — так вартість не залежить від того,
                // з якого боку в неї зайшли.
                long stepCost  = Fixed.mul(stepLength, grid.costAt(next));
                long candidate = g(current) + stepCost;

                if (isVisited(next) && candidate >= g(next)) continue;

                setG(next, candidate);
                cameFrom[next] = current;
                long h = heuristic(next, goal);
                hScore[next] = h;
                open.push(next, candidate + h, h);
            }
        }

        if (!reached) return null;
        return buildPath(start, goal, toX, toY);
    }

    // ── Евристика ─────────────────────────────────────────────────────────

    /**
     * Октильна відстань, помножена на НАЙДЕШЕВШИЙ множник карти.
     *
     * <p>Множення саме на найдешевший — умова допустимості: евристика ніколи не
     * має переоцінювати. Якби вона брала множник поточної клітинки, A* міг би
     * пропустити коротший шлях через дорогу місцевість.
     */
    private long heuristic(int from, int to) {
        int dx = Math.abs(grid.cellX(from) - grid.cellX(to));
        int dy = Math.abs(grid.cellY(from) - grid.cellY(to));

        int max = Math.max(dx, dy), min = Math.min(dx, dy);
        // max + (√2 − 1) × min, у клітинках
        long cells = Fixed.fromInt(max)
                   + Fixed.mul(NavGrid.SQRT2 - Fixed.ONE, Fixed.fromInt(min));

        return Fixed.mul(Fixed.mul(cells, NavGrid.CELL_SIZE_FIXED), grid.minCost());
    }

    // ── Побудова й згладжування ───────────────────────────────────────────

    /**
     * Зібрати шлях і зрізати сходинки.
     *
     * <p>Сирий A* по сітці дає характерну «драбинку»: рух то по горизонталі, то
     * по діагоналі. Юніт, що йде такою ламаною, смикається. Тому шлях
     * прорідується жадібно: точку викидаємо, якщо пряма в обхід неї не дорожча
     * за ламану через неї. Порівняння — за ВАРТІСТЮ, а не за довжиною, інакше
     * згладжування радо зрізало б кут через річку.
     */
    private long[] buildPath(int start, int goal, long toX, long toY) {
        // Розгортаємо ланцюжок від цілі до старту
        int length = 0;
        for (int c = goal; c != -1 && c != start; c = cameFrom[c]) length++;

        int[] cells = new int[length];
        int w = length - 1;
        for (int c = goal; c != -1 && c != start; c = cameFrom[c]) cells[w--] = c;

        // Світові координати: центри клітинок, крім останньої — там ціль,
        // куди гравець справді клікнув.
        int n = cells.length;
        long[] xs = new long[n], ys = new long[n];
        for (int i = 0; i < n; i++) {
            xs[i] = grid.centerX(cells[i]);
            ys[i] = grid.centerY(cells[i]);
        }
        if (n > 0) { xs[n - 1] = toX; ys[n - 1] = toY; }

        return smooth(xs, ys);
    }

    private long[] smooth(long[] xs, long[] ys) {
        int n = xs.length;
        if (n <= 1) return interleave(xs, ys, n);

        long[] outX = new long[n], outY = new long[n];
        int outCount = 0;

        int anchor = 0;
        outX[outCount] = xs[0]; outY[outCount] = ys[0]; outCount++;

        int probe = 1;
        while (probe < n) {
            int next = probe + 1;
            if (next >= n) break;

            long direct  = grid.segmentCost(xs[anchor], ys[anchor], xs[next], ys[next]);
            long viaBend = grid.segmentCost(xs[anchor], ys[anchor], xs[probe], ys[probe])
                         + grid.segmentCost(xs[probe],  ys[probe],  xs[next],  ys[next]);

            if (direct <= viaBend) {
                // Проміжна точка нічого не дає — викидаємо її.
                probe = next;
            } else {
                outX[outCount] = xs[probe]; outY[outCount] = ys[probe]; outCount++;
                anchor = probe;
                probe  = next;
            }
        }

        outX[outCount] = xs[n - 1]; outY[outCount] = ys[n - 1]; outCount++;
        return interleave(outX, outY, outCount);
    }

    private static long[] interleave(long[] xs, long[] ys, int count) {
        long[] out = new long[count * 2];
        for (int i = 0; i < count; i++) {
            out[i * 2]     = xs[i];
            out[i * 2 + 1] = ys[i];
        }
        return out;
    }

    // ── Робота з масивами через покоління ─────────────────────────────────

    private boolean isVisited(int cell) { return stamp[cell] == generation; }

    private boolean isClosed(int cell) { return closedIn[cell] == generation; }

    private void setG(int cell, long value) {
        stamp[cell]  = generation;
        gScore[cell] = value;
    }

    private long g(int cell) {
        return stamp[cell] == generation ? gScore[cell] : Long.MAX_VALUE / 4;
    }

    /**
     * Двійкова купа індексів клітинок із повним порядком (f, h, індекс).
     *
     * <p>Своя, а не {@code java.util.PriorityQueue}, з двох причин: вона не
     * боксує {@code int} у {@code Integer} на кожну вставку, і — головне —
     * порівняння тут явне й повне. Нічиї неможливі за побудовою, тож результат
     * не залежить від внутрішнього порядку купи.
     */
    private static final class IntHeap {
        private int[]  cell;
        private long[] f;
        private long[] h;
        private int size;

        IntHeap(int capacity) {
            int cap = Math.max(16, capacity);
            cell = new int[cap];
            f    = new long[cap];
            h    = new long[cap];
        }

        void clear() { size = 0; }
        boolean isEmpty() { return size == 0; }

        /**
         * Купа РОСТЕ за потреби.
         *
         * <p>Одна клітинка потрапляє сюди стільки разів, скільки разів для неї
         * знайшлась краща оцінка, — а це не обмежене кількістю клітинок. Перша
         * версія мала фіксований масив і при переповненні мовчки викидала
         * вузли: довгі маршрути через усю карту від цього просто не
         * знаходились. Мовчазна втрата даних — найгірший вид обмеження.
         */
        private void grow() {
            int cap = cell.length * 2;
            int[]  nc = new int[cap];
            long[] nf = new long[cap];
            long[] nh = new long[cap];
            System.arraycopy(cell, 0, nc, 0, size);
            System.arraycopy(f,    0, nf, 0, size);
            System.arraycopy(h,    0, nh, 0, size);
            cell = nc; f = nf; h = nh;
        }

        void push(int c, long fScore, long hScore) {
            if (size == cell.length) grow();
            int i = size++;
            cell[i] = c; f[i] = fScore; h[i] = hScore;
            while (i > 0) {
                int parent = (i - 1) >> 1;
                if (less(i, parent)) { swap(i, parent); i = parent; } else break;
            }
        }

        int pop() {
            int top = cell[0];
            size--;
            if (size > 0) {
                cell[0] = cell[size]; f[0] = f[size]; h[0] = h[size];
                int i = 0;
                while (true) {
                    int l = i * 2 + 1, r = l + 1, best = i;
                    if (l < size && less(l, best)) best = l;
                    if (r < size && less(r, best)) best = r;
                    if (best == i) break;
                    swap(i, best); i = best;
                }
            }
            return top;
        }

        /** Повний порядок: спершу f, потім h (ближче до цілі), потім індекс клітинки. */
        private boolean less(int a, int b) {
            if (f[a] != f[b]) return f[a] < f[b];
            if (h[a] != h[b]) return h[a] < h[b];
            return cell[a] < cell[b];
        }

        private void swap(int a, int b) {
            int c = cell[a]; cell[a] = cell[b]; cell[b] = c;
            long t = f[a]; f[a] = f[b]; f[b] = t;
            t = h[a]; h[a] = h[b]; h[b] = t;
        }
    }
}
