package io.jababa.lost_batalion.terrain;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;

/**
 * Зчитує технічну маску карти та визначає тип місцевості за кольором пікселя.
 *
 * Кольори масок (hex → RGB):
 *   FOREST       #2E5819 → ( 46,  88,  26)  — стара маска лісу
 *   LOWLANDS     #57C371 → ( 87, 195, 113)  — 1а нізіни
 *   PRE_LOWLANDS #00E02F → (  0, 224,  47)  — 1б перед нізіни
 *   PLAINS       #689055 → (104, 144,  85)  — 2а рівнини
 *   PLAINS_ALT   #35880E → ( 53, 136,  14)  — 2б рівнини хз
 *   PRE_HIGHLANDS#C0AF00 → (192, 175,   0)  — 3а перед височини
 *   HIGHLANDS    #C06F00 → (192, 111,   0)  — 3б височини
 *
 * ПРОДУКТИВНІСТЬ: маска класифікується ОДИН раз при завантаженні у
 * {@code typeGrid} (1 байт на піксель = ordinal у {@link TerrainType}).
 * Далі {@link #isForestAt} / {@link #getElevationAt} — це просто читання з
 * масиву, без {@code Pixmap.getPixel} і без ланцюжка порівнянь кольорів.
 * Це критично для ray-marching (LOS, ALT-оверлей), який робить десятки тисяч
 * запитів за кадр. Pixmap звільняється одразу після побудови сітки.
 */
public class TerrainMaskManager {

    private static final int TOLERANCE = 20;

    // Кольори з технічного завдання
    private static final int[] FOREST_RGB        = { 93, 99, 0 };
    private static final int[] RIVER_RGB = { 0, 83, 255 };
    private static final int[] LOWLANDS_RGB       = { 87, 195, 113};
    private static final int[] PRE_LOWLANDS_RGB   = {  0, 224,  47};
    private static final int[] PLAINS_RGB         = {104, 144,  85};
    private static final int[] PLAINS_ALT_RGB     = { 53, 136,  14};
    private static final int[] PRE_HIGHLANDS_RGB  = {192, 175,   0};
    private static final int[] HIGHLANDS_RGB      = {192, 111,   0};
    private static final int[] VILLAGE_RGB        = {240, 255,   0};

    /** Кеш {@code TerrainType.values()} — щоб не алокувати масив на кожен запит. */
    private static final TerrainType[] TYPES = TerrainType.values();

    private static final byte NONE_ORD   = (byte) TerrainType.NONE.ordinal();
    private static final byte FOREST_ORD = (byte) TerrainType.FOREST.ordinal();

    /**
     * Розмір блоку для прискорювальної структури (див. {@link #buildBlocks}).
     * Степінь двійки.
     */
    public static final int BLOCK_SIZE = 8;

    /** Тип місцевості на піксель, у координатах пікселя (Y вниз). */
    private byte[] typeGrid;
    private int width;
    private int height;
    private boolean loaded = false;

    // ── Блокова зведена інформація (у СВІТОВИХ координатах блоків, Y вгору) ──
    private boolean[] blockAnyForest;
    private byte[]    blockMinHeight;
    private byte[]    blockMaxHeight;
    private int blocksX;
    private int blocksY;

    public TerrainMaskManager(String maskPath) {
        if (maskPath != null && Gdx.files.internal(maskPath).exists()) {
            Pixmap pixmap = new Pixmap(Gdx.files.internal(maskPath));
            try {
                buildGrid(pixmap);
                buildBlocks();
                loaded = true;
            } finally {
                pixmap.dispose();   // сітка побудована — сам Pixmap більше не потрібен
            }
        }
    }

    /** Класифікує кожен піксель маски один раз при завантаженні. */
    private void buildGrid(Pixmap pixmap) {
        width  = pixmap.getWidth();
        height = pixmap.getHeight();
        typeGrid = new byte[width * height];

        // Маски складаються з великих однотонних плям — мемоїзація попереднього
        // кольору прибирає більшість повторних порівнянь.
        int  lastPixel = 0;
        byte lastOrd   = NONE_ORD;
        boolean hasLast = false;

        for (int py = 0; py < height; py++) {
            int row = py * width;
            for (int px = 0; px < width; px++) {
                int pixel = pixmap.getPixel(px, py);
                byte ord;
                if (hasLast && pixel == lastPixel) {
                    ord = lastOrd;
                } else {
                    ord = classify(pixel);
                    lastPixel = pixel;
                    lastOrd   = ord;
                    hasLast   = true;
                }
                typeGrid[row + px] = ord;
            }
        }
    }

