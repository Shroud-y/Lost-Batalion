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
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;

public final class UIFactory {

    private static final String FONT_PATH = "fonts/main.ttf";

    /**
     * Набір символів, які треба згенерувати зі шрифту.
     *
     * <p>Задавати його ОБОВ'ЯЗКОВО. За замовчуванням FreeType бере лише
     * {@code DEFAULT_CHARS} — латиницю з цифрами, — і будь-яка кирилиця стає
     * порожнім квадратом навіть тоді, коли гліфи у файлі шрифту є.
     *
     * <p>Сюди входить і пунктуація, якою реально користується інтерфейс:
     * трикрапка, тире, лапки, апостроф. Без них у текстах на кшталт
     * «Очікування…» останній символ теж перетворюється на квадрат.
     */
    private static final String FONT_CHARS =
        FreeTypeFontGenerator.DEFAULT_CHARS
        + "АБВГҐДЕЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЬЮЯ"
        + "абвгґдеєжзиіїйклмнопрстуфхцчшщьюя"
        + "ЁёЪъЫыЭэ"          // трапляються в чужих ніках
        + "…—–«»„“”’№°×÷≈≤≥"
        + "☰≡✕";              // піктограми кнопок HUD

    /** Чи вже сказали в лог, що шрифт неповний. Раз на запуск, не раз на стиль. */
    private static boolean fontCoverageReported;

    private static final Array<BitmapFont> createdFonts    = new Array<>();
    private static final Array<Texture>    createdTextures = new Array<>();
    public static final Color COLOR_ACCENT = new Color(0.80f, 0.60f, 0.20f, 1f);
    public static final Color COLOR_BUTTON_IDLE = new Color(0.13f, 0.13f, 0.20f, 0.96f);
    public static final Color COLOR_BUTTON_OVER = new Color(0.22f, 0.22f, 0.34f, 1f);
    public static final Color COLOR_BUTTON_DOWN = new Color(0.08f, 0.08f, 0.13f, 1f);
    public static final Color COLOR_TEXT = new Color(0.92f, 0.90f, 0.82f, 1f);

    private static final Color COLOR_BORDER_IDLE = new Color(0.38f, 0.35f, 0.52f, 1f);
    private static final Color COLOR_BORDER_OVER = new Color(0.55f, 0.50f, 0.72f, 1f);
    private static final Color COLOR_BORDER_DOWN = new Color(0.28f, 0.26f, 0.40f, 1f);

    private static final int PATCH_SIZE = 64;
    private static final int CORNER_R = 14;
    private static final int BORDER_W = 2;

    private UIFactory() {}

    public static TextButton.TextButtonStyle createMenuButtonStyle() {
        return roundedButtonStyle(screenRelativeSize(0.035f),
            COLOR_BUTTON_IDLE, COLOR_BORDER_IDLE,
            COLOR_BUTTON_OVER, COLOR_BORDER_OVER,
            COLOR_BUTTON_DOWN, COLOR_BORDER_DOWN);
    }

    public static TextButton.TextButtonStyle createSmallButtonStyle() {
        return roundedButtonStyle(18,
            COLOR_BUTTON_IDLE, COLOR_BORDER_IDLE,
            COLOR_BUTTON_OVER, COLOR_BORDER_OVER,
            COLOR_BUTTON_DOWN, COLOR_BORDER_DOWN);
    }

