package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.economy.PendingSpawn;
import io.jababa.lost_batalion.economy.SpawnQueue;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.units.UnitType;

/**
 * Привиди майбутніх військ: під курсором і на місці висадки.
 *
 * <p>Дві різні речі одним класом, бо картинка в них одна й та сама. Привид під
 * курсором — це вибір, який ще не зроблено, тож він у координатах ЕКРАНА і
 * ходить за мишею. Привид на карті — це вже замовлення в симуляції, тож він у
 * світових координатах і стоїть там, куди клікнули.
 *
 * <p>Чужі замовлення не малюються взагалі: поки війська немає на полі, це
 * задум, а не розвідданi.
 */
public class SpawnGhostRenderer {

    /** Непрозорість привида під курсором. */
    private static final float CURSOR_ALPHA = 0.55f;

    /**
     * Непрозорість привида на карті: на початку очікування і в кінці.
     *
     * <p>Він густішає, поки йде відлік. Це єдина підказка, скільки лишилось
     * часу передумати, і вона не потребує ані цифр, ані ще однієї смужки.
     */
    private static final float PLACED_ALPHA_START = 0.35f;
    private static final float PLACED_ALPHA_END   = 0.70f;

    /**
     * Наскільки привид зміщений ЛІВОРУЧ від вістря курсора, у пікселях екрана.
     * Під самим вістрям він затуляв би те місце, куди гравець цілиться.
     */
    private static final float CURSOR_OFFSET = 6f;

    /**
     * Розмір силуета під курсором, частками розміру юніта. Більший за одиницю
     * (інакше піхота під курсором — крапка), але не вдвічі: партія з кількох
     * штук тоді закриває саме те місце, куди гравець цілиться.
     */
    private static final float CURSOR_SCALE = 1.3f;

    /**
     * Крок між силуетами партії, частками розміру. Більший за одиницю — між
     * ними лишається просвіт, і видно, що їх кілька, а не один розмазаний.
     */
    private static final float CURSOR_STACK = 1.2f;

    /**
     * Скільки силуетів узагалі малювати. Точну кількість показують квадратики
     * в меню; курсор лише каже «беремо кілька».
     */
    private static final int CURSOR_MAX_GHOSTS = 5;

    private final ObjectMap<String, Texture> cache = new ObjectMap<>();

    /** Привид під курсором. Координати екранні, Y уже перевернутий викликачем. */
    public void drawCursor(SpriteBatch uiBatch, UnitType type, Team team,
                           float screenX, float screenY) {
        drawCursor(uiBatch, type, 1, team, screenX, screenY);
    }

    /**
     * Те саме для партії з кількох штук: силуети йдуть рядком ЛІВОРУЧ від
     * курсора, з нахлистом, щоб довга черга не перекрила пів екрана.
     *
     * @param count скільки замовлено; ≤0 — не малюється нічого
     */
    public void drawCursor(SpriteBatch uiBatch, UnitType type, int count, Team team,
                           float screenX, float screenY) {
        if (type == null || count <= 0) return;
        // Ширина й висота окремо: спрайт легкої піхоти не квадратний, і
        // квадратний привид показував би розтягнуту фігуру.
        float w = type.sizePx()   * CURSOR_SCALE;
        float h = type.heightPx() * CURSOR_SCALE;
        Texture tex = texture(type, team);

        int shown = Math.min(count, CURSOR_MAX_GHOSTS);
        // Крок по ШИРИНІ — силуети йдуть рядком, тобто нахлист горизонтальний.
        float step = w * CURSOR_STACK;

        uiBatch.setColor(1f, 1f, 1f, CURSOR_ALPHA);
        for (int i = 0; i < shown; i++) {
            uiBatch.draw(tex, screenX - CURSOR_OFFSET - w - i * step,
                         screenY - h / 2f, w, h);
        }
        uiBatch.setColor(1f, 1f, 1f, 1f);
    }

    /**
     * Привиди ВЛАСНИХ замовлень на карті. Координати світові.
     *
     * <p>Фільтр по номеру гравця, а не по стороні: замовлення союзника — його
     * справа, і показувати чуже місце висадки означало б захаращувати поле
     * тим, на що гравець усе одно не впливає.
     */
    public void drawPending(SpriteBatch batch, SpawnQueue queue,
                            int viewerPlayerId, Team viewer) {
        if (queue == null) return;
        Array<PendingSpawn> all = queue.getPending();

        for (int i = 0; i < all.size; i++) {
            PendingSpawn s = all.get(i);
            if (s.playerId != viewerPlayerId) continue;

            float w = s.type.sizePx();
            float h = s.type.heightPx();
            float x = Fixed.toFloat(s.x) - w / 2f;
            float y = Fixed.toFloat(s.y) - h / 2f;

            float alpha = PLACED_ALPHA_START
                        + (PLACED_ALPHA_END - PLACED_ALPHA_START) * s.elapsed();

            batch.setColor(1f, 1f, 1f, alpha);
            batch.draw(texture(s.type, viewer), x, y, w, h);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    private Texture texture(UnitType type, Team team) {
        String path = type.texturePath(team);
        Texture cached = cache.get(path);
        if (cached != null) return cached;

        Texture tex = Gdx.files.internal(path).exists()
            ? new Texture(Gdx.files.internal(path))
            : fallback(team);
        cache.put(path, tex);
        return tex;
    }

    /** Та сама заглушка, що й у {@link UnitRenderer}: кольоровий квадрат сторони. */
    private Texture fallback(Team team) {
        Pixmap pm = new Pixmap(32, 32, Pixmap.Format.RGBA8888);
        if (team == Team.PLAYER) pm.setColor(0.2f, 0.4f, 0.9f, 1f);
        else                     pm.setColor(0.85f, 0.2f, 0.2f, 1f);
        pm.fill();
        Texture tex = new Texture(pm);
        pm.dispose();
        return tex;
    }

    public void dispose() {
        for (Texture tex : cache.values()) tex.dispose();
        cache.clear();
    }
}
