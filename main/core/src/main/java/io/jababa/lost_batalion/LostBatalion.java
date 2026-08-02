package io.jababa.lost_batalion;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import io.jababa.lost_batalion.audio.AudioManager;
import io.jababa.lost_batalion.audio.MusicManager;
import io.jababa.lost_batalion.screens.MainMenuScreen;
import io.jababa.lost_batalion.ui.ScreenResolution;
import io.jababa.lost_batalion.ui.ScreenResolution.WindowMode;

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

    /**
     * Фонова музика. Належить грі, а не екрану: {@link #setScreen} звільняє
     * попередній екран, і музика, прив'язана до нього, обривалась би на кожному
     * переході — навіть на вході в налаштування й назад.
     */
    private MusicManager music;

    private InputMultiplexer inputMultiplexer;

    /**
     * До якого НЕповноекранного режиму повертає F11.
     *
     * <p>Живе в полі, а не в налаштуваннях: це пам'ять сеансу про те, звідки
     * гравець пішов у повний екран. Без неї F11 із режиму «без рамки» повертав
     * би у звичайне вікно — тобто мовчки міняв налаштування, якого ніхто не
     * чіпав.
     */
    private WindowMode windowedPreference = WindowMode.WINDOWED;

    @Override
    public void create() {

        Gdx.app.setLogLevel(Application.LOG_DEBUG);

        // Режим і розмір вікна відновлюються тут, а не в лаунчері: Preferences
        // живуть у Gdx.app, якого на момент складання конфігурації ще не існує.
        windowedPreference = Settings.getWindowMode();
        if (windowedPreference == WindowMode.FULLSCREEN)
            windowedPreference = WindowMode.WINDOWED;
        ScreenResolution.applyMode(Settings.getWindowMode(),
                                      Settings.getResWidth(), Settings.getResHeight());

        inputMultiplexer = new InputMultiplexer();
        inputMultiplexer.addProcessor(buildGlobalInput());
        Gdx.input.setInputProcessor(inputMultiplexer);

        // Гучності читаються один раз і живуть у полях шини — постріли йдуть
        // десятками за секунду, і кожен похід у Preferences там зайвий.
        AudioManager.refresh();
        music = new MusicManager();

        batch = new SpriteBatch();
        setScreen(new MainMenuScreen(this));
    }

    /** Фонова музика застосунку. Екрани кажуть їй режим у своєму {@code show()}. */
    public MusicManager music() { return music; }

    /**
     * Кадр гри плюс фейди музики.
     *
     * <p>Оновлення музики стоїть саме тут, а не в екрані: воно має відбуватись
     * на КОЖНОМУ екрані й не перериватись на переходах між ними.
     */
    @Override
    public void render() {
        if (music != null) music.update(Gdx.graphics.getDeltaTime());
        super.render();
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

    /**
     * F11. Повертає в ТОЙ режим, з якого пішли в повний екран (вікно або без
     * рамки), і зі збереженою роздільністю — раніше тут стояло зашите
     * 1280×720, і перше ж натискання F11 мовчки скидало вибір гравця.
     */
    private void toggleFullscreen() {
        WindowMode current = Settings.getWindowMode();
        WindowMode next;

        if (current == WindowMode.FULLSCREEN) {
            next = windowedPreference;
        } else {
            windowedPreference = current;   // запам'ятати, куди повертатись
            next = WindowMode.FULLSCREEN;
        }

        Settings.setWindowMode(next);
        ScreenResolution.applyMode(next, Settings.getResWidth(), Settings.getResHeight());
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

        /**
         * Загальна гучність. Множиться на все, що звучить.
         *
         * <p>Ключ лишився старим (`volume`), хоч сенс уточнився з «гучність» на
         * «загальна гучність»: перейменування мовчки скинуло б налаштування
         * кожному, хто вже його чіпав.
         */
        public static float getVolume() { return getPrefs().getFloat("volume", 1f); }
        public static void setVolume(float val) { getPrefs().putFloat("volume", val).flush(); }

        /** Гучність музики. За замовчуванням тихіша за бій — це фон, а не подія. */
        public static float getMusicVolume() { return getPrefs().getFloat("volumeMusic", 0.7f); }
        public static void setMusicVolume(float val) { getPrefs().putFloat("volumeMusic", val).flush(); }

        /** Гучність ефектів: постріли, вибухи, інтерфейс. */
        public static float getSfxVolume() { return getPrefs().getFloat("volumeSfx", 1f); }
        public static void setSfxVolume(float val) { getPrefs().putFloat("volumeSfx", val).flush(); }

        public static boolean isFullscreen() { return getPrefs().getBoolean("fullscreen", false); }
        public static void setFullscreen(boolean val) { getPrefs().putBoolean("fullscreen", val).flush(); }

        /**
         * Режим вікна: у вікні / без рамки / повний екран.
         *
         * <p>Якщо ключа ще немає, він добудовується зі старої галочки
         * {@code fullscreen} — інакше гравець, який колись увімкнув повний
         * екран, після оновлення отримав би вікно без пояснень.
         */
        public static WindowMode getWindowMode() {
            WindowMode fallback =
                isFullscreen()
                    ? WindowMode.FULLSCREEN
                    : WindowMode.WINDOWED;
            return WindowMode.parse(
                    getPrefs().getString("windowMode", null), fallback);
        }

        public static void setWindowMode(WindowMode mode) {
            // Стара галочка лишається в синхроні: на неї ще дивиться пункт
            // «На весь екран» у збережених налаштуваннях старих збірок.
            getPrefs().putString("windowMode", mode.name())
                      .putBoolean("fullscreen",
                          mode == WindowMode.FULLSCREEN)
                      .flush();
        }

        /**
         * Роздільність вікна. Зберігається окремо від прапорця повного екрана:
         * це те, куди гра повернеться, вийшовши з нього.
         */
        public static int getResWidth() {
            return getPrefs().getInteger("resW", ScreenResolution.DEFAULT_W);
        }
        public static int getResHeight() {
            return getPrefs().getInteger("resH", ScreenResolution.DEFAULT_H);
        }
        public static void setResolution(int w, int h) {
            getPrefs().putInteger("resW", w).putInteger("resH", h).flush();
        }

        /**
         * Особистий множник розміру інтерфейсу, 0.5–2.0.
         *
         * <p>Множиться на автоматичний масштаб за висотою вікна, а не заміняє
         * його: автоматичний тримає HUD однакового розміру ВІДНОСНО ЕКРАНА на
         * різних роздільностях, і викидати його заради повзунка означало б
         * повернути стару ваду — той самий інтерфейс удвічі дрібніший на 1440p.
         */
        public static float getUiScale() { return getPrefs().getFloat("uiScale", 1f); }
        public static void setUiScale(float val) { getPrefs().putFloat("uiScale", val).flush(); }

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

        if (music != null) {
            music.dispose();
            music = null;
        }

        io.jababa.lost_batalion.screens.menu.MenuBackdrop.disposeShared();

        super.dispose();
        batch.dispose();
    }
}
