package io.jababa.lost_batalion.sim;

/**
 * Годинник фіксованого кроку: перетворює час кадру на цілу кількість тіків.
 *
 * <p>Рендер може йти з будь-яким FPS, симуляція — строго 40 разів на секунду.
 * Залишок часу, якого не вистачило на повний тік, накопичується і віддається
 * як {@link #getAlpha()} для інтерполяції рендеру між двома станами: без цього
 * при 144 Гц юніти рухались би ривками по 25 мс.
 */
public class LockstepClock {

    /**
     * Накопичений час у СЕКУНДАХ, але типом double.
     *
     * <p>З float тут виходила систематична помилка: при 144 Гц крок кадру
     * 1/144 не представний точно, і на десяти секундах набігав недолік майже
     * на цілий тік — 399 замість 400. Помилка не випадкова, а стала, тож із
     * часом матчу вона тільки росла б. У double запасу точності вистачає
     * на години гри.
     */
    private double accumulator;

    /** Скільки тіків довелось відкинути через обмеження наздоганяння. */
    private int droppedTicks;

    /**
     * @param renderDelta час кадру в секундах
     * @return скільки тіків симуляції треба виконати цього кадру
     */
    public int advance(float renderDelta) {
        // Довгий кадр (перемикання вікна, завантаження ассетів, точка зупину в
        // дебагері) не має перетворюватись на хвилину прискореної гри.
        double maxFrame = TickRate.MAX_CATCHUP_TICKS * (double) TickRate.TICK_SECONDS_EXACT;
        double clamped  = Math.min(renderDelta, maxFrame);
        accumulator += clamped;

        final double step = TickRate.TICK_SECONDS_EXACT;

        int ticks = 0;
        while (accumulator >= step && ticks < TickRate.MAX_CATCHUP_TICKS) {
            accumulator -= step;
            ticks++;
        }

        if (accumulator >= step) {
            // Не встигаємо навіть із наздоганянням — рахуємо борг і скидаємо
            // залишок, щоб він не ріс безмежно.
            droppedTicks += (int) (accumulator / step);
            accumulator %= step;
        }
        return ticks;
    }

    /**
     * Частка тіку, що вже минула: 0 — щойно виконали тік, 1 — ось-ось наступний.
     * Рендер малює юніта між його позицією на минулому і поточному тіку.
     */
    public float getAlpha() {
        return (float) (accumulator / TickRate.TICK_SECONDS_EXACT);
    }

    /**
     * Скільки тіків машина не потягнула. У мультиплеєрі ненульове значення
     * означає, що ця сторона гальмує весь матч — саме її решта чекає.
     */
    public int getDroppedTicks() { return droppedTicks; }

    /** Скинути накопичене — після паузи, ресинку чи завантаження. */
    public void reset() {
        accumulator = 0d;
    }
}
