package io.jababa.lost_batalion.math;

/**
 * Детермінована арифметика з фіксованою комою, формат Q47.16 у {@code long}.
 *
 * <p>Уся симуляція рахується в цих числах, бо {@code float}/{@code double} не дають
 * побітово однакового результату між JVM, архітектурами (x86 vs ARM) і платформами
 * (Desktop vs Android). Lockstep вимагає саме побітової однаковості: розбіжність
 * в один ULP на одному тіку через 40 тіків на секунду перетворюється на юніта,
 * що стоїть в іншому місці, і матч розсинхронізується.
 *
 * <p>Домовленість: сирі значення в цьому форматі всюди називаються "raw" і мають
 * тип {@code long}. Перетворення на {@code float} дозволене ЛИШЕ на межі рендеру.
 *
 * <p>Точність: 1/65536 ≈ 0.0000153 світової одиниці. Мапи в проєкті мають розмір
 * порядку 10^3 px, тож запас по діапазону (±1.4×10^14) надлишковий.
 */
public final class Fixed {

    private Fixed() {}

    /** Кількість дробових бітів. */
    public static final int  SHIFT = 16;
    /** Одиниця (1.0). */
    public static final long ONE   = 1L << SHIFT;
    /** Половина (0.5). */
    public static final long HALF  = ONE >> 1;
    /** Нуль. */
    public static final long ZERO  = 0L;

    /** Маска дробової частини. */
    private static final long FRAC_MASK = ONE - 1;

    // ── Перетворення ──────────────────────────────────────────────────────

    public static long fromInt(int v)   { return (long) v << SHIFT; }

    /**
     * Перетворення з {@code float}. Дозволене лише для КОНСТАНТ і даних, завантажених
     * з ассетів однаково на всіх клієнтах — не для проміжних результатів симуляції.
     */
    public static long fromFloat(float v) { return (long) Math.floor((double) v * ONE); }

    /** Тільки для рендеру/UI. */
    public static float toFloat(long raw) { return (float) raw / ONE; }

    /** Округлення до найближчого цілого (half-up), детерміноване. */
    public static int toIntRound(long raw) { return (int) ((raw + HALF) >> SHIFT); }

    /** Відкидання дробової частини вниз (floor), детерміноване і для від'ємних. */
    public static int toIntFloor(long raw) { return (int) (raw >> SHIFT); }

    /** Дробова частина, завжди в [0, ONE). */
    public static long frac(long raw) { return raw & FRAC_MASK; }

    // ── Базові операції ───────────────────────────────────────────────────

    /**
     * Множення. Проміжний добуток може переповнити {@code long} при |a|,|b| > 2^23,
     * тож для великих величин застосовується розклад на цілу й дробову частини.
     * Обидві гілки дають однаковий результат — обрана та, що не переповнюється.
     */
    public static long mul(long a, long b) {
        long hi = (a >> SHIFT) * b;
        long lo = (a & FRAC_MASK) * b >> SHIFT;
        return hi + lo;
    }

    /** Ділення. {@code b == 0} трактується як ділення на найменшу одиницю зі знаком. */
    public static long div(long a, long b) {
        if (b == 0) return a >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        return (a << SHIFT) / b;
    }

    public static long abs(long a) { return a < 0 ? -a : a; }
    public static long min(long a, long b) { return a < b ? a : b; }
    public static long max(long a, long b) { return a > b ? a : b; }

    public static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ── Корінь і довжина ──────────────────────────────────────────────────

    /**
     * Квадратний корінь цілочисельним методом Ньютона.
     * Жодних {@code Math.sqrt}, жодних таблиць — тільки цілі операції,
     * тому результат побітово однаковий скрізь.
     */
    public static long sqrt(long raw) {
        if (raw <= 0) return 0;

        // sqrt(R) у Q47.16 == isqrt(raw << 16), бо sqrt(raw/2^16)*2^16 == sqrt(raw*2^16).
        // Зсув безпечний, поки raw < 2^47 (тобто величина < 2^31) — для дистанцій на
        // мапі порядку 10^3 px це з великим запасом. Понад цією межею падаємо на
        // менш точну гілку, аби не втратити старші біти.
        if (raw < (1L << 47)) return isqrt(raw << SHIFT);
        return isqrt(raw) << (SHIFT / 2);
    }

