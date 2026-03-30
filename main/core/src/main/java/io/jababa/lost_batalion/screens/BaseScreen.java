package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Базовий екран
 */
public abstract class BaseScreen implements Screen {

    protected final LostBatalion game;
    protected Stage stage;

    private int lastWidth  = -1;
    private int lastHeight = -1;

    protected BaseScreen(LostBatalion game) {
        this.game = game;
    }

    protected abstract void buildUI();

    private void rebuildStage(int width, int height) {
        UIFactory.disposeAll();

        if (stage != null) {
            stage.dispose();
        }

        stage = new Stage(new ExtendViewport(900, 580), game.batch);
        stage.getViewport().update(width, height, true);
        game.setScreenInputProcessor(stage);

        lastWidth  = width;
        lastHeight = height;

        buildUI();
    }

    @Override
    public void show() {
        rebuildStage(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
    }

    @Override
    public void resize(int width, int height) {
        if (stage == null) return;

        if (width == lastWidth && height == lastHeight) {

            stage.getViewport().update(width, height, true);
            return;
        }

        rebuildStage(width, height);
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.08f, 0.08f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        game.batch.setProjectionMatrix(stage.getCamera().combined);
        stage.act(delta);
        stage.draw();
    }

    @Override public void pause()  {}
    @Override public void resume() {}

    @Override
    public void hide() {
        game.setScreenInputProcessor(new com.badlogic.gdx.InputAdapter());
    }

    @Override
    public void dispose() {
        if (stage != null) stage.dispose();
    }
}
