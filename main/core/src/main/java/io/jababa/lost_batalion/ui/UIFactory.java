package io.jababa.lost_batalion.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.List;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;

/**
 * Стилі інтерфейсу за декларацією {@code .claude/DESIGN.md}.
 *
 * <p>Усе будується процедурно з пікcмап — файлів тем у грі немає. Числа тут не
 * підбирались на око: палітра §2, кеглі §3, товщини §4 живуть у декларації, а
 * цей клас лише перекладає їх у scene2d.
 *
 * <p>Форма одна на весь інтерфейс: прямокутник, заливка з прозорістю, рамка в
 * один піксель. Заокруглень немає навмисно — вони читаються як елемент
 * застосунку, а гра має виглядати як калька, накладена на карту.
 */
public final class UIFactory {

    private static final String FONT_PATH = "fonts/main.ttf";

    /**
     * Символи, яких потребує САМЕ ця гра понад стандартний набір libGDX.
     *
     * <p>Перевірка покриття шрифту дивиться лише сюди. {@code DEFAULT_CHARS}
     * від libGDX — це збірна солянка з латиницею всіх мов, і половини її немає
     * в жодному нормальному шрифті. Скаржитись на неї означало б щоразу
     * друкувати попередження про літери, яких інтерфейс ніколи не покаже.
     */
    private static final String REQUIRED_CHARS =
          "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ"
        + "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя"
        + "ЁёЪъЫыЭэ"          // трапляються в чужих ніках
        + "…—–«»„“”’№°×÷";
    // ≈ ≤ ≥ звідси прибрані разом із переходом на Pixel Font7 (2026-08-03): у
    // самому шрифті їх немає, а в інтерфейсі вони й не траплялись — жили лише в
    // javadoc. Тримати їх у списку означало б помилку в лог на кожному запуску.

    /**
     * Набір символів, які треба згенерувати зі шрифту.
     *
     * <p>Задавати його ОБОВ'ЯЗКОВО. За замовчуванням FreeType бере лише
     * {@code DEFAULT_CHARS} — латиницю з цифрами, — і будь-яка кирилиця стає
     * порожнім квадратом навіть тоді, коли гліфи у файлі шрифту є.
     */
    private static final String FONT_CHARS =
        FreeTypeFontGenerator.DEFAULT_CHARS + REQUIRED_CHARS;
    // Піктограм ☰ ≡ ✕ тут навмисно НЕМАЄ: жоден із текстових шрифтів, що
    // пасують грі, їх не містить, а генерувати завідомо порожні гліфи — це
    // щоразу лякати лог фальшивим попередженням. Кнопки підписані словами.

    /** Чи вже сказали в лог, що шрифт неповний. Раз на запуск, не раз на стиль. */
    private static boolean fontCoverageReported;

    private static final Array<BitmapFont> createdFonts    = new Array<>();
    private static final Array<Texture>    createdTextures = new Array<>();

    // ── Палітра (DESIGN §2) ───────────────────────────────────────────────

    /** Акцент: планка, лінійка, одне слово заголовка. Заливкою НЕ буває. */
    public static final Color COLOR_ACCENT   = new Color(0.80f, 0.60f, 0.20f, 1f); // #CC9933
    /** Основний текст. */
    public static final Color COLOR_TEXT     = new Color(0.92f, 0.90f, 0.82f, 1f); // #EBE6D1
    /** Лінійка під заголовком. */
    public static final Color COLOR_MENU_RULE = new Color(0.47f, 0.43f, 0.34f, 0.55f);

    /** Назва пункту в спокої та під курсором. */
    private static final Color ITEM_REST  = new Color(0.82f, 0.80f, 0.73f, 1f); // #D1CCBA
    private static final Color ITEM_HOVER = new Color(0.96f, 0.93f, 0.84f, 1f); // #F5EDD6
    /** Другорядний підпис. */
    private static final Color NOTE       = new Color(0.47f, 0.45f, 0.41f, 1f); // #787369
    /** Помилки валідації та мережеві збої. */
    private static final Color DANGER     = new Color(0.78f, 0.35f, 0.28f, 1f);

