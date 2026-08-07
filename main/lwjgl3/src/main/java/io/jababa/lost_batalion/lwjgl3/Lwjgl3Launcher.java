package io.jababa.lost_batalion.lwjgl3;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import io.jababa.lost_batalion.LostBatalion;

/** Launches the desktop (LWJGL3) application. */
public class Lwjgl3Launcher {
    public static void main(String[] args) {
        try {
            registerSteamBackend();
            createApplication();
        } catch (Throwable t) {
            t.printStackTrace(); // Це виведе помилку червоним у консоль
            writeCrashReport(t);
            System.exit(1);
        } finally {
            shutdownSteam();
        }
    }

    /**
     * Додати мережевий бекенд Steam, якщо модуль {@code :steam} є в класпасі.
     *
     * <p>Саме через рефлексію, а не прямим {@code new SteamBackend()}: тоді
     * збірка без Steam (DRM-free) робиться видаленням ОДНОГО рядка залежності
     * в {@code lwjgl3/build.gradle} і не потребує правок коду. Відсутній клас —
     * штатна ситуація, а не помилка.
     *
     * <p>Тут лише реєстрація. Самі нативи Steamworks піднімаються пізніше й
     * ліниво: їх завантажувач ходить у {@code Gdx.files}, якого до старту
     * {@code Lwjgl3Application} ще не існує.
     */
    private static void registerSteamBackend() {
        try {
            Class<?> type = Class.forName("io.jababa.lost_batalion.steam.SteamBackend");
            io.jababa.lost_batalion.net.api.MultiplayerServices.register(
                (io.jababa.lost_batalion.net.api.NetBackend)
                    type.getDeclaredConstructor().newInstance());
        } catch (ClassNotFoundException e) {
            // Збірка без Steam — лишається локальна мережа.
        } catch (Throwable t) {
            System.err.println("Steam-бекенд не зареєстровано: " + t);
        }
    }

    /**
     * {@code SteamAPI.shutdown()} на виході.
     *
     * <p>У {@code finally}, а не в {@code LostBatalion.dispose()}: гру можуть
     * закрити й падінням, а незакритий Steamworks лишає по собі фонові потоки,
     * через які процес не вмирає до кінця.
     */
    private static void shutdownSteam() {
        try {
            Class.forName("io.jababa.lost_batalion.steam.SteamBoot")
                 .getMethod("shutdown").invoke(null);
        } catch (Throwable ignored) {
            // Немає модуля або він і не піднімався — нічого закривати.
        }
    }

