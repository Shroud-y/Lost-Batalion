package io.jababa.lost_batalion.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;

/**
 * Квадратик учасника: аватарка, а поки її немає — перша літера ніка.
 *
 * <p>Літера, а не порожня пластина, бо порожньої пластини в лоббі буде
 * БІЛЬШІСТЬ: у локальній мережі аватарок немає взагалі, у бота їх не буде
 * ніколи, а в Steam картинка приїжджає за кілька кадрів після входу. Ряд
 * однакових порожніх квадратів не розрізняє нікого, а літера розрізняє одразу —
 * і вона ж лишається на місці, коли аватарка нарешті стає.
 *
 * <p>Текстуру віджет НЕ звільняє: нею володіє {@code AvatarSource}, який живе
 * довше за будь-який екран (див. його javadoc). Сюди приходить уже готове
 * посилання або {@code null}.
 */
public final class AvatarBox extends Table {

    /**
     * @param avatar      готова аватарка або {@code null}
     * @param nick        нік, з якого береться літера; порожній дає «?»
     * @param letterStyle стиль літери — СПІЛЬНИЙ, а не свій на кожен квадратик:
     *                    їх у кімнаті до десятка, і кожен виклик фабрики пече
     *                    новий атлас шрифту (DESIGN §6)
     * @param dim         приглушити — для бота, у якого аватарки не буде ніколи
     */
    public AvatarBox(Texture avatar, String nick, Label.LabelStyle letterStyle, boolean dim) {
        this(avatar, nick, letterStyle, dim, null);
    }

    /**
     * @param outline колір власника для обводки, або {@code null} — тоді
     *                звичайна рамка рядка. Обводка саме РАМКОЮ, а не заливкою:
     *                залитий кольором квадрат сперечався б і з акцентом, і з
     *                кольорами сторін (DESIGN §1)
     */
    public AvatarBox(Texture avatar, String nick, Label.LabelStyle letterStyle, boolean dim,
                     Color outline) {
        setBackground(outline == null ? UIFactory.createRowBackground(false)
                                      : UIFactory.createOwnerBackground(outline));
        // Відступ у піксель — щоб вміст не ліг ПОВЕРХ рамки й не з'їв її.
        if (outline != null) pad(1f);

        if (avatar != null) {
            Image image = new Image(new TextureRegionDrawable(avatar));
            // Аватарка квадратна, як і сам квадратик, тож fill нічого не ріже.
            image.setScaling(Scaling.fill);
            add(image).grow();
            return;
        }

        Label letter = new Label(initial(nick), letterStyle);
        letter.setColor(dim ? UIFactory.itemMutedColor() : new Color(UIFactory.COLOR_TEXT));
        add(letter).center().expand();
    }

    private static String initial(String nick) {
        if (nick == null) return "?";
        String trimmed = nick.trim();
        if (trimmed.isEmpty()) return "?";
        return trimmed.substring(0, 1).toUpperCase();
    }
}
