package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.Gdx;

import java.util.ArrayList;
import java.util.List;

/**
 * Підготовка матчу, розкладена на кроки з назвами.
 *
 * <p>Раніше вся вона робилась одним викликом у {@code GameScreen.show()}:
 * текстура карти, дві маски, сітка навігації, десяток рендерерів і збірка
 * інтерфейсу зі шрифтами. Кадр при цьому не малювався взагалі, тож гравець
 * бачив завислу гру, а в мережевому матчі суперник — «гравець гальмує»
 * (виміряно 884 мс на перших тіках).
 *
 * <p>Тут та сама робота лишається В ТОМУ Ж ПОРЯДКУ, але виконується
 * порціями: за кадр береться стільки кроків, скільки влазить у
 * {@link #BUDGET_MILLIS}, після чого керування повертається, щоб намалювати
 * смугу. Кроки НЕ можна виконувати паралельно чи переставляти — кожен
 * наступний спирається на попередній, а частина з них чіпає контекст OpenGL,
 * який належить потоку рендеру.
 *
 * <p>Смуга показує РЕАЛЬНИЙ поступ: частку виконаних кроків, зважених за
 * оголошеною вагою. Ваги грубі — важливо лише, щоб довгі кроки не
 * виглядали як миттєві.
 */
public class MatchLoader {

    /**
     * Скільки часу на кадр віддавати завантаженню.
     *
     * <p>Компроміс: більше — швидше завантажується, але смуга оновлюється
     * ривками; менше — плавно, але довго. 12 мс лишають місце на кадр 60 Гц.
     * Один крок ніколи не переривається посеред себе, тож крок, який сам
     * триває довше за бюджет (сітка навігації), однаково з'їсть свій час —
     * зате смуга перед ним уже намальована з його назвою.
     */
    private static final long BUDGET_MILLIS = 12;

    /** Один крок підготовки. */
    public interface Work { void run(); }

    private static final class Step {
        final String label;
        final float  weight;
        final Work   work;
        Step(String label, float weight, Work work) {
            this.label = label; this.weight = weight; this.work = work;
        }
    }

    private final List<Step> steps = new ArrayList<>();
    private int   index;
    private float doneWeight;
    private float totalWeight;

    /** Скільки часу зайняла підготовка — потрапляє в лог. */
    private long startedAtMillis;
    private long elapsedMillis;

    /**
     * @param weight груба ціна кроку одна відносно одної; лише для вигляду смуги
     */
    public MatchLoader add(String label, float weight, Work work) {
        steps.add(new Step(label, weight, work));
        totalWeight += weight;
        return this;
    }

    public boolean isDone() { return index >= steps.size(); }

    /** Назва того, що робиться ЗАРАЗ. Після завершення — назва останнього кроку. */
    public String currentLabel() {
        if (steps.isEmpty()) return "";
        return steps.get(Math.min(index, steps.size() - 1)).label;
    }

    /** 0..1 за вагою виконаних кроків. */
    public float progress() {
        if (totalWeight <= 0f) return 1f;
        return Math.min(1f, doneWeight / totalWeight);
    }

    public long elapsedMillis() { return elapsedMillis; }

    /**
     * Виконати стільки кроків, скільки влазить у бюджет кадру.
     *
     * @return чи все закінчено
     */
    public boolean advance() {
        if (startedAtMillis == 0L) startedAtMillis = System.currentTimeMillis();
        if (isDone()) return true;

        long deadline = System.currentTimeMillis() + BUDGET_MILLIS;
        do {
            Step step = steps.get(index);
            try {
                step.work.run();
            } catch (RuntimeException e) {
                // Крок, що впав, зупиняє матч однаково — але з назвою в логу
                // видно, ЯКИЙ саме, а не просто стек у глибині show().
                Gdx.app.error("LOAD", "крок «" + step.label + "» упав: " + e, e);
                throw e;
            }
            index++;
            doneWeight += step.weight;
        } while (!isDone() && System.currentTimeMillis() < deadline);

        if (isDone()) elapsedMillis = System.currentTimeMillis() - startedAtMillis;
        return isDone();
    }
}