    public static Label.LabelStyle createTitleStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(screenRelativeSize(0.09f), COLOR_ACCENT);
        s.fontColor = COLOR_ACCENT;
        return s;
    }

    public static Label.LabelStyle createSubtitleStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(screenRelativeSize(0.025f), COLOR_TEXT);
        s.fontColor = COLOR_TEXT;
        return s;
    }

    public static Label.LabelStyle createScreenTitleStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(32, COLOR_ACCENT);
        s.fontColor = COLOR_ACCENT;
        return s;
    }

    public static Label.LabelStyle createCardTitleStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(20, COLOR_TEXT);
        s.fontColor = COLOR_TEXT;
        return s;
    }

    public static Label.LabelStyle createCardDescStyle() {
        Color c = new Color(0.70f, 0.68f, 0.60f, 1f);
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(15, c);
        s.fontColor = c;
        return s;
    }

    /** Звичайний текст у списках і формах. */
    public static Label.LabelStyle createBodyStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(17, COLOR_TEXT);
        s.fontColor = COLOR_TEXT;
        return s;
    }

    /** Другорядний текст: підписи, лічильники, статуси. */
    public static Label.LabelStyle createHintStyle() {
        Color c = new Color(0.62f, 0.60f, 0.54f, 1f);
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(14, c);
        s.fontColor = c;
        return s;
    }

    /** Помилки валідації та мережеві збої. */
    public static Label.LabelStyle createErrorStyle() {
        Color c = new Color(0.85f, 0.35f, 0.30f, 1f);
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(15, c);
        s.fontColor = c;
        return s;
    }

    /** Підсвічений текст: свій нік у списку, «готовий», акценти. */
    public static Label.LabelStyle createAccentStyle() {
        Label.LabelStyle s = new Label.LabelStyle();
        s.font = generateFont(17, COLOR_ACCENT);
        s.fontColor = COLOR_ACCENT;
        return s;
    }

    public static TextField.TextFieldStyle createTextFieldStyle() {
        TextField.TextFieldStyle s = new TextField.TextFieldStyle();
        s.font      = generateFont(18, COLOR_TEXT);
        s.fontColor = COLOR_TEXT;
        s.messageFont      = generateFont(18, new Color(0.45f, 0.44f, 0.40f, 1f));
        s.messageFontColor = new Color(0.45f, 0.44f, 0.40f, 1f);
        s.background = roundedNinePatch(new Color(0.08f, 0.08f, 0.12f, 1f), COLOR_BORDER_IDLE);
        s.focusedBackground = roundedNinePatch(new Color(0.08f, 0.08f, 0.12f, 1f), COLOR_ACCENT);

        Drawable cursor = flatDrawable(COLOR_ACCENT);
        cursor.setMinWidth(2f);
        s.cursor = cursor;

        Drawable selection = flatDrawable(new Color(0.30f, 0.28f, 0.45f, 1f));
        s.selection = selection;
        return s;
    }

    /**
     * Напівпрозоре затемнення на весь екран під модальні вікна.
     * Потрібне, щоб було видно: гра під ним не реагує, поки вікно відкрите.
     */
    public static Drawable createModalScrim() {
        return flatDrawable(new Color(0f, 0f, 0f, 0.72f));
    }

    /** Фон панелі/картки. */
    public static Drawable createPanelBackground() {
        return roundedNinePatch(new Color(0.13f, 0.13f, 0.20f, 0.98f), COLOR_BORDER_IDLE);
    }

    /** Фон рядка у списку — трохи світліший за екран, щоб рядки читались. */
    public static Drawable createRowBackground(boolean highlighted) {
        return highlighted
            ? roundedNinePatch(new Color(0.20f, 0.19f, 0.28f, 1f), COLOR_ACCENT)
            : roundedNinePatch(new Color(0.11f, 0.11f, 0.17f, 1f), COLOR_BORDER_IDLE);
    }

    public static void disposeAll() {
        for (int i = 0; i < createdFonts.size;    i++) createdFonts.get(i).dispose();
        for (int i = 0; i < createdTextures.size; i++) createdTextures.get(i).dispose();
        createdFonts.clear();
        createdTextures.clear();
    }

    public static Drawable createColorDrawable(Color color) {
        return flatDrawable(color);
    }


    private static TextButton.TextButtonStyle roundedButtonStyle(
        int fontSize,
        Color bgIdle, Color borderIdle,
        Color bgOver, Color borderOver,
        Color bgDown, Color borderDown) {

        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
        style.font = generateFont(fontSize, COLOR_TEXT);
        style.up   = roundedNinePatch(bgIdle, borderIdle);
        style.over = roundedNinePatch(bgOver, borderOver);
        style.down = roundedNinePatch(bgDown, borderDown);
        return style;
    }

    private static NinePatchDrawable roundedNinePatch(Color bg, Color border) {
        int s = PATCH_SIZE;
        int r = CORNER_R;
        int b = BORDER_W;

        Pixmap pm = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);

        int[][] corners = { {r, r}, {s - r - 1, r}, {r, s - r - 1}, {s - r - 1, s - r - 1} };

        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {

                Integer cx = null, cy = null;
                if (x < r && y < r)         { cx = r;     cy = r; }
                else if (x >= s-r && y < r) { cx = s-r-1; cy = r; }
                else if (x < r && y >= s-r) { cx = r;     cy = s-r-1; }
                else if (x >= s-r && y >= s-r) { cx = s-r-1; cy = s-r-1; }

                float fillAlpha, borderAlpha;

                if (cx != null) {

                    float dx   = x - cx + 0.5f;
                    float dy   = y - cy + 0.5f;
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);

                    fillAlpha   = clamp(r - dist + 0.5f);

                    float inner = r - b;
                    borderAlpha = clamp(r - dist + 0.5f) - clamp(inner - dist + 0.5f);
                } else {

                    boolean onBorder = (x < b || x >= s - b || y < b || y >= s - b);
                    fillAlpha   = 1f;
                    borderAlpha = onBorder ? 1f : 0f;
                }

                if (fillAlpha <= 0f) continue;

                pm.setColor(bg.r, bg.g, bg.b, bg.a * fillAlpha);
                pm.drawPixel(x, y);

                if (borderAlpha > 0.01f) {
                    float ba = border.a * borderAlpha;
                    float fa = bg.a * fillAlpha;

                    float outA = ba + fa * (1f - ba);
                    if (outA > 0f) {
                        float r2 = (border.r * ba + bg.r * fa * (1f - ba)) / outA;
                        float g2 = (border.g * ba + bg.g * fa * (1f - ba)) / outA;
                        float bl2= (border.b * ba + bg.b * fa * (1f - ba)) / outA;
                        pm.setColor(r2, g2, bl2, outA);
                        pm.drawPixel(x, y);
                    }
                }
            }
        }

        Texture tex = new Texture(pm);
        tex.setFilter(TextureFilter.Linear, TextureFilter.Linear);
        pm.dispose();
        createdTextures.add(tex);

        NinePatch patch = new NinePatch(tex, r, r, r, r);
        return new NinePatchDrawable(patch);
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

    private static int screenRelativeSize(float fraction) {
        return Math.max(12, (int) (580 * fraction));
    }

    private static BitmapFont generateFont(int size, Color color) {
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal(FONT_PATH));
        FreeTypeFontParameter p   = new FreeTypeFontParameter();
        p.size       = size * 2;
        p.color      = color;
        p.characters = FONT_CHARS;
        p.minFilter  = TextureFilter.Linear;
        p.magFilter  = TextureFilter.Linear;
        p.genMipMaps = true;
        BitmapFont font = gen.generateFont(p);
        font.getData().setScale(0.5f);
        gen.dispose();
        createdFonts.add(font);

        reportCoverageOnce(font);
        return font;
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
        for (int i = 0; i < FONT_CHARS.length(); i++) {
            char c = FONT_CHARS.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (font.getData().getGlyph(c) == null) missing.append(c);
        }
        if (missing.length() == 0) return;

        Gdx.app.error("UIFactory",
            "Шрифт " + FONT_PATH + " не має " + missing.length()
            + " потрібних гліфів — вони малюватимуться порожніми квадратами: "
            + missing);
    }

    public static Slider.SliderStyle createSliderStyle() {
        Slider.SliderStyle style = new Slider.SliderStyle();
        // Створюємо текстури для повзунка "на льоту"
        style.background = flatDrawable(new Color(0.2f, 0.2f, 0.2f, 1f));
        style.background.setMinHeight(10);
        style.knob = flatDrawable(COLOR_ACCENT);
        style.knob.setMinWidth(20);
        style.knob.setMinHeight(30);
        return style;
    }

    public static CheckBox.CheckBoxStyle createCheckBoxStyle() {
        CheckBox.CheckBoxStyle style = new CheckBox.CheckBoxStyle();
        style.font = generateFont(20, COLOR_TEXT);
        style.fontColor = COLOR_TEXT;
        // Використовуємо прості кольорові квадрати для галочки
        style.checkboxOff = flatDrawable(COLOR_BUTTON_IDLE);
        style.checkboxOff.setMinWidth(30);
        style.checkboxOff.setMinHeight(30);
        style.checkboxOn = flatDrawable(COLOR_ACCENT);
        style.checkboxOn.setMinWidth(30);
        style.checkboxOn.setMinHeight(30);
        return style;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
