package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.IntArray;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.units.Unit;

/**
 * Значки модифікаторів місцевості під юнітом.
 *
 * <p>Малюються праворуч-знизу від ВИДІЛЕНОГО юніта і складаються стовпчиком
 * угору. Наразі значок рівно один — ярус висоти, — але збірка йде через список
 * індексів, бо далі сюди підуть ліс і річка, і тоді зміниться лише
 * {@link #collect(Unit, TerrainQuery)}, а не розкладка.
 *
 * <p>Чистий рендер: жодного впливу на симуляцію. Місцевість питається через
 * {@link TerrainQuery#elevationF} — fixed-версію, ту саму, якою користується
 * симуляція, щоб значок не міг показати не той ярус, у якому юніт стоїть
 * насправді.
 */
public class TerrainIndicatorRenderer {

    private static final String ATLAS_PATH = "ui/terrain_modifiers.png";

    /** Смуга 112×16 — сім клітин 16×16 підряд. */
    private static final int CELL = 16;
    private static final int CELLS = 7;

    /**
     * Прозорий бортик навколо кожної клітини у ЗІБРАНОМУ атласі.
     *
     * <p>У вихідному PNG клітини стоять впритул і малюнок упирається в самі краї
     * (у всіх семи зайняті стовпці 0..15). Значок малюється меншим за 16 пікселів,
     * тобто GPU його зменшує, а зменшення з Linear бере сусідні тексели — без
     * бортика шеврон отримав би кольорову смужку від сусідньої клітини.
     */
    private static final int PAD  = 2;
    private static final int SLOT = CELL + PAD * 2;

    // Індекси клітин в атласі.
    private static final int ICON_UP_1    = 0; // зелений шеврон  — перед височини
    private static final int ICON_UP_2    = 1; // подвійний зелений — височини
    private static final int ICON_DOWN_1  = 2; // червоний шеврон — перед нізіни
    private static final int ICON_DOWN_2  = 3; // подвійний червоний — нізіни
    private static final int ICON_NEUTRAL = 4; // сіра риска — стандартна висота
    @SuppressWarnings("unused")
    private static final int ICON_MOVE    = 5; // бігун — поки не використовується
    @SuppressWarnings("unused")
    private static final int ICON_DEFENSE = 6; // щит  — поки не використовується

    /**
     * Розмір значка у СВІТОВИХ одиницях — сталий. Камера на нього не впливає:
     * значок наближається й віддаляється разом із юнітом, тому їхнє
     * співвідношення завжди однакове.
     */
    private static final float ICON_SIZE = 8f;
    /** Відступ від правого краю хітбокса. */
    private static final float GAP     = 1f;
    /** Проміжок між значками в стовпчику. */
    private static final float SPACING = 0.5f;
    /**
     * Наскільки стовпчик опущено нижче дна хітбокса. Рівно по дну значок
     * притискався до спрайта і читався як його частина.
     */
    private static final float DROP    = 3f;

    private final Texture atlas;
    private final TextureRegion[] icons = new TextureRegion[CELLS];

    /** Буфер значків одного юніта. Поле, а не локальна змінна — рендер щокадровий. */
    private final IntArray scratch = new IntArray(4);

    public TerrainIndicatorRenderer() {
        Pixmap source = Gdx.files.internal(ATLAS_PATH).exists()
            ? new Pixmap(Gdx.files.internal(ATLAS_PATH))
            : buildFallbackSource();

        Pixmap padded = pad(source);
        source.dispose();

        atlas = new Texture(padded);
        padded.dispose();

        // Зменшення — Linear: значок малюється меншим за вихідні 16 пікселів, і
        // Nearest просто викидав би кожен другий рядок, а штрихи шеврона завтовшки
        // 2px від цього розсипались. Збільшення — Nearest, бо це піксель-арт і
        // розмивати його при наближенні нема сенсу.
        atlas.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Nearest);

