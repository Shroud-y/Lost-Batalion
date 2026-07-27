package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;

public class ExplosionAnimation {

    private final Animation<TextureRegion> animation;
    private final Array<Texture>           ownedTextures = new Array<>(); // для dispose()

    private ExplosionAnimation(Animation<TextureRegion> anim) {
        this.animation = anim;
    }

    // ── Фабричні методи ──────────────────────────────────────────────────

    /**
     * Завантажити зі spritesheet.
     *
     * @param path     шлях до PNG (assets/effects/explosion_sheet.png)
     * @param cols     кількість колонок кадрів
     * @param rows     кількість рядків кадрів
     * @param fps      кадрів на секунду
     * @param pixelArt true — {@code Nearest}-фільтр (дрібний піксельний лист),
     *                 false — {@code Linear} (великі згладжені кадри)
     */
    public static ExplosionAnimation fromSheet(String path, int cols, int rows, float fps,
                                               boolean pixelArt) {
        if (!Gdx.files.internal(path).exists()) return null;
        if (cols < 1 || rows < 1) return null;

        Texture sheet = new Texture(Gdx.files.internal(path));
        Texture.TextureFilter filter = pixelArt
            ? Texture.TextureFilter.Nearest
            : Texture.TextureFilter.Linear;
        sheet.setFilter(filter, filter);

        int frameW = sheet.getWidth()  / cols;
        int frameH = sheet.getHeight() / rows;

        // Сітка, що не ділить лист націло, — це майже завжди застарілі
        // константи після заміни спрайта. Мовчки нарізати з такої сітки не
        // можна: попередній лист був 8x10, і на листі 112x16 це давало 80
        // кадрів завтовшки в один піксель — вибух малювався смужкою, і
        // причину було не видно ніде, крім самої картинки.
        if (frameW < 1 || frameH < 1
            || frameW * cols != sheet.getWidth()
            || frameH * rows != sheet.getHeight()) {
            Gdx.app.error("EFFECTS", "Лист " + path + " має розмір "
                + sheet.getWidth() + "x" + sheet.getHeight()
                + ", а сітка задана " + cols + "x" + rows
                + " — розміри не збігаються. Анімацію не завантажено.");
            sheet.dispose();
            return null;
        }

        TextureRegion[][] grid = TextureRegion.split(sheet, frameW, frameH);
        Array<TextureRegion> frames = new Array<>();
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                frames.add(grid[r][c]);

        Animation<TextureRegion> anim = new Animation<>(1f / fps, frames, Animation.PlayMode.NORMAL);

        ExplosionAnimation ea = new ExplosionAnimation(anim);
        ea.ownedTextures.add(sheet);
        return ea;
    }

    /**
     * Завантажити з окремих файлів: prefix + "0" + suffix, prefix + "1" + suffix, ...
     * @param prefix  наприклад "effects/explosion_"
     * @param count   кількість кадрів
     * @param suffix  наприклад ".png"
     * @param fps     кадрів на секунду
     */
    public static ExplosionAnimation fromFrames(String prefix, int count, String suffix, float fps) {
        Array<TextureRegion> frames = new Array<>();
        Array<Texture> tempTextures = new Array<>(); // Collect textures here first

        for (int i = 0; i < count; i++) {
            String p = prefix + i + suffix;
            if (!Gdx.files.internal(p).exists()) continue;

            Texture t = new Texture(Gdx.files.internal(p));
            t.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            tempTextures.add(t);
            frames.add(new TextureRegion(t));
        }

        if (frames.size == 0) return null;

        Animation<TextureRegion> anim = new Animation<>(1f / fps, frames, Animation.PlayMode.NORMAL);

        // Create the object normally without anonymous class hacks
        ExplosionAnimation ea = new ExplosionAnimation(anim);
        ea.ownedTextures.addAll(tempTextures);

        return ea;
    }

    // ── Програвання ──────────────────────────────────────────────────────

    /**
     * Намалювати поточний кадр у центрі (cx, cy) з розміром size x size.
     * @param stateTime  час від початку анімації (секунди)
     * @param alpha      прозорість 0..1
     */
    public void draw(SpriteBatch batch, float cx, float cy, float size, float stateTime, float alpha) {
        if (animation == null) return;
        TextureRegion frame = animation.getKeyFrame(stateTime, false);
        if (frame == null) return;

        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(frame,
            cx - size / 2f, cy - size / 2f,
            size, size);
        batch.setColor(1f, 1f, 1f, 1f);
    }

    /** Чи завершилась анімація (останній кадр пройдено). */
    public boolean isFinished(float stateTime) {
        if (animation == null) return true;
        return animation.isAnimationFinished(stateTime);
    }

    public float getDuration() {
        if (animation == null) return 0f;
        return animation.getAnimationDuration();
    }

    public void dispose() {
        for (Texture t : ownedTextures) t.dispose();
        ownedTextures.clear();
    }
}
