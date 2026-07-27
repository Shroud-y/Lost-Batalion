package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.utils.Disposable;

/**
 * Позначка наказу на рух — світний хрестик у точці призначення.
 *
 * <p>Сама фігура — спрайт {@code ui/movemarker.png}; код лише масштабує його,
 * гасить і підсвічує. Світіння навколо дає {@link BloomEffect}.
 *
 * <p><b>Адаптивний блендинг.</b> Спрайт кладеться двома проходами, вага між
 * якими їде за фазою анімації: «тіло» (звичайне перекриття) і «світло»
 * (додавання). Коефіцієнт {@code additive} 0 означає чисте перекриття, 1 —
 * чисте світло, проміжні значення дають суміш. На спалаху позначка більше
 * світиться, на затуханні лишається щільним штрихом, щоб не губитись на
 * світлому ґрунті.
 *
 * <p>PNG зберігається зі звичайною (не помноженою) альфою, а буфер
 * {@link BloomEffect} працює в premultiplied: тому обидва проходи йдуть із
 * {@code glBlendFuncSeparate}, де альфа рахується не так, як колір. Інакше
 * розмиття дало б темну облямівку по краях хрестика.
 */
public class MoveMarker implements Disposable {

    public enum MarkerType { MOVE, ATTACK }

    private static final String TEXTURE_PATH = "ui/movemarker.png";
    /** Розмір запасної текстури, якщо файлу немає. */
    private static final int FALLBACK_SIZE = 32;

    private static final float FADE_IN_TIME  = 0.08f;
    private static final float HOLD_TIME     = 0.25f;
    private static final float FADE_OUT_TIME = 0.28f;
    private static final float TOTAL_TIME    = FADE_IN_TIME + HOLD_TIME + FADE_OUT_TIME;

    /** Сторона спрайта на карті, світові пікселі. */
    private static final float SIZE_MAX = 15f;
    private static final float SIZE_MIN = 5f;
    /** Наскільки хрестик «видихає» назовні, поки гасне. */
    private static final float SIZE_OVERSHOOT = 4f;

    private static final float MAX_ALPHA = 0.9f;
    /** Максимальна частка «світла» в суміші — на спалаху. */
    private static final float ADDITIVE_MAX = 0.45f;

    /** Підсвітка: тепло-білий, як спалах. */
    private static final float R = 1.0f, G = 0.96f, B = 0.82f;

    private final Texture texture;

    private float x, y;
    private float timer = TOTAL_TIME;
    private boolean active = false;
    private MarkerType type = MarkerType.MOVE;

    public MoveMarker() {
        texture = loadTexture();
        texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    }

    /** Файл може не доїхати в збірку — тоді малюємо хрестик у пікселях самі. */
    private static Texture loadTexture() {
        if (Gdx.files.internal(TEXTURE_PATH).exists())
            return new Texture(Gdx.files.internal(TEXTURE_PATH));

        Gdx.app.error("MARKER", "немає " + TEXTURE_PATH + ", беру запасний хрестик");
        Pixmap pm = new Pixmap(FALLBACK_SIZE, FALLBACK_SIZE, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        pm.setColor(0f, 0f, 0f, 0f);
        pm.fill();
        pm.setColor(1f, 1f, 1f, 1f);
        int pad = 5, thick = 2;
        for (int i = -thick; i <= thick; i++) {
            pm.drawLine(pad + i, pad, FALLBACK_SIZE - 1 - pad + i, FALLBACK_SIZE - 1 - pad);
            pm.drawLine(pad + i, FALLBACK_SIZE - 1 - pad, FALLBACK_SIZE - 1 - pad + i, pad);
        }
        Texture t = new Texture(pm);
        pm.dispose();
        return t;
    }

    public void show(float worldX, float worldY) {
        show(worldX, worldY, MarkerType.MOVE);
    }

    public void show(float worldX, float worldY, MarkerType markerType) {
        this.x = worldX;
        this.y = worldY;
        this.timer  = 0f;
        this.active = true;
        this.type = markerType;
    }

    public void update(float delta) {
        if (!active) return;
        timer += delta;
        if (timer >= TOTAL_TIME) active = false;
    }

    public boolean isActive() { return active; }

    /**
     * @param batch батч із проєкцією камери; блендинг після виклику
     *              повертається до типового
     */
    public void draw(SpriteBatch batch) {

        if (!active || type == MarkerType.ATTACK) return;

        float alpha, size;
        // «Спалах» — наскільки зараз позначка поводиться як світло, а не як
        // намальована фігура. Максимум у момент появи.
        float flash;

        if (timer < FADE_IN_TIME) {
            float t = timer / FADE_IN_TIME;
            alpha = t * MAX_ALPHA;
            size  = SIZE_MIN + (SIZE_MAX - SIZE_MIN) * Interpolation.pow2Out.apply(t);
            flash = 1f;
        } else if (timer < FADE_IN_TIME + HOLD_TIME) {
            float t = (timer - FADE_IN_TIME) / HOLD_TIME;
            alpha = MAX_ALPHA;
            size  = SIZE_MAX;
            flash = 1f - 0.45f * t;
        } else {
            float t = (timer - FADE_IN_TIME - HOLD_TIME) / FADE_OUT_TIME;
            alpha = (1f - Interpolation.pow2In.apply(t)) * MAX_ALPHA;
            size  = SIZE_MAX + SIZE_OVERSHOOT * t;
            flash = 0.55f + 0.45f * t;   // гасне — знову йде в чисте світло
        }
        if (alpha <= 0f) return;

        float k  = ADDITIVE_MAX * flash;
        float hw = size / 2f;

        Gdx.gl.glEnable(GL20.GL_BLEND);

        // Тіло: колір змішується як завжди, альфа — як у premultiplied-буфері.
        batch.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA,
                                       GL20.GL_ONE,       GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.begin();
        batch.setColor(R, G, B, alpha * (1f - k));
        batch.draw(texture, x - hw, y - hw, size, size);
        batch.end();

        // Світло: додається до кадру й нічого не затуляє, тому альфа буфера
        // лишається недоторканою.
        if (k > 0f) {
            batch.setBlendFunctionSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE,
                                           GL20.GL_ZERO,      GL20.GL_ONE);
            batch.begin();
            batch.setColor(R, G, B, alpha * k);
            batch.draw(texture, x - hw, y - hw, size, size);
            batch.end();
        }

        batch.setColor(1f, 1f, 1f, 1f);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override
    public void dispose() {
        if (texture != null) texture.dispose();
    }
}