    /** Цілочисельний квадратний корінь (floor). Лише цілі операції — побітово стабільний. */
    private static long isqrt(long n) {
        long result = 0;
        // Стартовий біт: найстарший парний біт, не більший за n.
        long bit = 1L << 62;
        while (bit > n) bit >>= 2;

        while (bit != 0) {
            if (n >= result + bit) {
                n -= result + bit;
                result = (result >> 1) + bit;
            } else {
                result >>= 1;
            }
            bit >>= 2;
        }
        return result;
    }

    /** Довжина вектора (dx, dy). */
    public static long length(long dx, long dy) {
        return sqrt(mul(dx, dx) + mul(dy, dy));
    }

    /** Квадрат довжини — дешевше за {@link #length} і достатньо для порівнянь. */
    public static long lengthSq(long dx, long dy) {
        return mul(dx, dx) + mul(dy, dy);
    }

    /** Відстань між двома точками. */
    public static long dst(long x1, long y1, long x2, long y2) {
        return length(x2 - x1, y2 - y1);
    }

    public static long dstSq(long x1, long y1, long x2, long y2) {
        return lengthSq(x2 - x1, y2 - y1);
    }

    /**
     * Найменше ціле {@code n}, для якого {@code n*n >= value}.
     *
     * <p>Потрібне там, де раніше стояло {@code (int) Math.ceil(Math.sqrt(count))} —
     * розкладка юнітів у сітку. {@code Math.sqrt} насправді детермінований
     * (IEEE вимагає правильного округлення), але змішувати float у симуляції
     * означає щоразу доводити, що саме цей виклик безпечний. Дешевше не мати
     * там float взагалі.
     */
    public static int ceilSqrtInt(int value) {
        if (value <= 0) return 0;
        int n = 0;
        while (n * n < value) n++;
        return n;
    }

    /** Ділення на ціле — окремо, бо ділити на {@code fromInt(n)} дорожче й вужче за діапазоном. */
    public static long divInt(long raw, int divisor) {
        if (divisor == 0) return raw >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        return raw / divisor;
    }

    /**
     * Нормалізований напрямок (dx, dy) → (outX, outY) у переданий масив довжини 2.
     *
     * <p>Повертає довжину вихідного вектора; якщо вона нульова, напрямок теж
     * нульовий, і викликач сам вирішує, що з цим робити. Виділений метод, бо
     * нормалізація трапляється в бою тричі й щоразу однаково.
     */
    public static long normalize(long dx, long dy, long[] out) {
        long len = length(dx, dy);
        if (len == 0) { out[0] = 0; out[1] = 0; return 0; }
        out[0] = div(dx, len);
        out[1] = div(dy, len);
        return len;
    }

    // ── Тригонометрія ─────────────────────────────────────────────────────

    /** Кут повного оберту (2π). */
    public static final long PI2 = fromFloat(6.283185307179586f);
    /** π. */
    public static final long PI  = fromFloat(3.141592653589793f);

    /** Розмір таблиці синусів на повний оберт. Степінь двійки — щоб маска працювала. */
    private static final int  SIN_BITS  = 12;
    private static final int  SIN_COUNT = 1 << SIN_BITS;      // 4096
    private static final int  SIN_MASK  = SIN_COUNT - 1;
    private static final long[] SIN_TABLE = new long[SIN_COUNT];

    static {
        // Таблиця будується один раз із double і одразу квантується у Q47.16.
        // Квантування робить її незалежною від того, чи однаково JIT порахував
        // Math.sin — після округлення до 1/65536 усі платформи дають ті самі long.
        for (int i = 0; i < SIN_COUNT; i++) {
            double angle = 2.0 * Math.PI * i / SIN_COUNT;
            SIN_TABLE[i] = Math.round(Math.sin(angle) * ONE);
        }
    }

    /** Індекс у таблиці для кута в Q47.16 радіанах. */
    private static int sinIndex(long angleRaw) {
        // (angle / 2π) * SIN_COUNT одним діленням, щоб не втрачати точність двічі.
        // Від'ємні кути коректно обгортаються завдяки доповняльному коду + масці.
        return (int) ((angleRaw * SIN_COUNT) / PI2) & SIN_MASK;
    }

    public static long sin(long angleRaw) { return SIN_TABLE[sinIndex(angleRaw)]; }

    public static long cos(long angleRaw) {
        return SIN_TABLE[(sinIndex(angleRaw) + (SIN_COUNT >> 2)) & SIN_MASK];
    }

    // ── Форматування ──────────────────────────────────────────────────────

    /** Для логів десинхрону — показує сире значення й приблизне десяткове. */
    public static String str(long raw) {
        return raw + " (~" + (raw / (double) ONE) + ")";
    }
}
