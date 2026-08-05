package io.jababa.lost_batalion.debug;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Scaling;

import java.io.File;

/**
 * ЖАРТ. Показує найсвіжішу картинку з теки «Завантаження» на команду
 * {@code aivsai}.
 *
 * <p><b>Тимчасове. Забрати разом із викликом у {@code GameScreen.DevHost}
 * і полем {@code gag} — більше нічого від нього не залежить.</b> Клас навмисно
 * не має жодних зв'язків із рештою гри: ані стану, ані налаштувань, ані запису
 * будь-куди. Видаляється одним файлом.
 *
 * <p>Ліземо в теку користувача, тож усе робиться захищено: немає теки, немає
 * картинок, формат не читається libGDX (наприклад {@code .webp}) — мовчки
 * нічого не показуємо й кажемо про це в консоль. Падати грі через прикол не
 * можна.
 */
public final class DownloadsGag {

    /** Скільки картинка висить на екрані, секунди. */
    private static final float HOLD = 3.5f;
    private static final float FADE = 0.4f;

    /** Яку частку висоти екрана займає. */
    private static final float SCREEN_FRACTION = 0.45f;

    /** libGDX читає лише це. {@code .webp} і {@code .gif} — ні. */
    private static final String[] SUPPORTED = { ".png", ".jpg", ".jpeg", ".bmp" };

    private Texture texture;
    private Image   actor;

    /** Чи картинка ще на екрані — {@code GameScreen} питає, щоб малювати сцену. */
    public boolean isActive() { return actor != null && actor.hasParent(); }

    /**
     * @return опис того, що сталося — рядок для консолі
     */
    public String show(Stage stage) {
        FileHandle file = newestImage();
        if (file == null) return "у Завантаженнях немає картинки, яку я вмію прочитати";

        dispose();   // попередню прибрати, інакше текстури накопичаться

        try {
            texture = new Texture(file);
        } catch (Exception e) {
            texture = null;
            return "не зміг прочитати " + file.name() + " (" + e.getClass().getSimpleName() + ")";
        }
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);

        actor = new Image(new TextureRegionDrawable(texture));
        actor.setScaling(Scaling.fit);

        float h = stage.getViewport().getWorldHeight() * SCREEN_FRACTION;
        float w = h * texture.getWidth() / (float) texture.getHeight();
        actor.setSize(w, h);
        actor.setPosition((stage.getViewport().getWorldWidth() - w) / 2f,
                          (stage.getViewport().getWorldHeight() - h) / 2f);

        actor.getColor().a = 0f;
        actor.addAction(Actions.sequence(
            Actions.fadeIn(FADE, Interpolation.smooth),
            Actions.delay(HOLD),
            Actions.fadeOut(FADE, Interpolation.smooth),
            Actions.removeActor()));

        stage.addActor(actor);
        return "а ось і " + file.name();
    }

    /**
     * Найсвіжіша читабельна картинка в Завантаженнях.
     *
     * <p>Теку шукаємо в кількох місцях: на Windows її часто переносить OneDrive,
     * і жорсткий {@code user.home/Downloads} тоді просто не існує.
     */
    private static FileHandle newestImage() {
        String home = System.getProperty("user.home");
        if (home == null) return null;

        String[] candidates = { home + "/Downloads", home + "/OneDrive/Downloads" };

        File best = null;
        for (String path : candidates) {
            File dir = new File(path);
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;

            for (File f : files) {
                if (!f.isFile() || !supported(f.getName())) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
        }
        return best == null ? null : Gdx.files.absolute(best.getAbsolutePath());
    }

    private static boolean supported(String name) {
        String lower = name.toLowerCase();
        for (String ext : SUPPORTED) if (lower.endsWith(ext)) return true;
        return false;
    }

    public void dispose() {
        if (actor != null) { actor.remove(); actor = null; }
        if (texture != null) { texture.dispose(); texture = null; }
    }
}
