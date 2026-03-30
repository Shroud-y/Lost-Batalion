package io.jababa.lost_batalion.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
public final class UIFactory {

    private static final String FONT_PATH = "fonts/main.ttf";

    private static final Array<BitmapFont>createdFonts = new Array<>();
    private static final Array<Texture>createdTextures = new Array<>();

    public static final Color COLOR_ACCENT = new Color(0.80f, 0.60f, 0.20f, 1f);
    public static final Color COLOR_BUTTON_IDLE = new Color(0.15f, 0.15f, 0.22f, 0.95f);
    public static final Color COLOR_BUTTON_OVER = new Color(0.25f, 0.25f, 0.38f, 1f);
    public static final Color COLOR_BUTTON_DOWN = new Color(0.10f, 0.10f, 0.15f, 1f);
    public static final Color COLOR_TEXT = new Color(0.90f, 0.88f, 0.80f, 1f);

    private UIFactory() {}

    public static TextButton.TextButtonStyle createMenuButtonStyle() {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
        style.font = generateFont(screenRelativeSize(0.035f), COLOR_TEXT);
        style.up = colorDrawable(COLOR_BUTTON_IDLE);
        style.over = colorDrawable(COLOR_BUTTON_OVER);
        style.down = colorDrawable(COLOR_BUTTON_DOWN);
        return style;
    }

    public static Label.LabelStyle createTitleStyle() {
        Label.LabelStyle style = new Label.LabelStyle();
        style.font = generateFont(screenRelativeSize(0.09f), COLOR_ACCENT);
        style.fontColor = COLOR_ACCENT;
        return style;
    }

    public static Label.LabelStyle createSubtitleStyle() {
        Label.LabelStyle style = new Label.LabelStyle();
        style.font = generateFont(screenRelativeSize(0.025f), COLOR_TEXT);
        style.fontColor = COLOR_TEXT;
        return style;
    }
    public static Label.LabelStyle createScreenTitleStyle() {
        Label.LabelStyle style = new Label.LabelStyle();
        style.font = generateFont(32, COLOR_ACCENT);
        style.fontColor = COLOR_ACCENT;
        return style;
    }
    public static Label.LabelStyle createCardTitleStyle() {
        Label.LabelStyle style = new Label.LabelStyle();
        style.font = generateFont(20, COLOR_TEXT);
        style.fontColor = COLOR_TEXT;
        return style;
    }

    public static Label.LabelStyle createCardDescStyle() {
        Label.LabelStyle style = new Label.LabelStyle();
        style.font = generateFont(15, new Color(0.70f, 0.68f, 0.60f, 1f));
        style.fontColor = new Color(0.70f, 0.68f, 0.60f, 1f);
        return style;
    }

    public static TextButton.TextButtonStyle createSmallButtonStyle() {
        TextButton.TextButtonStyle style = new TextButton.TextButtonStyle();
        style.font = generateFont(18, COLOR_TEXT);
        style.up = colorDrawable(COLOR_BUTTON_IDLE);
        style.over = colorDrawable(COLOR_BUTTON_OVER);
        style.down = colorDrawable(COLOR_BUTTON_DOWN);
        return style;
    }

    public static void disposeAll() {
        for (BitmapFont font : createdFonts) font.dispose();
        createdFonts.clear();
        for (Texture tex : createdTextures) tex.dispose();
        createdTextures.clear();
    }

    public static Drawable createColorDrawable(Color color) {
        return colorDrawable(color);
    }

    private static int screenRelativeSize(float fraction) {
        return Math.max(12, (int) (Gdx.graphics.getHeight() * fraction));
    }
    private static BitmapFont generateFont(int size, Color color) {
        FreeTypeFontGenerator generator = new FreeTypeFontGenerator(
            Gdx.files.internal(FONT_PATH));

        FreeTypeFontParameter params = new FreeTypeFontParameter();
        params.size = size * 2;
        params.color = color;
        params.minFilter = TextureFilter.Linear;
        params.magFilter = TextureFilter.Linear;
        params.genMipMaps = true;

        BitmapFont font = generator.generateFont(params);
        font.getData().setScale(0.5f);
        generator.dispose();
        createdFonts.add(font);
        return font;
    }

    private static Drawable colorDrawable(Color color) {
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(color);
        pixmap.fill();
        Texture tex = new Texture(pixmap);
        pixmap.dispose();
        createdTextures.add(tex);
        return new TextureRegionDrawable(tex);
    }
}