        for (int i = 0; i < CELLS; i++) {
            icons[i] = new TextureRegion(atlas, i * SLOT + PAD, PAD, CELL, CELL);
        }
    }

    /**
     * @param alpha   частка тіку (як у {@link UnitRenderer#drawSprites}) — значок
     *                має їхати разом зі спрайтом, а не відставати від нього
     * @param viewer  сторона, чиїми очима дивимось
     * @param terrain доступ до масок; null → нічого не малюється
     */
    public void draw(SpriteBatch batch, Iterable<Unit> units, float alpha,
                     Team viewer, TerrainQuery terrain) {
        if (terrain == null) return;

        final float size = ICON_SIZE;
        final float gap  = GAP;
        final float step = size + SPACING;

        for (Unit u : units) {
            if (!u.alive) continue;
            if (!u.selected) continue;            // тільки виділені
            if (!u.isVisibleTo(viewer)) continue; // туман

            collect(u, terrain);
            if (scratch.size == 0) continue;

            // Прив'язка до хітбокса, а не до спрайта: у артилерії картинка має
            // порожні кути, і значок відлетів би від фігури. Так само роблять
            // обводка виділення й HP-бар.
            float hitR = u.getHitRadiusPx();
            float x    = u.renderX(alpha) + hitR + gap;
            // Дно першого значка — трохи нижче дна хітбокса, далі стовпчик росте
            // вгору вздовж правого боку. batch.draw міряє y саме від низу
            // картинки, тому поправки на розмір значка тут немає.
            float baseY = u.renderY(alpha) - hitR - DROP;

            for (int i = 0; i < scratch.size; i++) {
                batch.draw(icons[scratch.get(i)], x, baseY + i * step, size, size);
            }
        }
    }

    // ── Приватні методи ───────────────────────────────────────────────────

    /** Заповнює {@link #scratch} значками юніта знизу вгору. */
    private void collect(Unit u, TerrainQuery terrain) {
        scratch.clear();

        int elevationIcon = elevationIcon(terrain.elevationF(u.x, u.y));
        if (elevationIcon >= 0) scratch.add(elevationIcon);

        // Сюди ж далі підуть ліс і річка, коли з'являться їхні спрайти.
    }

    /**
     * Ярус висоти → значок. Стандартна висота значка НЕ дає: іконка означає
     * відхилення від норми, і сіра риска на кожному юніті була б шумом.
     *
     * <p>RIVER сюди доходить із маски лісу (див. {@link TerrainQuery#elevationF})
     * і теж лишається без значка — річка не змінює ярус, вона впливає на рух.
     *
     * @return індекс клітини атласу або -1, якщо значка немає
     */
    private static int elevationIcon(TerrainType t) {
        switch (t) {
            case HIGHLANDS:     return ICON_UP_2;
            case PRE_HIGHLANDS: return ICON_UP_1;
            case PRE_LOWLANDS:  return ICON_DOWN_1;
            case LOWLANDS:      return ICON_DOWN_2;
            default:            return -1; // PLAINS / PLAINS_ALT / NONE / FOREST / RIVER
        }
    }

    /**
     * Розкладає суцільну смугу по клітинах із прозорим бортиком навколо кожної.
     * Розмір клітини не змінюється — між ними лише з'являється порожнеча, куди
     * може «затікати» фільтрація.
     */
    private static Pixmap pad(Pixmap source) {
        Pixmap out = new Pixmap(SLOT * CELLS, SLOT, Pixmap.Format.RGBA8888);
        // Без цього копія змішалася б із прозорим тлом і напівпрозорі пікселі
        // втратили б колір.
        out.setBlending(Pixmap.Blending.None);
        out.setColor(0f, 0f, 0f, 0f);
        out.fill();

        for (int i = 0; i < CELLS; i++) {
            out.drawPixmap(source, i * CELL, 0, CELL, CELL,
                           i * SLOT + PAD, PAD, CELL, CELL);
        }
        return out;
    }

    /**
     * Запасна смуга на випадок відсутнього файлу — щоб гра не падала.
     * Форма груба, але напрямок і кількість шевронів ті самі.
     */
    private static Pixmap buildFallbackSource() {
        Pixmap pm = new Pixmap(CELL * CELLS, CELL, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);
        pm.setColor(0f, 0f, 0f, 0f);
        pm.fill();

        drawChevrons(pm, ICON_UP_1,   1, true,  0f, 0.85f, 0.15f);
        drawChevrons(pm, ICON_UP_2,   2, true,  0f, 0.85f, 0.15f);
        drawChevrons(pm, ICON_DOWN_1, 1, false, 0.9f, 0.1f, 0.1f);
        drawChevrons(pm, ICON_DOWN_2, 2, false, 0.9f, 0.1f, 0.1f);

        pm.setColor(0.68f, 0.68f, 0.68f, 1f);
        pm.fillRectangle(ICON_NEUTRAL * CELL, CELL / 2 - 1, CELL, 2);

        // Бігун і щит поки не малюються — лишаються порожніми клітинами.

        return pm;
    }

    private static void drawChevrons(Pixmap pm, int cell, int count, boolean up,
                                     float r, float g, float b) {
        int x0 = cell * CELL;
        // Плече обмежене так, щоб перо 2×2 не вилізло за праву межу клітини:
        // при dx = CELL/2-1 останній піксель потрапив би в СУСІДНЮ клітину, і
        // її регіон отримав би чужий кольоровий стовпчик.
        final int arm = CELL / 2 - 2;
        pm.setColor(r, g, b, 1f);
        for (int n = 0; n < count; n++) {
            // Кілька шевронів розводяться по вертикалі, щоб їх було видно окремо.
            int yTip = up ? 3 + n * 5 : CELL - 5 - n * 5;
            for (int dx = 0; dx <= arm; dx++) {
                int dy = up ? dx : -dx;
                int y  = Math.max(0, Math.min(CELL - 2, yTip + dy));
                pm.fillRectangle(x0 + CELL / 2 - 1 - dx, y, 2, 2);
                pm.fillRectangle(x0 + CELL / 2 - 1 + dx, y, 2, 2);
            }
        }
    }

    public void dispose() {
        atlas.dispose();
    }
}
