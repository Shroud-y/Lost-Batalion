package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
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

    public PauseSettingsOverlay(Stage stage, final Runnable onBack) {
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

        panel.add(new SettingsPanel().getTable()).width(PANEL_WIDTH).row();

        PlateButton back = PlateButton.action("НАЗАД");
        back.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { onBack.run(); }
        });
        panel.add(back).size(BACK_WIDTH, BACK_HEIGHT).right().padTop(24f).row();

        scrim.add(panel);
        stage.addActor(scrim);
    }
}
