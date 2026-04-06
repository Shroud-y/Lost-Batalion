package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ui.UIFactory;

public class SettingsScreen extends BaseScreen {

    public SettingsScreen(LostBatalion game) {
        super(game);
    }

    @Override
    protected void buildUI() {
        Table table = new Table();
        table.setFillParent(true);
        stage.addActor(table);

        // Title
        Label title = new Label("SETTINGS", UIFactory.createTitleStyle());
        table.add(title).padBottom(40).colspan(2).row();

        // 1. Sound Volume
        table.add(new Label("SOUND VOLUME", UIFactory.createSubtitleStyle())).pad(10);
        final Slider volumeSlider = new Slider(0, 1, 0.1f, false, UIFactory.createSliderStyle());
        volumeSlider.setValue(LostBatalion.Settings.getVolume());
        volumeSlider.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                LostBatalion.Settings.setVolume(volumeSlider.getValue());
            }
        });
        table.add(volumeSlider).width(300).pad(10).row();

        // 2. Fullscreen
        table.add(new Label("FULLSCREEN", UIFactory.createSubtitleStyle())).pad(10);
        final CheckBox fullScreenCb = new CheckBox("", UIFactory.createCheckBoxStyle());
        fullScreenCb.setChecked(Gdx.graphics.isFullscreen());
        fullScreenCb.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (fullScreenCb.isChecked()) {
                    Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
                } else {
                    Gdx.graphics.setWindowedMode(1280, 720);
                }
                LostBatalion.Settings.setFullscreen(fullScreenCb.isChecked());
            }
        });
        table.add(fullScreenCb).left().pad(10).row();

        // 3. Back Button
        TextButton backBtn = new TextButton("BACK", UIFactory.createMenuButtonStyle());
        backBtn.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                game.setScreen(new MainMenuScreen(game));
            }
        });
        table.add(backBtn).size(250, 60).padTop(50).colspan(2);
    }
}
