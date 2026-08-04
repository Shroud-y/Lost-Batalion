package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.terrain.TerrainType;

/**
 * Оверлей топографії: яруси висот кольором по всій карті.
 *
 * <h3>Навіщо</h3>
 * Рельєф — заявлений стовп дизайну: висота дає до ±30% захисту
 * ({@code TerrainCombatModifier}) і міняє дальність огляду в 3.25 раза
 * ({@code VisibilitySystem.SIGHT_MOD_*}). Але на самій карті яруси майже не
 * розрізняються — вона вся один відтінок зеленого з ледь помітними плямами.
 * Гравець не може приймати рішення на основі даних, яких не бачить, тож уся
 * ця математика працювала внаслі́пу.
 *
 * <p>{@code TerrainIndicatorRenderer} відповідає на інше питання: він показує
 * ярус під ВИДІЛЕНИМ юнітом, тобто «де я стою». Це — «куди мені йти».
 *
 * <h3>Як це малюється</h3>
 * Один раз будується текстура розміром з карту, де колір пікселя = його ярус,
 * і далі вона просто малюється поверх карти з прозорістю. Альтернатива —
 * тисячі прямокутників щокадру — коштувала б на два порядки більше заради
 * картинки, яка ніколи не змінюється: маска місцевості за матч не міняється.
 *
 * <p>Текстура будується ЛІНИВО, при першому вмиканні: обхід 1440×1440 пікселів
 * має сенс тільки якщо гравець оверлеєм користується, а платити за нього в
 * кожному матчі — ні.
 *
 * <p>Чистий рендер: на симуляцію не впливає ніяк.
 */
public class TopographyOverlay {

    /**
     * Прозорість заливки.
     *
     * <p>Оверлей мусить лишити карту читабельною: гравець дивиться крізь нього
     * на власні війська й ліси, а не замість них. На повній непрозорості це
     * була б інша карта, а не підказка поверх цієї.
     */
    private static final float ALPHA = 0.45f;

    // Гіпсометрична шкала — та сама логіка, що на паперових картах: низини
    // зелені, височини руді. Це не декоративний вибір, а єдина розкладка,
    // яку гравець уже вміє читати, ніколи не бачивши цієї гри.
    private static final int C_LOWLANDS      = rgb(0x2E, 0x6B, 0x4F);
    private static final int C_PRE_LOWLANDS  = rgb(0x5A, 0x9E, 0x52);
    private static final int C_PLAINS        = rgb(0xC8, 0xCE, 0x7A);
    private static final int C_PRE_HIGHLANDS = rgb(0xD1, 0x99, 0x4E);
    private static final int C_HIGHLANDS     = rgb(0xA8, 0x5A, 0x2E);
    private static final int C_RIVER         = rgb(0x3A, 0x72, 0xB0);
    /** Поза ярусами (немає маски) — не фарбуємо взагалі. */
    private static final int C_NONE          = 0x00000000;

    private final TerrainQuery terrain;
    private final int mapW, mapH;

    private Texture texture;
    /** Чи вже пробували побудувати. Захищає від нової спроби щокадру при збої. */
    private boolean built;

    private boolean visible;

    public TopographyOverlay(TerrainQuery terrain, float mapWidth, float mapHeight) {
        this.terrain = terrain;
        this.mapW    = (int) mapWidth;
        this.mapH    = (int) mapHeight;
    }

    public void toggle()          { visible = !visible; }
    public boolean isVisible()    { return visible; }

    /** Малюється ПІД юнітами: це підказка про землю, а не про війська. */
    public void draw(SpriteBatch batch) {
        if (!visible || terrain == null) return;
        if (!built) build();
        if (texture == null) return;

        batch.setColor(1f, 1f, 1f, ALPHA);
        // Малюється так само просто, як сама карта — БЕЗ перевертання.
        // Перевертень уже застосований при побудові ({@link #build}): рядок 0
        // пікмапи заповнено найбільшим світовим Y, тобто верхом карти. Ще один
        // тут (від'ємна висота) перевернув би зображення вдруге, і оверлей ліг
        // би дзеркально до місцевості, яку описує.
        batch.draw(texture, 0, 0, mapW, mapH);
        batch.setColor(1f, 1f, 1f, 1f);
    }

    private void build() {
        built = true;
        if (mapW <= 0 || mapH <= 0) return;

        long started = System.currentTimeMillis();
        Pixmap pm = new Pixmap(mapW, mapH, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);

        for (int py = 0; py < mapH; py++) {
            // Пікмапа рахує Y вниз, світ — угору. Перетворення робиться ТУТ, а
            // не при малюванні, щоб запит ішов у ті самі світові координати,
            // якими користується симуляція.
            long worldY = Fixed.fromInt(mapH - 1 - py);
            for (int px = 0; px < mapW; px++) {
                pm.drawPixel(px, py, colorOf(terrain.elevationF(Fixed.fromInt(px), worldY)));
            }
        }

        texture = new Texture(pm);
        // Nearest: межі ярусів — це східці в масці, і згладжувати їх означало б
        // домалювати проміжні кольори, яким у грі не відповідає жоден ярус.
        texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();

        Gdx.app.log("TOPO", "оверлей топографії побудовано " + mapW + "x" + mapH
            + " за " + (System.currentTimeMillis() - started) + " мс");
    }

    private static int colorOf(TerrainType t) {
        switch (t) {
            case LOWLANDS:      return C_LOWLANDS;
            case PRE_LOWLANDS:  return C_PRE_LOWLANDS;
            case PLAINS:
            case PLAINS_ALT:    return C_PLAINS;
            case PRE_HIGHLANDS: return C_PRE_HIGHLANDS;
            case HIGHLANDS:     return C_HIGHLANDS;
            case RIVER:         return C_RIVER;
            default:            return C_NONE;
        }
    }

    /** RGBA8888 із непрозорою альфою; прозорість дає {@link #ALPHA} при малюванні. */
    private static int rgb(int r, int g, int b) {
        return (r << 24) | (g << 16) | (b << 8) | 0xFF;
    }

    public void dispose() {
        if (texture != null) texture.dispose();
    }
}