    // Плитка: спокій → наведення світлішає й тепліє, натиск темнішає.
    // Два стани йдуть у РІЗНІ боки — на цьому тримається читабельність.
    private static final Color PLATE_IDLE = new Color(0.047f, 0.059f, 0.075f, 0.75f);
    private static final Color PLATE_OVER = new Color(0.094f, 0.110f, 0.133f, 0.88f);
    private static final Color PLATE_DOWN = new Color(0.031f, 0.039f, 0.051f, 0.92f);

    private static final Color EDGE_IDLE  = new Color(0.376f, 0.353f, 0.282f, 0.43f);
    private static final Color EDGE_OVER  = new Color(0.588f, 0.471f, 0.235f, 0.78f);
    private static final Color EDGE_DOWN  = new Color(0.310f, 0.290f, 0.235f, 0.60f);

    private static final Color MARK_IDLE  = new Color(0.376f, 0.353f, 0.282f, 0.60f);

    /** Фон панелі/картки — щільніший за плитку, бо на ньому лежить вміст. */
    private static final Color PANEL_BG    = new Color(0.047f, 0.059f, 0.075f, 0.86f);
    /** Рядок списку: майже прозорий, поки не вибраний. */
    private static final Color ROW_BG      = new Color(0.047f, 0.059f, 0.075f, 0.50f);
    private static final Color ROW_EDGE    = new Color(0.376f, 0.353f, 0.282f, 0.24f);
    private static final Color ROW_BG_ON   = new Color(0.094f, 0.110f, 0.133f, 0.80f);
    private static final Color ROW_EDGE_ON = new Color(0.80f, 0.60f, 0.20f, 0.60f);

    /** Затемнення під модальні вікна й паузу. */
    private static final Color SCRIM       = new Color(0.016f, 0.024f, 0.031f, 0.78f);

    // ── Кольори для HUD ───────────────────────────────────────────────────
    //
    // HUD усередині матчу малюється вручну через SpriteBatch/ShapeRenderer і
    // стилями scene2d користуватись не може. Щоб він не поїхав від решти гри,
    // кольори він бере звідси — з тих самих констант, що й панелі меню.

    /** Тло панелі HUD. */
    public static final Color COLOR_HUD_PANEL     = new Color(PANEL_BG);
    /** Рамка панелі HUD. */
    public static final Color COLOR_HUD_EDGE      = new Color(EDGE_IDLE);
    /** Тло комірки (портрет, кнопка) у спокої. */
    public static final Color COLOR_HUD_SLOT      = new Color(ROW_BG);
    /** Рамка комірки у спокої. */
    public static final Color COLOR_HUD_SLOT_EDGE = new Color(ROW_EDGE);
    /** Тло комірки в активному стані. */
    public static final Color COLOR_HUD_SLOT_ON   = new Color(PLATE_OVER);

    // ── Форма (DESIGN §4, §6) ─────────────────────────────────────────────

    /** Розмір заготовки плитки. Тягнеться лише середина, краї лишаються різкими. */
    private static final int PLATE_SIZE = 24;
    /** Ширина акцентної планки ліворуч у спокої / під курсором. */
    private static final int PLATE_MARK_IDLE = 2;
    private static final int PLATE_MARK_OVER = 5;

    // ── Кеглі (DESIGN §3) ─────────────────────────────────────────────────

    // ── Спільні поля розкладки (DESIGN §4) ────────────────────────────────

    /** Ліве й праве поле екрана. Те саме, що в колонки головного меню. */
    public static final float MARGIN     = 72f;
    /** Відступ шапки від верху. */
    public static final float HEADER_TOP = 40f;
    /** Нижнє поле. */
    public static final float FOOTER_PAD = 28f;

    private static final int SIZE_GAME_TITLE   = 46;
    private static final int SIZE_SCREEN_TITLE = 28;
    private static final int SIZE_ITEM         = 19;
    private static final int SIZE_ACTION       = 15;
    private static final int SIZE_BODY         = 16;
    private static final int SIZE_NOTE         = 12;

    private UIFactory() {}

    // ── Кнопки ────────────────────────────────────────────────────────────

