package io.jababa.lost_batalion;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import io.jababa.lost_batalion.screens.MainMenuScreen;

/**
 * Ентріпоінт
 */
public class LostBatalion extends Game {

    /**
     * Версія гри — те, що показує підвал головного меню.
     *
     * <p>ПЛЕЙСХОЛДЕР: число проставлене вручну і ні з чим не звірене. Коли
     * зʼявиться нормальна збірка, значення має приїхати з Gradle (властивість
     * проєкту або згенерований клас), а не правитись тут перед кожним релізом.
     */
    public static final String VERSION = "0.1.0";

    public SpriteBatch batch;

    private InputMultiplexer inputMultiplexer;

    @Override
    public void create() {

        Gdx.app.setLogLevel(Application.LOG_DEBUG);

        inputMultiplexer = new InputMultiplexer();
        inputMultiplexer.addProcessor(buildGlobalInput());
        Gdx.input.setInputProcessor(inputMultiplexer);

        batch = new SpriteBatch();
        setScreen(new MainMenuScreen(this));
    }

    /**
     * Перемкнути екран і ЗВІЛЬНИТИ попередній.
     *
     * <p>Штатний {@code Game.setScreen} викликає лише {@code hide()} —
     * {@code dispose()} не викликається ніколи. Для більшості екранів це просто
     * витік: у кожного свій {@code Stage}, а в {@code GameScreen} ще й три
     * {@code SpriteBatch}, {@code ShapeRenderer}, текстури карти й масок. За
     * кілька матчів поспіль це десятки мегабайт, яких уже не повернути.
     *
     * <p>Але гірше інше: саме в {@code GameScreen.dispose()} живе закриття
     * мережевої сесії. Без нього хост, який вийшов із матчу в меню, далі
     * тримає відкритий порт і відповідає маячком — його лоббі назавжди висить
     * у списку в усіх у мережі, хоча грати там уже нема з ким.
     *
     * <p>Попередній екран звільняється ДО показу нового: {@code UIFactory}
     * тримає спільний кеш шрифтів і текстур, і {@code disposeAll()} із
     * {@code dispose()} старого екрана знищив би ресурси, які новий уже встиг
     * створити.
     */
    @Override
    public void setScreen(Screen next) {
        Screen previous = this.screen;
        if (previous == next) return;

        if (previous != null) {
            previous.hide();
            previous.dispose();
            // Обнуляємо, щоб super не викликав hide() вдруге.
            this.screen = null;
        }
        super.setScreen(next);
    }

    private InputAdapter buildGlobalInput() {
        return new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.F11) {
                    toggleFullscreen();
                    return true;
                }
                return false;
            }
        };
    }

    private void toggleFullscreen() {
        if (Gdx.graphics.isFullscreen()) {

            Gdx.graphics.setWindowedMode(1280, 720);
        } else {

            Graphics.DisplayMode displayMode = Gdx.graphics.getDisplayMode();
            Gdx.graphics.setFullscreenMode(displayMode);
        }
    }

    public void setScreenInputProcessor(com.badlogic.gdx.InputProcessor screenProcessor) {

        if (inputMultiplexer.size() > 1) {
            inputMultiplexer.removeProcessor(1);
        }
        inputMultiplexer.addProcessor(screenProcessor);
    }

    public class Settings {
        private static final String PREFS_NAME = "lost_batalion_settings";

        public static com.badlogic.gdx.Preferences getPrefs() {
            return com.badlogic.gdx.Gdx.app.getPreferences(PREFS_NAME);
        }

        public static float getVolume() { return getPrefs().getFloat("volume", 1f); }
        public static void setVolume(float val) { getPrefs().putFloat("volume", val).flush(); }

        public static boolean isFullscreen() { return getPrefs().getBoolean("fullscreen", false); }
        public static void setFullscreen(boolean val) { getPrefs().putBoolean("fullscreen", val).flush(); }

        /** Нік для мультиплеєра. Запам'ятовується, щоб не вводити його щоразу. */
        public static String getNick() { return getPrefs().getString("nick", ""); }
        public static void setNick(String val) { getPrefs().putString("nick", val == null ? "" : val).flush(); }
    }

    @Override
    public void dispose() {
        // Game.dispose() робить лише hide(). Якщо гру закрили просто з матчу,
        // без нього лишились би відкриті сокети — процес їх забере, але
        // покладатись на це не варто: на Android процес живе довше за гру.
        Screen current = this.screen;
        if (current != null) {
            current.hide();
            current.dispose();
            this.screen = null;
        }

        io.jababa.lost_batalion.screens.menu.MenuBackdrop.disposeShared();

        super.dispose();
        batch.dispose();
    }
}
