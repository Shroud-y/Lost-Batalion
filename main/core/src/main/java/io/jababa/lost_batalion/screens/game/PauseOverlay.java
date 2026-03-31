package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.ui.UIFactory;

public class PauseOverlay {

    public interface PauseListener {
        void onResume();
        void onReturnToLobby();
        void onSettings();
        void onExit();
    }

    private final Stage stage;

    public PauseOverlay(Stage stage, PauseListener listener) {
        this.stage = stage;
        build(listener);
    }

    private void build(PauseListener listener) {
        Table backdrop = new Table();
        backdrop.setFillParent(true);
        backdrop.setBackground(UIFactory.createColorDrawable(new Color(0f, 0f, 0f, 0.65f)));

        Table menu = new Table();
        menu.setBackground(UIFactory.createColorDrawable(new Color(0.10f, 0.10f, 0.16f, 0.97f)));
        menu.pad(36f);

        Label title = new Label("Paused", UIFactory.createScreenTitleStyle());

        TextButton btnResume = btn("Resume");
        TextButton btnLobby = btn("Return to Lobby");
        TextButton btnSettings = btn("Settings");
        TextButton btnExit = btn("Exit Game");

        btnResume.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { listener.onResume(); }
        });
        btnLobby.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { listener.onReturnToLobby(); }
        });
        btnSettings.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { listener.onSettings(); }
        });
        btnExit.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { listener.onExit(); }
        });

        float bw = 320f, bh = 58f, sp = 14f;
        menu.add(title).padBottom(28f).row();
        menu.add(btnResume)  .size(bw, bh).padBottom(sp).row();
        menu.add(btnLobby)   .size(bw, bh).padBottom(sp).row();
        menu.add(btnSettings).size(bw, bh).padBottom(sp).row();
        menu.add(btnExit)    .size(bw, bh).row();

        backdrop.add(menu);
        stage.addActor(backdrop);
    }

    private TextButton btn(String text) {
        return new TextButton(text, UIFactory.createSmallButtonStyle());
    }

    public Stage getStage() { return stage; }
}