    /**
     * Будує зведення по блоках BLOCK_SIZE×BLOCK_SIZE — прискорювальну структуру
     * для ray-marching.
     *
     * Для кожного блоку зберігаємо:
     *   - чи є в ньому ХОЧ ОДИН піксель лісу;
     *   - МІНІМАЛЬНУ і МАКСИМАЛЬНУ висоту в блоці.
     *
     * Це консервативні межі, тому пропуск блоку — точний, а не наближений:
     * якщо в блоці немає лісу і його межі висот не можуть порушити правило
     * перекриття, то жоден піксель усередині нього променя не зупинить, і його
     * можна перестрибнути одним кроком.
     *
     * Індексація СВІТОВА (Y вгору), щоб той, хто пускає промінь, не думав про
     * переворот Y. Пам'ять: 180×180 блоків на маску 1440×1440 ≈ 65 КБ.
     */
    private void buildBlocks() {
        blocksX = (width  + BLOCK_SIZE - 1) / BLOCK_SIZE;
        blocksY = (height + BLOCK_SIZE - 1) / BLOCK_SIZE;

        blockAnyForest = new boolean[blocksX * blocksY];
        blockMinHeight = new byte[blocksX * blocksY];
        blockMaxHeight = new byte[blocksX * blocksY];

        java.util.Arrays.fill(blockMinHeight, Byte.MAX_VALUE);
        java.util.Arrays.fill(blockMaxHeight, Byte.MIN_VALUE);

        for (int py = 0; py < height; py++) {
            int worldY = height - 1 - py;      // pixmap Y вниз → світ Y вгору
            int by     = worldY / BLOCK_SIZE;
            int row    = py * width;

            for (int px = 0; px < width; px++) {
                byte ord = typeGrid[row + px];
                int  bi  = by * blocksX + (px / BLOCK_SIZE);

                if (ord == FOREST_ORD) blockAnyForest[bi] = true;

                // Ліс не несе даних про висоту — трактуємо як NONE, точно так
                // само як це робить getElevationAt.
                byte h = (byte) TerrainQuery.heightOf(
                    ord == FOREST_ORD ? TerrainType.NONE : TYPES[ord]);

                if (h < blockMinHeight[bi]) blockMinHeight[bi] = h;
                if (h > blockMaxHeight[bi]) blockMaxHeight[bi] = h;
            }
        }
    }

    public int getBlocksX() { return blocksX; }
    public int getBlocksY() { return blocksY; }

    /** Поза межами → true: «невідомо», хай викликач іде в детальний прохід. */
    public boolean blockHasForest(int bx, int by) {
        if (!inBlockRange(bx, by)) return true;
        return blockAnyForest[by * blocksX + bx];
    }

    /** Нижня межа висоти в блоці. Поза межами → 0 (найконсервативніше). */
    public int blockMinHeight(int bx, int by) {
        if (!inBlockRange(bx, by)) return 0;
        return blockMinHeight[by * blocksX + bx];
    }

    /** Верхня межа висоти в блоці. Поза межами → 0. */
    public int blockMaxHeight(int bx, int by) {
        if (!inBlockRange(bx, by)) return 0;
        return blockMaxHeight[by * blocksX + bx];
    }

    private boolean inBlockRange(int bx, int by) {
        return loaded && bx >= 0 && by >= 0 && bx < blocksX && by < blocksY;
    }

    /**
     * Кольор пікселя → ordinal типу місцевості.
     * Порядок перевірок не важливий: діапазони кольорів не перетинаються
     * при TOLERANCE = 20.
     *
     * <p>(240,255,0) у масці лісу — це СЕЛА (чотири плями, 10544 пікселів на
     * Жовтих Водах). На рух і бій вони не впливають; з них будуються точки
     * захоплення — див. {@link #findClusterCenters}.
     */
    private byte classify(int pixel) {
        if (pixel == 0) return NONE_ORD;          // повністю порожній піксель

        int r = (pixel >> 24) & 0xFF;
        int g = (pixel >> 16) & 0xFF;
        int b = (pixel >>  8) & 0xFF;

        if (matches(r, g, b, FOREST_RGB))         return FOREST_ORD;
        if (matches(r, g, b, RIVER_RGB))          return (byte) TerrainType.RIVER.ordinal();
        if (matches(r, g, b, LOWLANDS_RGB))       return (byte) TerrainType.LOWLANDS.ordinal();
        if (matches(r, g, b, PRE_LOWLANDS_RGB))   return (byte) TerrainType.PRE_LOWLANDS.ordinal();
        if (matches(r, g, b, PLAINS_RGB))         return (byte) TerrainType.PLAINS.ordinal();
        if (matches(r, g, b, PLAINS_ALT_RGB))     return (byte) TerrainType.PLAINS_ALT.ordinal();
        if (matches(r, g, b, PRE_HIGHLANDS_RGB))  return (byte) TerrainType.PRE_HIGHLANDS.ordinal();
        if (matches(r, g, b, HIGHLANDS_RGB))      return (byte) TerrainType.HIGHLANDS.ordinal();
        if (matches(r, g, b, VILLAGE_RGB))        return (byte) TerrainType.VILLAGE.ordinal();

        return NONE_ORD;
    }

