package io.jababa.lost_batalion.screens.menu;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;

/**
 * Плитка головного меню з плавним наведенням.
 *
 * <h3>Навіщо власний клас</h3>
 * Штатний {@link Button} просто підміняє {@code style.over} на {@code style.up}
 * — перехід відбувається за один кадр, і на око це смикання, а не підсвітка.
 * Крос-фейду між станами в scene2d немає взагалі, тож єдиний спосіб — тримати
 * власну частку наведення і малювати обидві пластини одна поверх одної.
 *
 * <p>Натискання при цьому лишається МИТТЄВИМ. Плавність доречна там, де курсор
 * ковзає по меню сам собою, але відгук на клік має бути одразу: розмазаний
 * натиск читається як підгальмовування інтерфейсу.
 */
public final class MenuPlate extends Button {

    /** Скільки секунд триває поява підсвітки. */
    private static final float FADE_IN  = 0.12f;
    /** ...і скільки — згасання. Довше: різкий обрив помітніший за різку появу. */
    private static final float FADE_OUT = 0.20f;

    /** Відступ назви від лівого краю в спокої і під курсором. */
    private static final float TEXT_PAD_REST  = 18f;
    private static final float TEXT_PAD_HOVER = 24f;

    private final Drawable idle;
    private final Drawable hot;
    private final Drawable pressedBg;

    private final Label name;
    private final Color restColor;
    private final Color hoverColor;
    private final Color tint = new Color();

    /** Частка наведення, 0..1. */
    private float hover;

    /**
     * @param style стиль від {@code UIFactory.createMenuPlateStyle()}. Не
     *              змінюється: базовому {@link Button} дістається копія лише з
     *              {@code up}, а стани малюються тут вручну
     */
    public MenuPlate(ButtonStyle style, String title, Label.LabelStyle labelStyle,
                     Color restColor, Color hoverColor) {
        super(baseStyle(style));

        this.idle      = style.up;
        this.hot       = style.over;
        this.pressedBg = style.down;

        this.restColor  = new Color(restColor);
        this.hoverColor = new Color(hoverColor);

        this.name = new Label(title, labelStyle);
        this.name.setColor(this.restColor);
        add(this.name).left().padLeft(TEXT_PAD_REST).expandX();
    }

    /**
     * Стиль без {@code over}/{@code down} — щоб {@link Button} не підміняв тло
     * стрибком і не смикав відступи.
     *
     * <p>Копія, а не обнуління полів оригіналу: стиль спільний на всі плитки,
     * і та, що будується першою, забрала б драбли собі, а решта лишилась би
     * взагалі без підсвітки.
     *
     * <p>Побічно зникає ще одне смикання: девʼятки станів мають різні сплайни
     * (планка 2px проти 5px), а {@code Table} бере власні відступи саме з тла —
     * тож підміна зсувала напис на три пікселі за кадр.
     */
    private static ButtonStyle baseStyle(ButtonStyle src) {
        ButtonStyle only = new ButtonStyle();
        only.up = src.up;
        return only;
    }

    @Override
    public void act(float delta) {
        super.act(delta);

        boolean lit = isOver() || isPressed();
        float step  = delta / (lit ? FADE_IN : FADE_OUT);
        float next  = MathUtils.clamp(lit ? hover + step : hover - step, 0f, 1f);
        if (next == hover) return;
        hover = next;

        // Прискорення на початку і гальмування в кінці. З лінійним рухом
        // підсвітка стартує й зупиняється однаково різко, і саме ці два моменти
        // око й читає як смикання.
        float k = Interpolation.smooth.apply(hover);

        name.setColor(tint.set(restColor).lerp(hoverColor, k));

        // Назва зсувається всередину — рух підказує, що пункт «взято».
        getCell(name).padLeft(MathUtils.lerp(TEXT_PAD_REST, TEXT_PAD_HOVER, k));
        invalidate();
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
