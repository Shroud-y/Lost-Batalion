package io.jababa.lost_batalion.math;

/**
 * Єдиний генератор випадкових чисел ігрової симуляції.
 *
 * <p>xorshift64* — цілочисельний, без {@code Math.random}, без {@code java.util.Random}
 * (той теж детермінований, але його стан не серіалізується зручно, а нам стан треба
 * класти у знімок для ресинку і в checksum).
 *
 * <p>Правила користування, порушення будь-якого = гарантований десинхрон:
 * <ul>
 *   <li>Один екземпляр на матч, seed приходить від хоста в StartMatch.</li>
 *   <li>Смикати ЛИШЕ всередині тіку симуляції, ніколи з рендеру чи UI.</li>
 *   <li>Кількість викликів за тік мусить збігатися на всіх клієнтах — не можна
 *       робити виклик усередині гілки, що залежить від локальних даних
 *       (наприклад від видимості з перспективи локального гравця).</li>
 * </ul>
 *
 * <p>Для візуальних ефектів (розкид трасера пострілу тощо) цей генератор
 * використовувати НЕ можна — там лишається libGDX MathUtils.random.
 */
public final class DeterministicRandom {

    private long state;
    /** Лічильник викликів — потрапляє в checksum, ловить розбіжність у кількості смикань. */
    private long calls;

    public DeterministicRandom(long seed) {
        setSeed(seed);
    }

    public void setSeed(long seed) {
        // Нуль — нерухома точка xorshift, тому підмінюємо на довільну ненульову константу.
        this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        this.calls = 0;
    }

    public long nextLong() {
        long x = state;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        state = x;
        calls++;
        return x * 0x2545F4914F6CDD1DL;
    }

    /** Невід'ємний int у [0, bound). Відкидає упереджені значення, щоб розподіл був рівномірним. */
    public int nextInt(int bound) {
        if (bound <= 0) return 0;
        int bits, val;
        do {
            bits = (int) (nextLong() >>> 33);
            val  = bits % bound;
        } while (bits - val + (bound - 1) < 0);
        return val;
    }

    /** Значення у Q47.16 в діапазоні [0, ONE). */
    public long nextFixedUnit() {
        return (nextLong() >>> 48);  // 16 випадкових бітів == дробова частина
    }

    /** Значення у Q47.16 в діапазоні [0, maxRaw]. */
    public long nextFixed(long maxRaw) {
        if (maxRaw <= 0) return 0;
        return Fixed.mul(nextFixedUnit(), maxRaw);
    }

    /** Кут у Q47.16 радіанах, рівномірно на повному оберті. */
    public long nextAngle() {
        return Fixed.mul(nextFixedUnit(), Fixed.PI2);
    }

    // ── Стан (знімок / checksum) ──────────────────────────────────────────

    public long getState() { return state; }
    public long getCalls() { return calls; }

    public void restore(long state, long calls) {
        this.state = state;
        this.calls = calls;
    }
}
