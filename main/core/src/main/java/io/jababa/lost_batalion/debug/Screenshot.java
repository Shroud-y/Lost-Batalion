package io.jababa.lost_batalion.debug;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.ScreenUtils;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Знімок екрана у PNG.
 *
 * <p>Потрібен не гравцеві, а розробці: усе, що стосується вигляду, інакше
 * перевіряється тільки очима людини перед монітором. Кадр у файлі можна
 * прикласти до задачі, порівняти «до і після» або показати тому, хто цей
 * монітор не бачить.
 *
 * <p>Знімається САМЕ кадровий буфер, а не сцена: у ньому вже все — HUD,
 * пост-обробка bloom, туман війни. Малювати сцену вдруге «начисто» означало б
 * знімати не те, що бачить гравець.
 */
public final class Screenshot {

    private Screenshot() {}

    /**
     * Куди складати, якщо не сказано інакше — у ДОМАШНІЙ теці, не в проєкті.
     *
     * <p>Раніше тут був відносний шлях, і це виявилось пасткою: робоча тека при
     * запуску через Gradle — це {@code main/assets/}, тобто знімки падали прямо
     * в асети. Звідти їх забирає {@code generateAssetList} і пакує в jar, а
     * заразом вони лізуть у git. Домашня тека від робочої не залежить узагалі.
     */
    public static final String DEFAULT_DIR = "LostBatalion/screenshots";

    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS");

    /** Чи була попередня спроба невдалою — щоб не писати той самий рядок щокадру. */
    private static boolean bufferWasUnavailable;

    /** Знімок із типовою назвою за часом. */
    public static FileHandle capture() {
        return capture(DEFAULT_DIR, null);
    }

    /**
     * Знімок кадрового буфера.
     *
     * @param dir  тека; {@code null} → {@link #DEFAULT_DIR}
     * @param name ім'я файлу без розширення; {@code null} → мітка часу
     * @return файл, у який записано, або {@code null}, якщо не вийшло
     */
    public static FileHandle capture(String dir, String name) {
        int w = Gdx.graphics.getBackBufferWidth();
        int h = Gdx.graphics.getBackBufferHeight();
        // Згорнуте вікно дає буфер 0×0. Мовчазний null тут коштував двох
        // прогонів, у яких «усе пройшло», а файлів не було, — тому причина
        // йде в лог, а не здогадується.
        //
        // Але рівно ОДИН раз на смугу невдач: автознімки перепитують щокадру,
        // поки чекають на вікно, і безумовний лог видавав тисячі однакових
        // рядків за кілька секунд, у яких тонуло все інше.
        if (w <= 0 || h <= 0) {
            if (!bufferWasUnavailable) {
                bufferWasUnavailable = true;
                Gdx.app.log("SCREENSHOT", "кадровий буфер " + w + "×" + h
                          + " — вікно згорнуте або ще не готове, чекаю");
            }
            return null;
        }
        bufferWasUnavailable = false;

        Pixmap pixmap = null;
        try {
            // flipY=true: OpenGL віддає рядки знизу вгору, PNG чекає навпаки.
            byte[] pixels = ScreenUtils.getFrameBufferPixels(0, 0, w, h, true);

            // Альфа з буфера приходить нульовою, і PNG вийшов би повністю
            // прозорим — картинка є, а видно порожнечу. Тому канал забивається
            // непрозорим вручну.
            for (int i = 3; i < pixels.length; i += 4) pixels[i] = (byte) 0xFF;

            pixmap = new Pixmap(w, h, Pixmap.Format.RGBA8888);
            BufferUtils.copy(pixels, 0, pixmap.getPixels(), pixels.length);

            String folder = dir == null || dir.isEmpty() ? DEFAULT_DIR : dir;
            String file   = (name == null || name.isEmpty())
                          ? "lb-" + STAMP.format(new Date())
                          : name;

            FileHandle out = resolve(folder).child(file + ".png");
            out.parent().mkdirs();
            PixmapIO.writePNG(out, pixmap);

            // Повний шлях у лог: типова тека — робоча тека процесу, а вона при
            // запуску через Gradle не там, де гравець став би її шукати.
            Gdx.app.log("SCREENSHOT", out.file().getAbsolutePath());
            return out;
        } catch (Exception e) {
            // Знімок — допоміжна дія. Впасти через неї було б абсурдом.
            Gdx.app.error("SCREENSHOT", "не вдалося зняти кадр: " + e, e);
            return null;
        } finally {
            if (pixmap != null) pixmap.dispose();
        }
    }

    /**
     * Абсолютний шлях лишається абсолютним, відносний — рахується від ДОМАШНЬОЇ
     * теки, а не від робочої.
     *
     * <p>{@code Gdx.files.local} тут ужити не можна: він відлічує від робочої
     * теки процесу, а вона при запуску через Gradle — {@code main/assets/}.
     * {@code external} прив'язаний до домашньої теки й від способу запуску не
     * залежить. Для абсолютного шляху обидва дали б нісенітницю штибу
     * {@code ~/C:/tmp}, тому він обробляється окремо.
     */
    private static FileHandle resolve(String dir) {
        java.io.File f = new java.io.File(dir);
        return f.isAbsolute() ? Gdx.files.absolute(dir) : Gdx.files.external(dir);
    }
}
