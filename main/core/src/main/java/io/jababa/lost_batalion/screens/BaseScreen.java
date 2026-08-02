package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.audio.MusicManager;
import io.jababa.lost_batalion.screens.menu.MenuBackdrop;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.ui.UIScale;


/**
 * Базовий екран меню.
 *
 * <p>Тримає спільне для всіх екранів поза матчем: сцену, фон-карту і появу
 * (DESIGN §1, §5). Через це підклас пише лише власний вміст — і жоден екран не
 * може випадково лишитись на плоскій заливці.
 */
public abstract class BaseScreen implements Screen {

    /** Карта, яку видно на тлі всіх меню. Поки що сценарій у грі один. */
    private static final String BACKDROP_MAP = "scenarios/Zhovty_Vodu.png";

    /** Скільки триває поява екрана. */
    private static final float FADE_IN = 0.35f;

    protected final LostBatalion game;
    protected Stage stage;

    /**
     * Тло живе довше за сцену: сцена перебудовується на кожній зміні розміру
     * вікна, і перечитувати з диска карту 1440×1440 щоразу, коли користувач
     * тягне край вікна, — це помітні ривки на рівному місці.
     */
    private MenuBackdrop backdrop;

    private int lastWidth  = -1;
    private int lastHeight = -1;

    protected BaseScreen(LostBatalion game) {
        this.game = game;
    }

    protected abstract void buildUI();

    /**
     * Чи малювати фон-карту. Перекрий і поверни {@code false}, якщо екран має
     * власне тло на весь кадр.
     */
    protected boolean wantsBackdrop() {
        return true;
    }

    /** Скільки триває поява. Нуль вимикає її для цього екрана. */
    protected float fadeInDuration() {
        return FADE_IN;
    }

    private void rebuildStage(int width, int height) {
        UIFactory.disposeAll();

        if (stage != null) {
            stage.dispose();
        }

        // Світ сцени сталий: повзунок масштабу інтерфейсу керує тільки HUD
        // матчу, а меню й так ростуть разом із вікном.
        stage = new Stage(new ExtendViewport(UIScale.MENU_WORLD_WIDTH,
                                             UIScale.MENU_WORLD_HEIGHT), game.batch);
        stage.getViewport().update(width, height, true);
        game.setScreenInputProcessor(stage);

        lastWidth  = width;
        lastHeight = height;

        if (wantsBackdrop()) {
            if (backdrop == null) backdrop = new MenuBackdrop(BACKDROP_MAP);
            // Першим у сцену — щоб опинитись під усім вмістом. Розмір тло
            // тримає саме, див. MenuBackdrop.act.
            stage.addActor(backdrop);
        }

        buildUI();

        // Різкий показ готового екрана виглядає як смикання, надто коли тло вже
        // почало рухатись.
        float fade = fadeInDuration();
        if (fade > 0f) {
            stage.getRoot().getColor().a = 0f;
            stage.getRoot().addAction(Actions.fadeIn(fade));
        }
    }

    @Override
    public void show() {
        // Кожен екран поза матчем — це «меню». Повторний виклик із тим самим
        // режимом менеджер ігнорує, тож перехід меню → налаштування → меню
        // музику не чіпає.
        if (game.music() != null) game.music().setContext(MusicManager.Context.MENU);
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

        // Майже чорний, а не синюватий: фон-карта і сама достатньо темна, а
        // видно цю заливку лише там, куди карта не дістала.
        Gdx.gl.glClearColor(0.016f, 0.024f, 0.031f, 1f);
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
        if (backdrop != null) {
            backdrop.dispose();
            backdrop = null;
        }
    }
}
