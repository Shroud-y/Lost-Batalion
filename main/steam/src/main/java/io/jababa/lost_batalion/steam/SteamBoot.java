package io.jababa.lost_batalion.steam;

import com.badlogic.gdx.Gdx;
import com.codedisaster.steamworks.SteamAPI;
import com.codedisaster.steamworks.SteamException;
import com.codedisaster.steamworks.SteamLibraryLoader;
import com.codedisaster.steamworks.SteamLibraryLoaderGdx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Підняття Steamworks: нативи, {@code SteamAPI.init()}, такт колбеків.
 *
 * <p><b>Ініціалізація ЛІНИВА, і це не оптимізація.</b> Завантажувач нативів
 * {@code SteamLibraryLoaderGdx} розпаковує бібліотеки через {@code Gdx.files},
 * якого до старту {@code Lwjgl3Application} ще не існує — а бекенд
 * реєструється саме до нього. Тому реєстрація лише створює об'єкт, а справжній
 * старт стається на першому запиті ({@link #ensureBooted()}), тобто вже
 * всередині {@code LostBatalion.create()}.
 *
 * <p>Невдача — не помилка, а стан: без запущеного клієнта Steam гра має
 * спокійно лишитись на локальній мережі. Тому тут ніде немає кидання винятків
 * назовні, лише {@link #failure()} із причиною для інтерфейсу.
 */
public final class SteamBoot {

    private SteamBoot() {}

    /**
     * AppID тестового Spacewar. Для нього не потрібно ні власного застосунку в
     * Steamworks, ні грошей — рівно тому він і взятий на час розробки.
     */
    public static final int SPACEWAR_APP_ID = 480;

    /**
     * {@code -Dlb.steamAppId=480} — увімкнути Steam у сеансі розробки.
     *
     * <p>Без цієї властивості файл {@code steam_appid.txt} не створюється, і
     * {@code SteamAPI.init()} впаде для всіх, хто запустив гру не зі Steam.
     * Саме така поведінка й потрібна: у релізній збірці appid приходить від
     * самого клієнта Steam, а файл поруч із грою — це якраз те, що НЕ має
     * поїхати гравцям (з ним гра запуститься як Spacewar).
     */
    public static final String APP_ID_PROPERTY = "lb.steamAppId";

    private static boolean attempted;
    private static boolean running;
    private static String  failure;

    /** @return чи Steam піднявся. Ідемпотентно: реальна спроба рівно одна. */
    public static synchronized boolean ensureBooted() {
        if (attempted) return running;
        attempted = true;

        try {
            writeAppIdFile();

            SteamLibraryLoader loader = new SteamLibraryLoaderGdx();
            if (!SteamAPI.loadLibraries(loader)) {
                return fail("не вдалось завантажити нативи Steamworks");
            }

            // Причина одна на два різні випадки — клієнт не запущений або він
            // не знає нашого appid — і розрізнити їх звідси НЕ можна:
            // isSteamRunning() до вдалого init() однаково повертає false
            // (перевірено: при живому клієнті, але без steam_appid.txt воно
            // казало «Steam не запущено»). Краще одне чесне речення, ніж
            // впевнена неправда.
            if (!SteamAPI.init()) {
                return fail("Steam не запущено або не знає AppID гри");
            }

            running = true;
            Gdx.app.log("STEAM", "Steamworks піднято, AppID="
                + System.getProperty(APP_ID_PROPERTY, "<від клієнта>"));
            return true;

        } catch (SteamException e) {
            return fail("порушено порядок виклику Steamworks: " + e.getMessage());
        } catch (Throwable t) {
            // UnsatisfiedLinkError і подібне: бібліотеки може не бути під цю
            // платформу зовсім. Гра від цього падати не повинна.
            return fail("Steamworks недоступний: " + t);
        }
    }

    public static boolean isRunning() { return running; }

    /** Причина, чому не піднявся; {@code null}, якщо все гаразд або ще не пробували. */
    public static String failure() { return failure; }

    /**
     * Такт колбеків. Steamworks радить не рідше 15 разів на секунду — у нас це
     * кожен кадр із {@code LostBatalion.render()}.
     */
    public static void runCallbacks() {
        if (running && SteamAPI.isSteamRunning()) SteamAPI.runCallbacks();
    }

    /** Викликається один раз на виході з гри. */
    public static synchronized void shutdown() {
        if (!running) return;
        running = false;
        SteamAPI.shutdown();
    }

    // ── Внутрішнє ─────────────────────────────────────────────────────────

    /**
     * Покласти {@code steam_appid.txt} у робочу теку, якщо задано властивість.
     *
     * <p>Файл САМЕ в робочій теці процесу, бо звідти його читає нативний
     * Steamworks. Через Gradle це {@code main/assets/} — і саме тому файл не
     * лежить у репозиторії поруч з асетами: {@code generateAssetList} вніс би
     * його в {@code assets.txt} і запакував у jar кожної збірки.
     */
    private static void writeAppIdFile() {
        String appId = System.getProperty(APP_ID_PROPERTY);
        if (appId == null || appId.isEmpty()) return;

        try {
            Path file = Paths.get("steam_appid.txt").toAbsolutePath();
            if (Files.exists(file)) return;
            Files.write(file, appId.trim().getBytes(StandardCharsets.UTF_8));
            Gdx.app.log("STEAM", "створено " + file + " з AppID " + appId);
        } catch (Exception e) {
            // Не привід зупинятись: файл міг уже лежати поруч із грою, а тека
            // бути тільки для читання. init() однаково скаже правду.
            Gdx.app.log("STEAM", "не вдалось записати steam_appid.txt: " + e);
        }
    }

    private static boolean fail(String reason) {
        failure = reason;
        running = false;
        if (Gdx.app != null) Gdx.app.log("STEAM", "не піднявся: " + reason);
        return false;
    }
}
