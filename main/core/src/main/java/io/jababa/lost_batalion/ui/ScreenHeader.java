package io.jababa.lost_batalion.ui;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;

/**
 * Шапка внутрішнього екрана: кнопка повернення, заголовок, лінійка під ними.
 *
 * <p>Одна на всі екрани навмисно. Шапка — це те, що гравець бачить першим на
 * кожному переході, і якщо кнопка «Назад» щоразу стоїть на іншому місці, екрани
 * перестають відчуватись як одна гра.
 *
 * <p>Заголовок вирівняний по лівому краю, як і колонка меню (DESIGN §4):
 * центрований заголовок над лівим вмістом читається як чужий елемент.
 */
public final class ScreenHeader extends Table {

    private static final float BACK_WIDTH  = 128f;
    private static final float BACK_HEIGHT = 36f;
    /** Проміжок між кнопкою повернення і заголовком. */
    private static final float TITLE_GAP   = 26f;
    /** Відступ від заголовка до лінійки. */
    private static final float RULE_GAP    = 14f;

    /**
     * @param title  заголовок екрана; показується як є, у верхньому регістрі
     * @param onBack що робити по «Назад»; {@code null} — кнопки не буде
     */
    public ScreenHeader(String title, final Runnable onBack) {
        Table row = new Table();

        if (onBack != null) {
            // «, а НЕ ←: у main.ttf стрілок немає взагалі (перевірено по cmap),
            // і на екрані з них вийшов би порожній квадрат. Гільмет у шрифті є,
            // читається так само і пасує антикві краще за ASCII-«<».
            PlateButton back = PlateButton.action("« НАЗАД");
            back.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) { onBack.run(); }
            });
            row.add(back).size(BACK_WIDTH, BACK_HEIGHT).left().padRight(TITLE_GAP);
        }

        row.add(new Label(title, UIFactory.createScreenTitleStyle())).left().expandX();

        add(row).growX().row();
        add(new Image(UIFactory.createRuleDrawable())).height(1f).growX().padTop(RULE_GAP).row();
    }
}
