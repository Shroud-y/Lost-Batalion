package io.jababa.lost_batalion.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.viewport.ScreenViewport;

/**
 * Єдиний множник розміру інтерфейсу.
 *
 * <h3>Навіщо</h3>
 * Меню малюються в {@code ExtendViewport(900, 580)} і росли разом із вікном, а
 * весь бойовий HUD сидів у {@code ScreenViewport} — там одиниця дорівнює
 * пікселю, тож у повний екран панелі лишались того самого розміру в пікселях і
 * ставали вдвічі дрібнішими відносно екрана. Тут з'являється один спільний
 * коефіцієнт, на який множиться КОЖЕН елемент HUD, і питання «як це виглядає на
 * іншій роздільності» має одну відповідь замість трьох.
 *
 * <h3>Як рахується</h3>
 * За висотою вікна, бо саме висоти завжди бракує: 720p = 1.0×. Значення
 * округлюється до чверті — дробові множники дають різну растеризацію гліфів на
 * сусідніх роздільностях, і текст «пливе».
 *
 * <p>Стеля 2.0× не випадкова: {@code UIFactory.generateTintableFont} пече
 * гліфи вдвічі більшими й малює їх із {@code setScale(0.5f)}, тобто запас
 * різкості шрифту рівно двократний. Вище — розмиття.
 *
 * <h3>Логічні координати</h3>
 * Після масштабування HUD живе в ЛОГІЧНИХ одиницях: {@code logicalWidth()} ×
 * {@code logicalHeight()}. {@code Gdx.input} же й далі віддає ФІЗИЧНІ пікселі,
 * тому все, що перевіряє влучання по намальованому вручну HUD, зобов'язане
 * пропустити координату через {@link #toLogical(float)}. Scene2d робить це сам
 * через в'юпорт сцени.
 */
public final class UIScale {

    /** Висота вікна, на якій множник дорівнює одиниці. */
    public static final float REFERENCE_HEIGHT = 720f;

    public static final float MIN  = 0.75f;
    public static final float MAX  = 2.0f;
    public static final float STEP = 0.25f;

    private UIScale() {}

    /** Множник для поточного вікна. */
    public static float get() {
        return forHeight(Gdx.graphics.getHeight());
    }

    /** Множник для заданої висоти вікна. Виділено окремо заради тестів і макетів. */
    public static float forHeight(int screenHeight) {
        if (screenHeight <= 0) return 1f;
        float raw     = screenHeight / REFERENCE_HEIGHT;
        float snapped = Math.round(raw / STEP) * STEP;
        if (snapped < MIN) return MIN;
        if (snapped > MAX) return MAX;
        return snapped;
    }

    /** Ширина HUD в логічних одиницях. */
    public static float logicalWidth() {
        return Gdx.graphics.getWidth() / get();
    }

    /** Висота HUD в логічних одиницях. */
    public static float logicalHeight() {
        return Gdx.graphics.getHeight() / get();
    }

    /** Фізичний піксель → логічна одиниця. */
    public static float toLogical(float pixels) {
        return pixels / get();
    }

    /** Логічна одиниця → фізичний піксель. */
    public static float toPixels(float logical) {
        return logical * get();
    }

    /**
     * Координата вводу (нуль УГОРІ, як віддає {@code Gdx.input}) → логічна
     * координата з нулем УНИЗУ, як їх бачить {@code SpriteBatch}.
     *
     * <p>Два перетворення в одному навмисно: розділені, вони раз у раз
     * застосовувались у різному порядку, а {@code (h - y) / s} і
     * {@code h - y / s} — це різні точки.
     */
    public static float inputYToLogical(float inputY) {
        return (Gdx.graphics.getHeight() - inputY) / get();
    }

    /** Координата вводу по X → логічна. Дзеркало до {@link #inputYToLogical}. */
    public static float inputXToLogical(float inputX) {
        return inputX / get();
    }

    /**
     * Прив'язати в'юпорт сцени до поточного множника.
     *
     * <p>Викликати ПЕРЕД {@code viewport.update(...)}: {@code update} рахує
     * світ із того {@code unitsPerPixel}, який стоїть на момент виклику.
     */
    public static void apply(ScreenViewport viewport) {
        if (viewport == null) return;
        viewport.setUnitsPerPixel(1f / get());
    }

    /**
     * Те саме, але для явно заданої висоти вікна.
     *
     * <p>Потрібно в {@code resize(w, h)}: там нова висота приходить аргументом,
     * і покладатись на {@code Gdx.graphics} під час самого зворотного виклику не
     * можна — порядок оновлення залежить від бекенда.
     */
    public static void apply(ScreenViewport viewport, int screenHeight) {
        if (viewport == null) return;
        viewport.setUnitsPerPixel(1f / forHeight(screenHeight));
    }

    /** Створити в'юпорт сцени, уже прив'язаний до множника. */
    public static ScreenViewport createViewport() {
        ScreenViewport vp = new ScreenViewport();
        apply(vp);
        return vp;
    }
}