    /**
     * Запит за ІНДЕКСОМ пікселя.
     *
     * <p>Основний вхід для симуляції: вона тримає координати у fixed-point і
     * переводить їх у піксель відкиданням дробової частини — точною
     * цілочисельною операцією. Приймати тут float означало б, що межа пікселя
     * залежить від округлення, а різні клієнти можуть опинитись по різні боки
     * цієї межі.
     *
     * @param worldPy Y у СВІТОВИХ пікселях (вгору), а не в координатах pixmap
     */
    public boolean isForestAtPixel(int px, int worldPy) {
        return ordinalAtPixel(px, worldPy) == FOREST_ORD;
    }

    public TerrainType getElevationAtPixel(int px, int worldPy) {
        byte ord = ordinalAtPixel(px, worldPy);
        // Ліс не несе даних про висоту (він з іншої маски) — трактуємо як NONE,
        // так само як раніше робив старий getElevationAt, що просто його не перевіряв.
        if (ord == FOREST_ORD) return TerrainType.NONE;
        return TYPES[ord];
    }

    /**
     * Тип місцевості (ordinal) за індексом пікселя.
     * Y перевертається: світ — Y вгору, pixmap — Y вниз.
     */
    private byte ordinalAtPixel(int px, int worldPy) {
        if (!loaded) return NONE_ORD;

        int py = height - 1 - worldPy;
        if (px < 0 || py < 0 || px >= width || py >= height) return NONE_ORD;

        return typeGrid[py * width + px];
    }

    /**
     * Центри суцільних плям заданого типу — у СВІТОВИХ пікселях (Y вгору).
     *
     * <p>Потрібне селам: у масці вони намальовані як плями довільної форми, а
     * грі треба одна точка на село. Обхід — заливка по 4 сусідах, центр —
     * цілочисельне середнє координат плями.
     *
     * <p>Результат входить у стан матчу (з нього будуються точки захоплення),
     * тому він мусить бути однаковим у всіх: маска — ассет, обхід іде по
     * індексах у сталому порядку, середнє цілочисельне. Жодного float.
     *
     * @param minPixels плями, дрібніші за це, ігноруються — у масці трапляються
     *                  поодинокі пікселі кольору на межах заливки
     * @return пари {@code x, y} підряд; порядок — від верхнього рядка маски
     */
    public int[] findClusterCenters(TerrainType type, int minPixels) {
        if (!loaded) return new int[0];

        final byte want = (byte) type.ordinal();
        boolean[] seen  = new boolean[width * height];
        int[] stack     = new int[width * height];
        int[] out       = new int[16];
        int outSize     = 0;

        for (int py = 0; py < height; py++) {
            for (int px = 0; px < width; px++) {
                int start = py * width + px;
                if (typeGrid[start] != want || seen[start]) continue;

                int top = 0;
                stack[top++] = start;
                seen[start]  = true;

                long sumX = 0, sumY = 0;
                int  count = 0;

                while (top > 0) {
                    int idx = stack[--top];
                    int cx  = idx % width;
                    int cy  = idx / width;

                    count++;
                    sumX += cx;
                    sumY += height - 1 - cy;      // pixmap Y вниз → світ Y вгору

                    top = tryPush(stack, top, seen, want, cx > 0,          idx - 1);
                    top = tryPush(stack, top, seen, want, cx < width - 1,  idx + 1);
                    top = tryPush(stack, top, seen, want, cy > 0,          idx - width);
                    top = tryPush(stack, top, seen, want, cy < height - 1, idx + width);
                }

                if (count < minPixels) continue;

                if (outSize + 2 > out.length) {
                    int[] bigger = new int[out.length * 2];
                    System.arraycopy(out, 0, bigger, 0, outSize);
                    out = bigger;
                }
                out[outSize++] = (int) (sumX / count);
                out[outSize++] = (int) (sumY / count);
            }
        }

        int[] result = new int[outSize];
        System.arraycopy(out, 0, result, 0, outSize);
        return result;
    }

    /** Покласти сусіда в стек, якщо він у межах, потрібного типу і ще не бачений. */
    private int tryPush(int[] stack, int top, boolean[] seen, byte want,
                        boolean inRange, int idx) {
        if (!inRange || seen[idx] || typeGrid[idx] != want) return top;
        seen[idx] = true;
        stack[top] = idx;
        return top + 1;
    }

    private boolean matches(int r, int g, int b, int[] target) {
        return Math.abs(r - target[0]) <= TOLERANCE
            && Math.abs(g - target[1]) <= TOLERANCE
            && Math.abs(b - target[2]) <= TOLERANCE;
    }

    public void dispose() {
        typeGrid       = null;
        blockAnyForest = null;
        blockMinHeight = null;
        blockMaxHeight = null;
        loaded         = false;
    }
}