    /**
     * Записати звіт про падіння у ДОМАШНЮ теку користувача.
     *
     * <p>Раніше тут стояв відносний шлях {@code "desktop_crash.txt"}, і це була
     * та сама пастка, що колись зі знімками екрана: робоча тека при запуску
     * через Gradle — це {@code main/assets/}, тож звіт лягав прямо в асети.
     * Звідти його забирав {@code generateAssetList}, вносив у {@code assets.txt}
     * і пакував у jar — тобто стектрейс із машини розробника роз'їжджався
     * гравцям у кожній збірці, ще й потрапляв у git.
     *
     * <p>{@code Gdx.files} тут використати не можна: падіння могло статись до
     * того, як libGDX узагалі піднявся, тож шлях будується через
     * {@code user.home} звичайним java.nio.
     *
     * <p>Файл із міткою часу, а не один на всі рази: друге падіння не повинно
     * стирати сліди першого.
     */
    private static void writeCrashReport(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            // Повний стек, а не t.toString(): раніше писався лише рядок із
            // назвою винятку, з якого неможливо сказати, ДЕ саме впало.
            t.printStackTrace(new java.io.PrintWriter(sw));

            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                .format(new java.util.Date());

            java.nio.file.Path dir = java.nio.file.Paths
                .get(System.getProperty("user.home"), "LostBatalion");
            java.nio.file.Files.createDirectories(dir);

            java.nio.file.Path out = dir.resolve("crash-" + stamp + ".txt");
            java.nio.file.Files.write(out, sw.toString().getBytes("UTF-8"));

            System.err.println("Звіт про падіння: " + out.toAbsolutePath());
        } catch (Exception ignored) {
            // Не вдалося записати — не привід падати ще раз поверх падіння.
        }
    }

    private static Lwjgl3Application createApplication() {
        return new Lwjgl3Application(new LostBatalion(), getDefaultConfiguration());
    }

    private static Lwjgl3ApplicationConfiguration getDefaultConfiguration() {
        Lwjgl3ApplicationConfiguration configuration = new Lwjgl3ApplicationConfiguration();
        configuration.setTitle("Lost Batalion");
        //// Vsync limits the frames per second to what your hardware can display, and helps eliminate
        //// screen tearing. This setting doesn't always work on Linux, so the line after is a safeguard.
        configuration.useVsync(true);
        //// Limits FPS to the refresh rate of the currently active monitor, plus 1 to try to match fractional
        //// refresh rates. The Vsync setting above should limit the actual FPS to match the monitor.
        configuration.setForegroundFPS(Lwjgl3ApplicationConfiguration.getDisplayMode().refreshRate + 1);
        //// If you remove the above line and set Vsync to false, you can get unlimited FPS, which can be
        //// useful for testing performance, but can also be very stressful to some hardware.
        //// You may also need to configure GPU drivers to fully disable Vsync; this can cause screen tearing.

        //// Вікно нерозтяжне: розмір міняється тільки списком у налаштуваннях
        //// (ui/ScreenResolution). HUD рахується від UIScale і на довільному
        //// розмірі не ламається, але перевірити вигляд можна лише на скінченному
        //// наборі — а вільне перетягування давало вікна, де панель виділення
        //// займала пів екрана.
        configuration.setResizable(false);
        //// Стартовий розмір збігається з ScreenResolution.DEFAULT_*. Справжній
        //// ставить LostBatalion.create() зі збережених налаштувань — раніше
        //// Preferences ще не існує.
        configuration.setWindowedMode(1280, 720);
        configuration.setBackBufferConfig(8, 8, 8, 8, 16, 8, 0);
        //// You can change these files; they are in lwjgl3/src/main/resources/ .
        //// They can also be loaded from the root of assets/ .
        configuration.setWindowIcon("libgdx128.png", "libgdx64.png", "libgdx32.png", "libgdx16.png");

        //// Режим автознімків (-Dlb.shotAt=...) мусить пережити те, що людина за
        //// комп'ютером у цей час працює. Типово libGDX ЗУПИНЯЄ цикл рендеру,
        //// коли вікно згорнуте, — а знімається саме кадровий буфер, тож досить
        //// було збити фокус, щоб прогін завершився без жодного файлу.
        //// Поза цим режимом усе лишається як було: звичайній грі спинятись
        //// у згорнутому стані правильно, вона не має гріти процесор у фоні.
        if (System.getProperty("lb.shotAt") != null) {
            configuration.setPauseWhenMinimized(false);
            configuration.setPauseWhenLostFocus(false);
        }

        //// This could improve compatibility with Windows machines with buggy OpenGL drivers, Macs
        //// with Apple Silicon that have to emulate compatibility with OpenGL anyway, and more.
        //// This uses the dependency `com.badlogicgames.gdx:gdx-lwjgl3-angle` to function.
        //// You would need to add this line to lwjgl3/build.gradle , below the dependency on `gdx-backend-lwjgl3`:
        ////     implementation "com.badlogicgames.gdx:gdx-lwjgl3-angle:$gdxVersion"
        //// You can choose to add the following line and the mentioned dependency if you want; they
        //// are not intended for games that use GL30 (which is compatibility with OpenGL ES 3.0).
        //// Know that it might not work well in some cases.
//        configuration.setOpenGLEmulation(Lwjgl3ApplicationConfiguration.GLEmulation.ANGLE_GLES20, 0, 0);

        return configuration;
    }
}
