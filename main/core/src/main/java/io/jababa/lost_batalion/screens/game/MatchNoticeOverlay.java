package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Модальне вікно «матч далі не піде»: зв'язок втрачено або грати вже нема з ким.
 *
 * <p>Окреме від {@link DesyncOverlay} саме тому, що виходу з цього стану немає:
 * там була кнопка «Синхронізувати» і надія, тут лишається тільки вихід. Одне
 * вікно на два різні випадки мусило б ховати половину себе, і рано чи пізно
 * показало б кнопку синхронізації там, де синхронізувати нема з ким.
 */
public class MatchNoticeOverlay {

    public interface Listener {
        void onLeave();
    }

    private final Table root;
    private final Label textLabel;

    public MatchNoticeOverlay(Stage stage, String title, String text, Listener listener) {
        Table backdrop = new Table();
        backdrop.setFillParent(true);
        backdrop.setBackground(UIFactory.createColorDrawable(new Color(0f, 0f, 0f, 0.72f)));

        Table panel = new Table();
        panel.setBackground(UIFactory.createColorDrawable(new Color(0.10f, 0.10f, 0.16f, 0.97f)));
        panel.pad(34f);

        textLabel = new Label(text, UIFactory.createBodyStyle());
        textLabel.setWrap(true);
        textLabel.setAlignment(Align.center);

        TextButton leaveBtn = new TextButton("Вийти в меню", UIFactory.createMenuButtonStyle());
        leaveBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { listener.onLeave(); }
        });

        panel.add(new Label(title, UIFactory.createScreenTitleStyle())).padBottom(18f).row();
        panel.add(textLabel).width(520f).padBottom(26f).row();
        panel.add(leaveBtn).size(300f, 54f).row();

        backdrop.add(panel);
        root = backdrop;
        stage.addActor(backdrop);
    }

    public void setText(String text) { textLabel.setText(text); }

    public void remove() { root.remove(); }
}
