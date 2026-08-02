package io.jababa.lost_batalion.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import io.jababa.lost_batalion.LostBatalion;

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

    /** Висота вікна, на якій автоматичний множник дорівнює одиниці. */
    public static final float REFERENCE_HEIGHT = 720f;

    public static final float MIN  = 0.75f;
    public static final float MAX  = 2.0f;
    public static final float STEP = 0.25f;

    /** Межі особистого множника з налаштувань: 50–200%. */
    public static final float USER_MIN  = 0.5f;
    public static final float USER_MAX  = 2.0f;
    public static final float USER_STEP = 0.1f;

    /**
     * Світ сцени меню. СТАЛИЙ — повзунок масштабу його не чіпає.
     *
     * <p>Повзунок керує лише інтерфейсом МАТЧУ: мінікартою, панеллю виділення,
     * панеллю військ, меню паузи. Меню поза матчем — це суцільні scene2d-таблиці
     * в {@code ExtendViewport}, вони і без того ростуть разом із вікном, і
     * другий множник поверх них лише ламав би розкладку заради нічого.
     */
    public static final float MENU_WORLD_WIDTH  = 900f;
    public static final float MENU_WORLD_HEIGHT = 580f;

    /**
     * Стеля щільності запікання шрифту.
     *
     * <p>Гліфи печуться вдвічі більшими за потрібний кегль і малюються з
     * {@code setScale(0.5)} — це і є запас різкості. Понад цю щільність атлас
     * шрифту росте квадратично й упирається в максимальний розмір текстури, а
     * виграш на око вже непомітний. На поєднанні 1440p + 200% текст трохи
     * мʼякший за ідеал — це усвідомлений розмін.
     */
    private static final float FONT_DENSITY_CAP = 3f;

    /**
     * Особистий множник із налаштувань, кешований.
     *
     * <p>{@link #get()} кличеться десятки разів за кадр (кожна перевірка
     * влучання по HUD), а {@code Preferences} — це похід у файлову абстракцію.
     * Значення читається один раз і оновлюється через {@link #refreshUser()},
     * коли гравець посунув повзунок.
     */
    private static float user = -1f;

    private UIScale() {}

    /** Підсумковий множник для поточного вікна: автоматичний × особистий. */
    public static float get() {
        return scaleFor(Gdx.graphics.getHeight());
    }

    /**
     * Підсумковий множник для заданої висоти вікна.
     *
     * <p>Потрібен у {@code resize(w, h)}: там нова висота приходить аргументом,
     * і покладатись на {@code Gdx.graphics} під час зворотного виклику не можна.
     */
    public static float scaleFor(int screenHeight) {
        return forHeight(screenHeight) * user();
    }

    /**
     * АВТОМАТИЧНА частина множника — лише за висотою вікна, без налаштувань.
     * Виділено окремо заради тестів і макетів.
     */
    public static float forHeight(int screenHeight) {
        if (screenHeight <= 0) return 1f;
        float raw     = screenHeight / REFERENCE_HEIGHT;
        float snapped = Math.round(raw / STEP) * STEP;
        if (snapped < MIN) return MIN;
        if (snapped > MAX) return MAX;
        return snapped;
    }

    /** Особистий множник із налаштувань (50–200%). */
    public static float user() {
        if (user < 0f) refreshUser();
        return user;
    }

    /** Перечитати особистий множник із налаштувань. */
    public static void refreshUser() {
        user = clampUser(LostBatalion.Settings.getUiScale());
    }

    /**
     * Записати особистий множник.
     *
     * <p>Саме по собі це нічого не перемальовує: стилі, шрифти й вʼюпорти вже
     * створені під старе значення. Той, хто це кличе, зобовʼязаний перескласти
     * сцени — інакше повзунок «спрацює» аж на наступному запуску.
     */
    public static void setUser(float value) {
        LostBatalion.Settings.setUiScale(clampUser(value));
        refreshUser();
    }

    private static float clampUser(float v) {
        if (v < USER_MIN) return USER_MIN;
        if (v > USER_MAX) return USER_MAX;
        return v;
    }

    /**
     * У скільки разів пекти гліфи відносно кегля стилю.
     *
     * <p>Береться максимум із двох сцен — HUD і меню, — бо шрифти в них спільні:
     * запекти під дрібнішу означало б розмити текст у більшій. Меню особистого
     * множника не мають, тож їхня щільність залежить лише від висоти вікна.
     * Одиниця — це колишня поведінка (рівно двократний запас), тобто нижче
     * базового рівня різкості щільність не опускається навіть на 50%.
     */
    public static float fontDensity() {
        float hud  = get();
        float menu = Gdx.graphics.getHeight() / MENU_WORLD_HEIGHT;
        float d = Math.max(hud, menu);
        if (d < 1f) return 1f;
        return d > FONT_DENSITY_CAP ? FONT_DENSITY_CAP : d;
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
        viewport.setUnitsPerPixel(1f / scaleFor(screenHeight));
    }

    /** Створити в'юпорт сцени, уже прив'язаний до множника. */
    public static ScreenViewport createViewport() {
        ScreenViewport vp = new ScreenViewport();
        apply(vp);
        return vp;
    }
}
