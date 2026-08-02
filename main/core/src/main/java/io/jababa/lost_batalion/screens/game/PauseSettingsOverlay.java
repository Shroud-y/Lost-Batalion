package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.screens.SettingsPanel;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Налаштування, відкриті з паузи, — друга сторінка того самого вікна.
 *
 * <h3>Чому не перехід на {@code SettingsScreen}</h3>
 * Перехід на інший екран звільняє {@code GameScreen} (див.
 * {@code LostBatalion.setScreen}), а разом із ним закривається мережева сесія.
 * Тобто «зайти в налаштування» з матчу означало б вийти з матчу — у
 * мультиплеєрі назавжди. Тому це не екран, а вміст того самого
 * {@code pauseStage}, і назад він вертається таким самим підмінюванням.
 *
 * <p>Вигляд повторює {@link PauseOverlay}: те саме затемнення, та сама
 * центрована панель із заголовком і лінійкою. Дві сторінки одного вікна мають
 * виглядати як одне вікно, інакше перехід читається як стрибок кудись іще.
 */
public class PauseSettingsOverlay {

    private static final float PANEL_WIDTH = 420f;
    private static final float BACK_WIDTH  = 160f;
    private static final float BACK_HEIGHT = 40f;

    /**
     * Висота, зайнята всім, крім самих рядків: поля панелі, заголовок із
     * лінійкою і кнопка «Назад». Сума з розкладки нижче.
     */
    private static final float CHROME_HEIGHT = 28f + 34f + 35f + 24f + BACK_HEIGHT + 28f;

    /** Менше цього список не стискається. */
    private static final float MIN_ROWS_HEIGHT = 120f;

    /**
     * @param onBack           повернутись до меню паузи
     * @param onUiScaleChanged перескласти інтерфейс матчу під новий масштаб;
     *                         сцену, на якій висить це вікно, при цьому буде
     *                         звільнено, тому виклик іде наступним кадром
     */
    public PauseSettingsOverlay(Stage stage, final Runnable onBack,
                                Runnable onUiScaleChanged) {
        Table scrim = new Table();
        scrim.setFillParent(true);
        scrim.setBackground(UIFactory.createModalScrim());

        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(28f, 30f, 28f, 30f);
        panel.top();

        panel.add(new Label("НАЛАШТУВАННЯ", UIFactory.createScreenTitleStyle())).left().row();
        panel.add(new Image(UIFactory.createRuleDrawable()))
             .height(1f).growX().padTop(12f).padBottom(22f).row();

        // Рядки прокручуються, якщо не влізли. На 200% масштабу логічна висота
        // сцени падає втричі відносно 1080p, і повний список у неї не входить —
        // без цього нижні пункти просто виїжджали б за екран.
        ScrollPane rows = new ScrollPane(new SettingsPanel(onUiScaleChanged).getTable());
        rows.setScrollingDisabled(true, false);
        rows.setFadeScrollBars(false);
        rows.setOverscroll(false, false);

        float available = Math.max(stage.getHeight() - CHROME_HEIGHT, MIN_ROWS_HEIGHT);
        panel.add(rows).width(PANEL_WIDTH).maxHeight(available).row();

        PlateButton back = PlateButton.action("НАЗАД");
        back.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { onBack.run(); }
        });
        panel.add(back).size(BACK_WIDTH, BACK_HEIGHT).right().padTop(24f).row();

        scrim.add(panel);
        stage.addActor(scrim);
    }
}
