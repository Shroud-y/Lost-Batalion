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

    /**
     * Екран може згаснути просто посеред власного кадру.
     *
     * <p>Слухач кнопки або мережева подія викликає {@code game.setScreen()}, а
     * той звільняє попередній екран НЕГАЙНО — тобто {@code dispose()} відпрацює
     * ще до того, як цей метод дійде до наступного рядка. Далі чіпати сцену не
     * можна: від неї лишився {@code null} (або вже інша, якщо стався
     * {@code rebuildStage}). Тому сцена береться в локальну змінну і звіряється
     * після кожного місця, звідки міг піти перехід.
     */
    @Override
    public void render(float delta) {
        Stage current = stage;
        if (current == null) return;

        Gdx.gl.glClearColor(0.08f, 0.08f, 0.12f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        game.batch.setProjectionMatrix(current.getCamera().combined);
        current.act(delta);

        // act() міг виконати слухач кнопки, а той — перемкнути екран.
        if (stage != current) return;
        current.draw();
    }

    @Override public void pause()  {}
    @Override public void resume() {}

    @Override
    public void hide() {
        game.setScreenInputProcessor(new com.badlogic.gdx.InputAdapter());
    }

    @Override
    public void dispose() {
        if (stage != null) {
            stage.dispose();
            stage = null;   // повторний dispose має бути безпечним
        }
    }


}
