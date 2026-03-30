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

    protected BaseScreen(LostBatalion game) {
        this.game = game;
    }

    protected abstract void buildUI();

    private void rebuildStage() {

        UIFactory.disposeAll();
        if (stage != null) {
            stage.dispose();
        }
        stage = new Stage(new ExtendViewport(900, 580), game.batch);
        Gdx.input.setInputProcessor(stage);
        buildUI();
    }

    @Override
    public void show() {
        rebuildStage();
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0.08f, 0.08f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        game.batch.setProjectionMatrix(stage.getCamera().combined);

        stage.act(delta);
        stage.draw();
    }

    @Override
    public void resize(int width, int height) {

        rebuildStage();
    }

    @Override public void pause()  {}
    @Override public void resume() {}

    @Override
    public void hide() {
        Gdx.input.setInputProcessor(null);
    }

    @Override
    public void dispose() {
        if (stage != null) stage.dispose();
    }
}