    /**
     * Плитка списку: прямокутна пластина з акцентною планкою ліворуч, що
     * потовщується під курсором. Для пунктів меню й рядків вибору.
     *
     * <p>Це {@link Button.ButtonStyle}, а не {@code TextButtonStyle}: вміст
     * плитки складає викликач, а стиль дає лише тло. Анімацію станів робить
     * {@link PlateButton} — підміняти драбл на льоту не можна (див. DESIGN §6).
     */
    public static Button.ButtonStyle createMenuPlateStyle() {
        Button.ButtonStyle style = new Button.ButtonStyle();
        style.up   = platePatch(PLATE_IDLE, EDGE_IDLE, MARK_IDLE,    PLATE_MARK_IDLE);
        style.over = platePatch(PLATE_OVER, EDGE_OVER, COLOR_ACCENT, PLATE_MARK_OVER);
        style.down = platePatch(PLATE_DOWN, EDGE_DOWN, COLOR_ACCENT, PLATE_MARK_OVER);
        return style;
    }

    /**
     * Компактна кнопка дії: «Назад», «Обрати», «Готовий». Без планки — вона
     * позначає пункт списку, а не разову дію, і на дрібній кнопці читалась би
     * як обрізаний край.
     */
    public static Button.ButtonStyle createActionStyle() {
        Button.ButtonStyle style = new Button.ButtonStyle();
        style.up   = rectPatch(PLATE_IDLE, EDGE_IDLE);
        style.over = rectPatch(PLATE_OVER, EDGE_OVER);
        style.down = rectPatch(PLATE_DOWN, EDGE_DOWN);
        return style;
    }

    /**
     * Кнопка зовсім без тла — тільки підпис.
     *
     * <p>Для меню, яке розгортається просто поверх карти: пластина під кожним
     * рядком перетворила б легкий список на другу панель і закрила б поле бою
     * саме там, куди гравець дивиться, обираючи місце висадки. Стани при цьому
     * не зникають — {@link PlateButton} веде підсвітку підписом.
     */
    public static Button.ButtonStyle createGhostStyle() {
        return new Button.ButtonStyle();
    }

    // ── Написи ────────────────────────────────────────────────────────────
    //
    // Усі стилі меню й екранів будуються на БІЛОМУ запеченому шрифті
    // (див. generateTintableFont), тому колір виходить точно той, що заданий, і
    // його можна анімувати. Множенням світлішого не зробиш — DESIGN §6.

    /** Слово назви гри. Розріджене: суцільний набір у 46px виглядає збитим. */
    public static Label.LabelStyle createMenuWordStyle(Color color) {
        return label(SIZE_GAME_TITLE, 3f, color);
    }

    /** Заголовок екрана. */
    public static Label.LabelStyle createScreenTitleStyle() {
        return label(SIZE_SCREEN_TITLE, 2.5f, COLOR_ACCENT);
    }

