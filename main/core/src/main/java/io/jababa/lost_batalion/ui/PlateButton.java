package io.jababa.lost_batalion.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/**
 * Кнопка інтерфейсу з плавним наведенням (DESIGN §5).
 *
 * <h3>Навіщо власний клас</h3>
 * Штатний {@link Button} просто підміняє {@code style.over} на {@code style.up}
 * — перехід відбувається за один кадр, і на око це смикання, а не підсвітка.
 * Гірше того: {@code Table} бере власні відступи з драбла тла, а девʼятки
 * станів мають різні сплайни, тож підміна ще й зсуває напис. Крос-фейду в
 * scene2d немає взагалі, тож єдиний спосіб — тримати власну частку наведення і
 * малювати обидві пластини одна поверх одної.
 *
 * <p>Натискання при цьому лишається МИТТЄВИМ. Плавність доречна там, де курсор
 * ковзає по меню сам собою, але відгук на клік має бути одразу: розмазаний
 * натиск читається як підгальмовування інтерфейсу.
 */
public final class PlateButton extends Button {

    /** Вид кнопки. Різниця не лише в тлі — різна поведінка підпису. */
    public enum Variant {
        /** Пункт списку: планка ліворуч, підпис при лівому краї, зсув при наведенні. */
        PLATE,
        /** Разова дія: без планки, підпис по центру, без зсуву. */
        ACTION
    }

    /** Скільки секунд триває поява підсвітки. */
    private static final float FADE_IN  = 0.12f;
    /** ...і скільки — згасання. Довше: різкий обрив помітніший за різку появу. */
    private static final float FADE_OUT = 0.20f;

    /** Відступ назви від лівого краю плитки в спокої і під курсором. */
    private static final float TEXT_PAD_REST  = 18f;
    private static final float TEXT_PAD_HOVER = 24f;

    private final Drawable idle;
    private final Drawable hot;
    private final Drawable pressedBg;

    private final Variant variant;
    private final Label name;
    private final Color restColor;
    private final Color hoverColor;
    private final Color tint = new Color();

    /** Чи приглушений підпис — пункт, який зараз не можна взяти. */
    private boolean muted;

    /** Частка наведення, 0..1. */
    private float hover;

    /** Пункт меню або списку. */
    public static PlateButton plate(String title) {
        return new PlateButton(UIFactory.createMenuPlateStyle(), Variant.PLATE, title,
                               UIFactory.createMenuItemStyle());
    }

    /** Пункт меню на СПІЛЬНОМУ стилі — коли таких кнопок поруч кілька. */
    public static PlateButton plate(ButtonStyle sharedStyle, String title) {
        return new PlateButton(sharedStyle, Variant.PLATE, title,
                               UIFactory.createMenuItemStyle());
    }

    /** Компактна кнопка дії. */
    public static PlateButton action(String title) {
        return new PlateButton(UIFactory.createActionStyle(), Variant.ACTION, title,
                               UIFactory.createActionLabelStyle());
    }

    /** Кнопка дії на спільному стилі. */
    public static PlateButton action(ButtonStyle sharedStyle, String title) {
        return new PlateButton(sharedStyle, Variant.ACTION, title,
                               UIFactory.createActionLabelStyle());
    }

    /**
     * @param style стиль від {@code UIFactory}. Не змінюється: базовому
     *              {@link Button} дістається копія лише з {@code up}, а стани
     *              малюються тут вручну
     */
    private PlateButton(ButtonStyle style, Variant variant, String title,
                        Label.LabelStyle labelStyle) {
        super(baseStyle(style));

        this.idle      = style.up;
        this.hot       = style.over;
        this.pressedBg = style.down;
        this.variant   = variant;

        this.restColor  = UIFactory.itemRestColor();
        this.hoverColor = UIFactory.itemHoverColor();

        this.name = new Label(title, labelStyle);
        this.name.setColor(this.restColor);

        if (variant == Variant.PLATE) {
            add(this.name).left().padLeft(TEXT_PAD_REST).expandX();
        } else {
            add(this.name).center().pad(0f, 14f, 0f, 14f).expandX();
        }
    }

    /** Змінити текст уже створеної кнопки (статус лоббі, «Готовий/Скасувати»). */
    public void setText(String text) {
        name.setText(text);
    }

    public String getText() {
        return name.getText().toString();
    }

    /**
     * Приглушити підпис: пункт видно, але взяти його зараз не можна (не
     * вистачає золота). Разом із {@code setDisabled} — саме воно вимикає
     * підсвітку й клік, а тон каже, ЧОМУ кнопка не реагує.
     */
    public void setMuted(boolean muted) {
        if (this.muted == muted) return;
        this.muted = muted;
        // act() перефарбовує підпис лише коли міняється частка наведення, а
        // приглушена кнопка не наводиться взагалі — тон доводиться поставити тут.
        name.setColor(muted ? UIFactory.itemMutedColor() : restColor);
    }

    /**
     * Стиль без {@code over}/{@code down} — щоб {@link Button} не підміняв тло
     * стрибком і не смикав відступи.
     *
     * <p>Копія, а не обнуління полів оригіналу: стиль буває спільним на групу
     * кнопок, і та, що будується першою, забрала б драбли собі, а решта
     * лишилась би взагалі без підсвітки.
     */
    private static ButtonStyle baseStyle(ButtonStyle src) {
        ButtonStyle only = new ButtonStyle();
        only.up = src.up;
        return only;
    }

    @Override
    public void act(float delta) {
        super.act(delta);

        // Вимкнена кнопка не підсвічується: підсвітка обіцяє, що натискання
        // щось зробить, а воно не зробить.
        boolean lit = !isDisabled() && (isOver() || isPressed());
        float step  = delta / (lit ? FADE_IN : FADE_OUT);
        float next  = MathUtils.clamp(lit ? hover + step : hover - step, 0f, 1f);
        if (next == hover) return;
        hover = next;

        // Прискорення на початку і гальмування в кінці. З лінійним рухом
        // підсвітка стартує й зупиняється однаково різко, і саме ці два моменти
        // око й читає як смикання.
        float k = Interpolation.smooth.apply(hover);

        if (!muted) name.setColor(tint.set(restColor).lerp(hoverColor, k));

        if (variant == Variant.PLATE) {
            // Назва зсувається всередину — рух підказує, що пункт «взято».
            // Кнопці дії цього не робимо: підпис у неї по центру, і зсув
            // виглядав би як промах розкладки.
            getCell(name).padLeft(MathUtils.lerp(TEXT_PAD_REST, TEXT_PAD_HOVER, k));
            invalidate();
        }
    }

    /**
     * Тло малюється шарами замість підміни: спокійна пластина завжди, гаряча —
     * з непрозорістю {@link #hover}, натиснута — поверх усього і без плавності.
     */
    @Override
    protected void drawBackground(Batch batch, float parentAlpha, float x, float y) {
        float w = getWidth(), h = getHeight();
        Color c = getColor();
        float a = c.a * parentAlpha;

        if (idle != null) {
            batch.setColor(c.r, c.g, c.b, a);
            idle.draw(batch, x, y, w, h);
        }
        if (hot != null && hover > 0f) {
            batch.setColor(c.r, c.g, c.b, a * Interpolation.smooth.apply(hover));
            hot.draw(batch, x, y, w, h);
        }
        if (pressedBg != null && isPressed()) {
            batch.setColor(c.r, c.g, c.b, a);
            pressedBg.draw(batch, x, y, w, h);
        }
        batch.setColor(c.r, c.g, c.b, a);
    }
}
