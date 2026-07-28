package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Пауза.
 *
 * <p>Панель тут центрована — на відміну від меню (DESIGN §4). Причина: під нею
 * не декоративна карта, а власний бій, і зсув панелі вбік означав би, що вона
 * закриває чийсь фланг. По центру вона однаково закриває обидва.
 */
public class PauseOverlay {

    public interface PauseListener {
        void onResume();
        void onReturnToLobby();
        void onSettings();
        void onExit();
    }

    private static final float PLATE_WIDTH  = 320f;
    private static final float PLATE_HEIGHT = 48f;
    private static final float PLATE_GAP    = 8f;

    private final Stage stage;

    public PauseOverlay(Stage stage, PauseListener listener) {
        this.stage = stage;
        build(listener);
    }

    private void build(final PauseListener listener) {
        Table scrim = new Table();
        scrim.setFillParent(true);
        scrim.setBackground(UIFactory.createModalScrim());

        // Один стиль на всі плитки — інакше кожна тягла б власний комплект
        // текстур (див. MainMenuScreen).
        Button.ButtonStyle plateStyle = UIFactory.createMenuPlateStyle();

        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(28f, 30f, 28f, 30f);

        Label title = new Label("ПАУЗА", UIFactory.createScreenTitleStyle());

        panel.add(title).left().row();
        panel.add(new Image(UIFactory.createRuleDrawable()))
             .height(1f).growX().padTop(12f).padBottom(22f).row();

        panel.add(plate(plateStyle, "ПРОДОВЖИТИ", listener::onResume))
             .size(PLATE_WIDTH, PLATE_HEIGHT).padBottom(PLATE_GAP).row();
        panel.add(plate(plateStyle, "НАЛАШТУВАННЯ", listener::onSettings))
             .size(PLATE_WIDTH, PLATE_HEIGHT).padBottom(PLATE_GAP).row();
        panel.add(plate(plateStyle, "ВИЙТИ В ЛОББІ", listener::onReturnToLobby))
             .size(PLATE_WIDTH, PLATE_HEIGHT).padBottom(PLATE_GAP).row();
        panel.add(plate(plateStyle, "ВИЙТИ З ГРИ", listener::onExit))
             .size(PLATE_WIDTH, PLATE_HEIGHT).row();

        scrim.add(panel);
        stage.addActor(scrim);
    }

    private Button plate(Button.ButtonStyle style, String title, final Runnable action) {
        PlateButton button = PlateButton.plate(style, title);
        button.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { action.run(); }
        });
        return button;
    }

    public Stage getStage() { return stage; }
}