    /**
     * Назва пункту меню.
     *
     * <p>{@code fontColor} тут навмисно {@code null}: колір задає САМ ярлик через
     * {@code setColor}, і меню міняє його при наведенні. Якби тон сидів у стилі,
     * підсвітити напис було б неможливо.
     */
    public static Label.LabelStyle createMenuItemStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateTintableFont(SIZE_ITEM, 2f);
        s.fontColor = null;
        return s;
    }

    /** Підпис на кнопці дії. Колір теж ставить сам ярлик — див. вище. */
    public static Label.LabelStyle createActionLabelStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateTintableFont(SIZE_ACTION, 1.5f);
        s.fontColor = null;
        return s;
    }

    /** Спокійний тон підпису на плитці — початкове значення для {@code setColor}. */
    public static Color itemRestColor()  { return new Color(ITEM_REST); }
    /** Тон під курсором. */
    public static Color itemHoverColor() { return new Color(ITEM_HOVER); }

    /** Звичайний текст у списках і формах. */
    public static Label.LabelStyle createBodyStyle() {
        return label(SIZE_BODY, 0f, COLOR_TEXT);
    }

    /** Лічильник золота в HUD. Акцент тут доречний: це головне число матчу. */
    public static Label.LabelStyle createGoldStyle() {
        return label(SIZE_ITEM, 1.5f, COLOR_ACCENT);
    }

    // ── Кольори сторін ────────────────────────────────────────────────────
    //
    // Одне джерело на весь матч: рахунок, підписи точок і зони на землі мусять
    // бути ОДНОГО кольору, інакше гравець не зв'яже цифру вгорі з плямою на
    // карті. Тони збігаються з CapturePointRenderer.

    /** Свій колір — синій. */
    public static final Color COLOR_TEAM_SELF    = new Color(0.35f, 0.60f, 1.00f, 1f);
    /** Ворожий колір — червоний. */
    public static final Color COLOR_TEAM_FOE     = new Color(1.00f, 0.35f, 0.30f, 1f);
    /** Нічия точка. */
    public static final Color COLOR_TEAM_NEUTRAL = new Color(0.62f, 0.60f, 0.55f, 1f);

    /**
     * Число рахунку. Крупніше за золото: це головне число партії, і читати його
     * мусить бути можливо, не відриваючись від поля.
     */
    public static Label.LabelStyle createScoreStyle(Color color) {
        return label(SIZE_SCREEN_TITLE - 4, 1.5f, color);
    }

    /**
     * Роздільник рахунку. Приглушений навмисно: двокрапка не несе інформації, і
     * рівна з цифрами вона перетягує погляд на себе.
     */
    public static Label.LabelStyle createScoreSeparatorStyle() {
        return label(SIZE_SCREEN_TITLE - 8, 1f, NOTE);
    }

    /**
     * Літера точки захоплення.
     *
     * <p>{@code fontColor} тут {@code null} навмисно: колір означає власника і
     * міняється щокадрово через {@code setColor}, а мигання їздить по альфі.
     * Якби тон сидів у стилі, підсвітити літеру було б неможливо — так само,
     * як у пунктах меню.
     */
    /**
     * Шрифт підписів ПОВЕРХ карти — позначки точок захоплення на полі й на
     * мінікарті.
     *
     * <p>Віддається сирим {@link BitmapFont}, а не стилем scene2d, бо ці підписи
     * малюються вручну в екранних координатах: вони прив'язані до світової
     * точки, а не до комірки таблиці, і жодна сцена їх не розкладає.
     *
     * <p>Звільняти його НЕ треба — як і всі шрифти звідси, він потрапляє в
     * {@link #disposeAll()}. Але після {@code disposeAll} посилання стає
     * недійсним, тож той, хто його тримає, мусить перезапитати шрифт разом із
     * перескладанням HUD.
     */
    public static BitmapFont createMapLabelFont() {
        return generateTintableFont(SIZE_NOTE + 2, 1f);
    }

    public static Label.LabelStyle createPointTagStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateTintableFont(SIZE_BODY, 2f);
        s.fontColor = null;
        return s;
    }

    /** Приглушений тон для недоступного пункту — те саме, що другорядний підпис. */
    public static Color itemMutedColor() { return new Color(NOTE); }

    /** Другорядний текст: підписи, лічильники, статуси, підвал. */
    public static Label.LabelStyle createHintStyle() {
        return label(SIZE_NOTE, 0f, NOTE);
    }

    /** Те саме, що {@link #createHintStyle()} — окрема назва для меню. */
    public static Label.LabelStyle createMenuNoteStyle() {
        return createHintStyle();
    }

    /** Помилки валідації та мережеві збої. */
    public static Label.LabelStyle createErrorStyle() {
        return label(SIZE_NOTE + 2, 0f, DANGER);
    }

    /** Підсвічений текст: свій нік у списку, «готовий», акценти. */
    public static Label.LabelStyle createAccentStyle() {
        return label(SIZE_BODY, 0f, COLOR_ACCENT);
    }

    /** Назва картки сценарію. */
    public static Label.LabelStyle createCardTitleStyle() {
        return label(SIZE_BODY + 2, 1.5f, COLOR_TEXT);
    }

    /** Опис картки сценарію. */
    public static Label.LabelStyle createCardDescStyle() {
        return label(SIZE_NOTE + 1, 0f, NOTE);
    }

    // ── Екран результату матчу (DESIGN §9) ────────────────────────────────

    /**
     * Слово результату — найбільший напис у грі після назви.
     *
     * <p>Розрідження 8 замість звичних 2–3: це єдиний напис, який стоїть сам
     * на порожній смузі, і щільний набір читався б там як заголовок діалогу, а
     * не як оголошення. Анімувати сам крок не можна — він запікається в
     * {@code xadvance} при генерації (DESIGN §6), тож поява йде прозорістю.
     */
    public static Label.LabelStyle createResultTitleStyle(Color color) {
        return label(SIZE_GAME_TITLE, 8f, color);
    }

    /** Число рахунку на екрані результату. Золоте, як і в HUD. */
    public static Label.LabelStyle createResultScoreStyle() {
        return label(SIZE_GAME_TITLE - 8, 2f, COLOR_ACCENT);
    }

    /** Число в колонці статистики. */
    public static Label.LabelStyle createResultStatStyle() {
        return label(SIZE_SCREEN_TITLE - 6, 1f, COLOR_TEXT);
    }

    /** Підпис під числом статистики. */
    public static Label.LabelStyle createResultCaptionStyle() {
        return label(SIZE_NOTE, 1.5f, NOTE);
    }

    /** Причина завершення — один рядок під заголовком. */
    public static Label.LabelStyle createResultCauseStyle() {
        return label(SIZE_NOTE + 1, 1f, NOTE);
    }

    private static Label.LabelStyle label(int size, float spacing, Color color) {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateTintableFont(size, spacing);
        s.fontColor = new Color(color);
        return s;
    }

    // ── Поля, повзунки, галочки ───────────────────────────────────────────

    public static TextField.TextFieldStyle createTextFieldStyle() {
        Color ghost = new Color(0.40f, 0.39f, 0.36f, 1f);

        TextField.TextFieldStyle s = new TextField.TextFieldStyle();
        s.font      = generateTintableFont(SIZE_BODY, 0f);
        s.fontColor = new Color(COLOR_TEXT);
        s.messageFont      = generateTintableFont(SIZE_BODY, 0f);
        s.messageFontColor = ghost;

        s.background        = rectPatch(PLATE_DOWN, EDGE_IDLE);
        s.focusedBackground = rectPatch(PLATE_DOWN, COLOR_ACCENT);

        Drawable cursor = flatDrawable(COLOR_ACCENT);
        cursor.setMinWidth(2f);
        s.cursor = cursor;

        s.selection = flatDrawable(new Color(0.30f, 0.26f, 0.16f, 1f));
        return s;
    }

    public static Slider.SliderStyle createSliderStyle() {
        Slider.SliderStyle style = new Slider.SliderStyle();

        // Жолоб — тонка врізана смуга, а не об'ємний брусок.
        style.background = rectPatch(PLATE_DOWN, EDGE_IDLE);
        style.background.setMinHeight(8f);

        // Заповнена частина світиться акцентом — це єдине місце, де значення
        // видно без цифри.
        style.knobBefore = flatDrawable(new Color(0.80f, 0.60f, 0.20f, 0.55f));
        style.knobBefore.setMinHeight(8f);

        style.knob = flatDrawable(COLOR_ACCENT);
        style.knob.setMinWidth(5f);
        style.knob.setMinHeight(22f);
        return style;
    }

    public static CheckBox.CheckBoxStyle createCheckBoxStyle() {
        CheckBox.CheckBoxStyle style = new CheckBox.CheckBoxStyle();
        style.font      = generateTintableFont(SIZE_BODY, 0f);
        style.fontColor = new Color(ITEM_REST);

        style.checkboxOff = rectPatch(PLATE_IDLE, EDGE_IDLE);
        style.checkboxOff.setMinWidth(22f);
        style.checkboxOff.setMinHeight(22f);

        style.checkboxOn = rectPatch(new Color(0.80f, 0.60f, 0.20f, 0.85f), COLOR_ACCENT);
        style.checkboxOn.setMinWidth(22f);
        style.checkboxOn.setMinHeight(22f);

        style.checkboxOver = rectPatch(PLATE_OVER, EDGE_OVER);
        style.checkboxOver.setMinWidth(22f);
        style.checkboxOver.setMinHeight(22f);
        return style;
    }

    /**
     * Випадний список (роздільність, режим вікна).
     *
     * <p>Зібраний із ТИХ САМИХ заготовок, що поле вводу й рядок списку —
     * {@code rectPatch(PLATE_*, EDGE_*)}. DESIGN §8 забороняє другий набір
     * правил вигляду, тож новим тут є лише збірка, а не жоден колір.
     *
     * <p>Стрілки в закритого списку немає навмисно: у шрифті немає ані ▼, ані
     * жодного іншого трикутника (перевірено по {@code cmap}), а малювати її
     * текстурою заради одного віджета — це той самий другий набір правил.
     * Список читається як поле, що реагує на наведення.
     */
    public static SelectBox.SelectBoxStyle createSelectBoxStyle() {
        SelectBox.SelectBoxStyle s = new SelectBox.SelectBoxStyle();

        s.font              = generateTintableFont(SIZE_BODY, 0f);
        s.fontColor         = new Color(COLOR_TEXT);
        s.disabledFontColor = new Color(NOTE);

        s.background         = rectPatch(PLATE_IDLE, EDGE_IDLE);
        s.backgroundOver     = rectPatch(PLATE_OVER, EDGE_OVER);
        s.backgroundOpen     = rectPatch(PLATE_DOWN, COLOR_ACCENT);
        s.backgroundDisabled = rectPatch(PLATE_DOWN, EDGE_DOWN);

        // Розгорнутий список лежить ПОВЕРХ екрана, тож фон у нього щільний —
        // напівпрозорий давав би читати крізь себе те, що під ним.
        ScrollPane.ScrollPaneStyle scroll = new ScrollPane.ScrollPaneStyle();
        scroll.background = rectPatch(PANEL_BG, COLOR_ACCENT);
        s.scrollStyle = scroll;

        List.ListStyle list = new List.ListStyle();
        list.font                = generateTintableFont(SIZE_BODY, 0f);
        list.fontColorSelected   = new Color(COLOR_ACCENT);
        list.fontColorUnselected = new Color(ITEM_REST);
        list.selection           = rectPatch(ROW_BG_ON, ROW_EDGE_ON);
        list.over                = rectPatch(ROW_BG,    ROW_EDGE);
        s.listStyle = list;

        return s;
    }

    // ── Тла ───────────────────────────────────────────────────────────────

    /**
     * Напівпрозоре затемнення на весь екран під модальні вікна.
     * Потрібне, щоб було видно: гра під ним не реагує, поки вікно відкрите.
     */
    public static Drawable createModalScrim() {
        return flatDrawable(SCRIM);
    }

    /** Фон панелі/картки. */
    public static Drawable createPanelBackground() {
        return rectPatch(PANEL_BG, EDGE_IDLE);
    }

    /** Фон рядка у списку. */
    public static Drawable createRowBackground(boolean highlighted) {
        return highlighted ? rectPatch(ROW_BG_ON, ROW_EDGE_ON)
                           : rectPatch(ROW_BG,    ROW_EDGE);
    }

    /** Горизонтальна лінійка-роздільник. */
    public static Drawable createRuleDrawable() {
        return flatDrawable(COLOR_MENU_RULE);
    }

    public static Drawable createColorDrawable(Color color) {
        return flatDrawable(color);
    }

    public static void disposeAll() {
        for (int i = 0; i < createdFonts.size;    i++) createdFonts.get(i).dispose();
        for (int i = 0; i < createdTextures.size; i++) createdTextures.get(i).dispose();
        createdFonts.clear();
        createdTextures.clear();
    }

    // ── Заготовки ─────────────────────────────────────────────────────────

    /** Пластина без планки — рамка й заливка. */
    private static NinePatchDrawable rectPatch(Color bg, Color border) {
        return platePatch(bg, border, border, 0);
    }

    /**
     * Прямокутна пластина: заливка, рамка в один піксель і акцентна планка
     * ліворуч.
     *
     * <p>Розтягується лише середина: сплайни девʼятки поставлені так, щоб планка
     * і рамка лишились рівно тієї товщини, з якою намальовані, на будь-якому
     * розмірі.
     */
    private static NinePatchDrawable platePatch(Color bg, Color border, Color mark, int markW) {
        int s = PLATE_SIZE;

        Pixmap pm = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        // Без Blending.None напівпрозора заливка змішалася б сама з собою, і
        // рамка поверх неї вийшла б брудною.
        pm.setBlending(Pixmap.Blending.None);

        pm.setColor(bg);
        pm.fill();

        pm.setColor(border);
        pm.drawRectangle(0, 0, s, s);

        if (markW > 0) {
            // Планка малюється ПОВЕРХ рамки: інакше на лівому краї лишалася б
            // однопіксельна смужка кольору рамки, і планка виглядала б брудною.
            pm.setColor(mark);
            pm.fillRectangle(0, 0, markW, s);
        }

        Texture tex = new Texture(pm);
        // Nearest: тут немає жодної діагоналі, а Linear розмив би однопіксельну
        // рамку в сірий градієнт.
        tex.setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        pm.dispose();
        createdTextures.add(tex);

        return new NinePatchDrawable(new NinePatch(tex, markW + 1, 1, 1, 1));
    }

    private static Drawable flatDrawable(Color color) {
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(color);
        pm.fill();
        Texture tex = new Texture(pm);
        pm.dispose();
        createdTextures.add(tex);
        return new TextureRegionDrawable(tex);
    }

    // ── Шрифти ────────────────────────────────────────────────────────────

    /**
     * Шрифт, придатний до фарбування.
     *
     * <p>FreeType ЗАПІКАЄ колір у текстуру гліфів, а {@code style.fontColor} і
     * колір актора домножуються поверх. Якщо запекти кольоровий, тон вийде у
     * квадраті — помітно темнішим за заявлений, — і його вже не підсвітити:
     * множення вміє лише темнити.
     *
     * <p>Тут запікається білий, тож єдиним джерелом кольору лишається тінт.
     */
    private static BitmapFont generateTintableFont(int size, float letterSpacing) {
        // Запас різкості. Був зашитий двократним, і цього вистачало, поки
        // масштаб інтерфейсу впирався в стелю 2.0×. З повзунком 50–200% верхня
        // межа поїхала вгору, і фіксований запас означав би розмитий текст рівно
        // там, де гравець просив ЗБІЛЬШИТИ інтерфейс.
        final float density = UIScale.fontDensity();

        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal(FONT_PATH));
        FreeTypeFontParameter p   = new FreeTypeFontParameter();
        p.size       = Math.max(1, Math.round(size * 2f * density));
        p.color      = Color.WHITE;
        p.characters = FONT_CHARS;
        p.minFilter  = TextureFilter.Linear;
        p.magFilter  = TextureFilter.Linear;
        p.genMipMaps = true;

        BitmapFont font = gen.generateFont(p);
        if (letterSpacing != 0f) applyLetterSpacing(font, letterSpacing, density);
        font.getData().setScale(0.5f / density);
        gen.dispose();
        createdFonts.add(font);

        reportCoverageOnce(font);
        return font;
    }

    /**
     * Розріджує набір. У libGDX немає властивості letter-spacing, тому крок
     * додається до {@code xadvance} кожного гліфа — це рівно те, що зробив би
     * такий параметр, якби існував.
     *
     * <p>Крок множиться на ту саму щільність, з якою запечено гліфи, і
     * скорочується подальшим {@code setScale(0.5/density)}; без цього розрідження
     * вийшло б у {@code density} разів меншим за замовлене. Викликати
     * ОБОВʼЯЗКОВО до {@code setScale}: масштаб множиться на {@code xadvance} під
     * час рендеру, і додавати після нього означало б додавати в інших одиницях.
     */
    private static void applyLetterSpacing(BitmapFont font, float pixels, float density) {
        int step = Math.round(pixels * 2f * density);
        if (step == 0) return;
        for (BitmapFont.Glyph[] page : font.getData().glyphs) {
            if (page == null) continue;
            for (BitmapFont.Glyph glyph : page) {
                if (glyph != null) glyph.xadvance += step;
            }
        }
    }

    /**
     * Один раз за запуск сказати в лог, яких гліфів шрифту бракує.
     *
     * <p>Порожній квадрат на екрані виглядає як помилка кодування, і на її
     * пошук легко витратити пів дня — хоча причина проста: у файлі шрифту
     * просто немає такої літери. Хай про це скаже лог, а не здогадка.
     */
    private static void reportCoverageOnce(BitmapFont font) {
        if (fontCoverageReported) return;
        fontCoverageReported = true;

        StringBuilder missing = new StringBuilder();
        for (int i = 0; i < REQUIRED_CHARS.length(); i++) {
            char c = REQUIRED_CHARS.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (font.getData().getGlyph(c) == null) missing.append(c);
        }
        if (missing.length() == 0) return;

        Gdx.app.error("UIFactory",
            "Шрифт " + FONT_PATH + " не має " + missing.length()
            + " потрібних гліфів — вони малюватимуться порожніми квадратами: "
            + missing);
    }
}
