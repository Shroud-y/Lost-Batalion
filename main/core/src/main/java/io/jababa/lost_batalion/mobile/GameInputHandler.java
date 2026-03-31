package io.jababa.lost_batalion.mobile;

import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.MathUtils;
import io.jababa.lost_batalion.screens.game.GameScreen;

public class GameInputHandler extends GestureDetector.GestureAdapter {

    private final GameScreen screen;

    public GameInputHandler(GameScreen screen) {
        this.screen = screen;
    }

    @Override
    public boolean zoom(float initialDistance, float distance) {
        if (screen.isPaused() || distance == 0) return false;

        float ratio = initialDistance / distance;
        float targetZoom = screen.getCamera().zoom * ratio;

        float lerpFactor = 0.1f;

        float currentZoom = screen.getCamera().zoom;
        float newZoom = currentZoom + (targetZoom - currentZoom) * lerpFactor;

        newZoom = MathUtils.clamp(newZoom, 0.3f, 2.0f);
        screen.setZoom(newZoom);

        return true;
    }
}
