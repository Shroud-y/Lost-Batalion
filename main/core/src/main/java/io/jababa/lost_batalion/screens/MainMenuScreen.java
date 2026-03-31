package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Екран головного меню
 */
public class MainMenuScreen extends BaseScreen {

    private static final float BUTTON_WIDTH = 280f;
    private static final float BUTTON_HEIGHT =  50f;
    private static final float BUTTON_SPACING = 14f;

    public MainMenuScreen(LostBatalion game) {
        super(game);
    }

    @Override
    protected void buildUI() {

        Label title = new Label("LOST BATTALION", UIFactory.createTitleStyle());
        Label subtitle = new Label("Real-Time Strategy", UIFactory.createSubtitleStyle());

        TextButton btnScenario = createButton("Select Scenario");
        TextButton btnSettings = createButton("Settings");
        TextButton btnExit = createButton("Exit");

        btnScenario.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                game.setScreen(new io.jababa.lost_batalion.screens.scenario.ScenarioScreen(game));
            }
        });

        btnSettings.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                // TODO: game.setScreen(new SettingsScreen(game));
            }
        });

        btnExit.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                Gdx.app.exit();
            }
        });

        Table root = new Table();
        root.setFillParent(true);

        root.add(title).padBottom(4f).row();
        root.add(subtitle).padBottom(60f).row();

        root.add(btnScenario).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(BUTTON_SPACING).row();
        root.add(btnSettings).size(BUTTON_WIDTH, BUTTON_HEIGHT).padBottom(BUTTON_SPACING).row();
        root.add(btnExit).size(BUTTON_WIDTH, BUTTON_HEIGHT).row();

        stage.addActor(root);
    }

    private TextButton createButton(String text) {
        return new TextButton(text, UIFactory.createMenuButtonStyle());
    }
}
