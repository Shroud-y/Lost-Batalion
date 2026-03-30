package io.jababa.lost_batalion;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import io.jababa.lost_batalion.screens.MainMenuScreen;

/**
 * Ентріпоінт
 */
public class LostBatalion extends Game {

    public SpriteBatch batch;

    private InputMultiplexer inputMultiplexer;

    @Override
    public void create() {

        inputMultiplexer = new InputMultiplexer();
        inputMultiplexer.addProcessor(buildGlobalInput());
        Gdx.input.setInputProcessor(inputMultiplexer);

        batch = new SpriteBatch();
        setScreen(new MainMenuScreen(this));
    }

    private InputAdapter buildGlobalInput() {
        return new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.F11) {
                    toggleFullscreen();
                    return true; // подія оброблена, далі не передаємо
                }
                return false;
            }
        };
    }

    private void toggleFullscreen() {
        if (Gdx.graphics.isFullscreen()) {
            // Повернутись у режим вікна з попередніми розмірами
            Gdx.graphics.setWindowedMode(1280, 720);
        } else {
            // Перейти у повноекранний режим з нативною роздільною здатністю монітора
            Graphics.DisplayMode displayMode = Gdx.graphics.getDisplayMode();
            Gdx.graphics.setFullscreenMode(displayMode);
        }
    }

    public void setScreenInputProcessor(com.badlogic.gdx.InputProcessor screenProcessor) {
        // index 0 = globalInput, index 1 = екран (замінюємо або додаємо)
        if (inputMultiplexer.size() > 1) {
            inputMultiplexer.removeProcessor(1);
        }
        inputMultiplexer.addProcessor(screenProcessor);
    }

    @Override
    public void dispose() {

        super.dispose();
        batch.dispose();
    }
}
