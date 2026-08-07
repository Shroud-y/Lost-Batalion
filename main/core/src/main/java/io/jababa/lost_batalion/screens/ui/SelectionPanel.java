package io.jababa.lost_batalion.screens.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.units.Artillery;
import io.jababa.lost_batalion.units.Unit;

/**
 * Панель виділення — виїжджає знизу-ліворуч.
 *
 * Зміни:
 *  - Акцентний колір змінено на білий (замість жовтого).
 *  - Кнопка "Artillery Fire" з'являється лише коли серед виділених є Artillery.
 *  - Кнопка підсвічується білим коли активна.
 *
 * Asset шляхи:
 *   ui/panel_bg.png       — фон панелі
 *   ui/portrait_bg.png    — рамка портрету
 *   ui/cmd_formation.png  — кнопка формації
 *   ui/cmd_artillery.png  — кнопка артвогню
 */
public class SelectionPanel {

    /**
     * Панель зменшена в півтора раза проти первісних розмірів.
     *
     * <p>Множник винесено окремо, а не вбито в кожне число: так видно, що всі
     * розміри всередині панелі змінюються РАЗОМ. Портрет, кнопка й поля мусять
     * ділитись на те саме — інакше сітка портретів поїде відносно рамки.
     *
     * <p>{@code PANEL_LEFT} свідомо НЕ ділиться: це відступ від краю екрана, він
     * належить розкладці HUD, а не панелі, і має збігатися з відступом інших
     * кутових елементів.
     */
    private static final float K = 1f / 1.5f;

    /**
     * Бажана ширина панелі в ЛОГІЧНИХ одиницях. Фактична може бути меншою —
     * див. {@link #layout}: на вузькому вікні панель тиснеться, щоб не залізти
     * під мінікарту.
     */
    private static final float PANEL_W_MAX   = 400f * K;
    private static final float PORTRAIT_SIZE =  60f * K;
    private static final float PORTRAIT_PAD  =   6f * K;
    private static final float CMD_SIZE      =  52f * K;
    private static final float CMD_PAD       =   8f * K;
    private static final float INNER_PAD_X   =  10f * K;
    private static final float PORTRAITS_TOP =  10f * K;
    private static final float CMDS_H        = CMD_SIZE + CMD_PAD * 2f;
    private static final float PANEL_LEFT    =  10f;
    private static final float SLIDE_TIME    =  0.20f;
    /** Товщина рамки. Один піксель, як усюди в інтерфейсі (DESIGN §1). */
    private static final float EDGE          =   1f;
    /** Поле між рамкою комірки і картинкою в ній. */
    private static final float ICON_INSET    =   2f * K;
    /** Акцентна риска по верхньому краю панелі. */
    private static final float ACCENT_BAR    =   2f;
    /** Найвужче, до чого можна стиснути: два портрети в ряд плюс поля. */
    private static final float PANEL_W_MIN   = PORTRAIT_SIZE * 2f + PORTRAIT_PAD + INNER_PAD_X * 2f;
    /** Проміжок між панеллю і зайнятим мінікартою кутком. */
    private static final float PANEL_GAP     = 12f;

    private float   slideProgress = 0f;
    private boolean visible       = false;

    private Array<Unit> selectedUnits = new Array<>();
    private boolean     hasArtillery  = false;
    private float       currentPanelH = 0f;
    /** Фактична ширина панелі; задає {@link #layout}. */
    private float       currentPanelW = PANEL_W_MAX;

    /** Матриця логічних екранних координат — панель малює себе сама. */
    private final Matrix4 screenProj = new Matrix4();

    public interface CommandListener {
        void onCurvedFormation();
        /** Кнопка «звести гармати». Показується лише коли є що зводити. */
        void onMergeArtillery();
    }
    private CommandListener listener;

    private final Texture panelBg;
    private final Texture portraitBg;
    private final Texture cmdFormation;
    private final Texture cmdArtillery;
    /** Немає в ассетах — тоді кнопка малює власний знак (див. drawMergeGlyph). */
    private final Texture cmdMerge;

    private final Array<Texture> portraitTextures = new Array<>();
    private final Array<String>  portraitPaths    = new Array<>();
    private Texture fallbackTex = null;

    private boolean formationActive = false;
    private boolean artilleryActive = false;

    /**
     * Чи є що зводити в батарею — рахується разом зі зміною виділення.
     *
     * <p>Кнопка не просто гасне, а ЗНИКАЄ: об'єднання доступне рідко (треба дві
     * гармати в одному виділенні), і постійно згасла кнопка в кутку панелі
     * читалась би як щось поламане. Це той рідкий випадок, коли §7 DESIGN
     * («недоступний пункт гасне, а не зникає») не діє: там ідеться про пункт
     * СПИСКУ, чия відсутність зсуває сусідів, а тут кнопка крайня в ряду.
     */
    private boolean mergeAvailable = false;

    public SelectionPanel() {
        panelBg      = loadTex("ui/panel_bg.png");
        portraitBg   = loadTex("ui/portrait_bg.png");
        cmdFormation = loadTex("ui/cmd_formation.png");
        cmdArtillery = loadTex("ui/cmd_artillery.png");
        cmdMerge     = loadTex("ui/cmd_merge.png");
    }

    public void setListener(CommandListener l) { this.listener = l; }

    // ── Публічне API ─────────────────────────────────────────────────────

    public void update(float delta, Array<Unit> current) {
        boolean hasSelection = current.size > 0;

        if (hasSelection != visible || !sameSelection(current)) {
            selectedUnits = new Array<>(current);
            visible       = hasSelection;
            currentPanelH = calcPanelHeight(selectedUnits.size);

            hasArtillery = false;
            for (int i = 0; i < selectedUnits.size; i++) {
                if (selectedUnits.get(i) instanceof Artillery) { hasArtillery = true; break; }
            }
            mergeAvailable = calcMergeAvailable();
        }

        float target = visible ? 1f : 0f;
        float speed  = 1f / SLIDE_TIME;
        slideProgress = target > slideProgress
            ? Math.min(slideProgress + speed * delta, target)
            : Math.max(slideProgress - speed * delta, target);

        if (!visible) { formationActive = false; artilleryActive = false; }
    }

    public void setFormationActive(boolean v)  { formationActive = v; }
    public void setArtilleryActive(boolean v)  { artilleryActive = v; }

    /**
     * Перерахувати ширину панелі під розмір вікна.
     *
     * <p>Викликати ЩОКАДРУ до {@link #draw} і бажано з {@code resize}: перевірки
     * влучання ({@link #containsScreenPoint}, {@link #handleClick}) читають ту
     * саму ширину, і якщо вона відстане від намальованої, кнопка почне
     * спрацьовувати не там, де її видно.
     *
     * @param logicalW      ширина HUD у логічних одиницях
     * @param reservedRight скільки праворуч зайнято (мінікарта); панель туди не лізе
     */
    public void layout(float logicalW, float reservedRight) {
        float available = logicalW - PANEL_LEFT - reservedRight - PANEL_GAP;
        float w = Math.min(PANEL_W_MAX, available);
        // Нижче цієї межі панель перестає бути панеллю — сітка портретів
        // ламається раніше, ніж економиться місце. Хай краще торкнеться
        // мінікарти, ніж перетвориться на смужку.
        if (w < PANEL_W_MIN) w = PANEL_W_MIN;

        if (w != currentPanelW) {
            currentPanelW = w;
            // Від ширини залежить, скільки портретів у ряд, а від того — висота.
            currentPanelH = calcPanelHeight(selectedUnits.size);
        }
    }

    /**
     * @param logicalW ширина HUD у ЛОГІЧНИХ одиницях, не в пікселях кадру
     * @param logicalH висота HUD у логічних одиницях
     */
    public void draw(SpriteBatch batch, ShapeRenderer shapes, float logicalW, float logicalH) {
        if (slideProgress <= 0.001f) return;

        float panelH = currentPanelH;
        float panelX = PANEL_LEFT;
        float panelY = -panelH + slideProgress * panelH;

        // Панель бере власну матрицю: батч приходить сюди з чужою (світовою або
        // логічною від мінікарти), і покладатись на порядок малювання означало б
        // ловити зсув панелі щоразу, коли цей порядок міняють.
        screenProj.setToOrtho2D(0, 0, logicalW, logicalH);
        batch.setProjectionMatrix(screenProj);
        batch.begin();

        // Фон
        if (panelBg != null) {
            batch.draw(panelBg, panelX, panelY, currentPanelW, panelH);
        } else {
            fill(batch, panelX, panelY, currentPanelW, panelH, UIFactory.COLOR_HUD_PANEL);
            stroke(batch, panelX, panelY, currentPanelW, panelH, UIFactory.COLOR_HUD_EDGE);
            // Акцентна риска по ВЕРХНЬОМУ краю. У списках меню планка стоїть
            // ліворуч, але панель виїжджає знизу — там верхній край і є той
            // бік, що «входить» у кадр.
            fill(batch, panelX, panelY + panelH - ACCENT_BAR, currentPanelW, ACCENT_BAR,
                 UIFactory.COLOR_ACCENT);
        }

        // Портрети
        int maxPerRow = maxPortraitsPerRow();
        float px = panelX + INNER_PAD_X;
        float py = panelY + panelH - PORTRAITS_TOP - PORTRAIT_SIZE;

        for (int i = 0; i < selectedUnits.size; i++) {
            if (i > 0 && i % maxPerRow == 0) {
                px  = panelX + INNER_PAD_X;
                py -= PORTRAIT_SIZE + PORTRAIT_PAD;
            }
            Unit u     = selectedUnits.get(i);
            boolean isArt = (u instanceof Artillery);

            if (portraitBg != null) {
                batch.draw(portraitBg, px, py, PORTRAIT_SIZE, PORTRAIT_SIZE);
                batch.setColor(1f, 1f, 1f, 1f);
            } else {
                // Артилерія виділена акцентною рамкою, а не іншою заливкою:
                // заливка змагалася б із самим портретом за увагу.
                fill(batch, px, py, PORTRAIT_SIZE, PORTRAIT_SIZE, UIFactory.COLOR_HUD_SLOT);
                stroke(batch, px, py, PORTRAIT_SIZE, PORTRAIT_SIZE,
                       isArt ? UIFactory.COLOR_ACCENT : UIFactory.COLOR_HUD_SLOT_EDGE);
            }

            Texture portrait = getPortrait(u.getTexturePath());
            if (portrait != null) {
                // Вписуємо картинку в слот ЗІ ЗБЕРЕЖЕННЯМ пропорції й по центру.
                // Розтягнути в квадрат можна було, поки всі спрайти квадратні;
                // легка піхота (29×11) у квадраті виглядає розчавленою.
                float box = PORTRAIT_SIZE - ICON_INSET * 2f;
                float k   = Math.min(box / portrait.getWidth(), box / portrait.getHeight());
                float iw  = portrait.getWidth()  * k;
                float ih  = portrait.getHeight() * k;
                batch.draw(portrait, px + ICON_INSET + (box - iw) / 2f,
                                     py + ICON_INSET + (box - ih) / 2f, iw, ih);
            }

            px += PORTRAIT_SIZE + PORTRAIT_PAD;
        }

        // Кнопка формації
        float cx = panelX + CMD_PAD;
        float cy = panelY + CMD_PAD;
        drawCmdButton(batch, cmdFormation, cx, cy, formationActive);

        // Друга в ряду — зведення гармат, і лише коли є що зводити.
        if (mergeAvailable) {
            drawCmdButton(batch, cmdMerge, cx + CMD_SIZE + CMD_PAD, cy, false);
            if (cmdMerge == null) drawMergeGlyph(batch, cx + CMD_SIZE + CMD_PAD, cy);
        }

        batch.end();
    }

    /**
     * Чи потрапляє точка в панель. Координати ЛОГІЧНІ, з нулем унизу — тобто
     * такі, які повертають {@code UIScale.inputXToLogical} /
     * {@code inputYToLogical}, а не сирі пікселі з {@code Gdx.input}.
     */
    public boolean containsScreenPoint(float sx, float syFromBottom) {
        if (slideProgress < 0.1f) return false;
        float visibleH = slideProgress * currentPanelH;
        return sx >= PANEL_LEFT && sx <= PANEL_LEFT + currentPanelW
            && syFromBottom >= 0 && syFromBottom <= visibleH;
    }

    /** Клік по панелі. Координати логічні, як у {@link #containsScreenPoint}. */
    public boolean handleClick(float sx, float syFromBottom) {
        if (!containsScreenPoint(sx, syFromBottom)) return false;

        float cx = PANEL_LEFT + CMD_PAD;
        float cy = CMD_PAD;

        if (hitBtn(sx, syFromBottom, cx, cy)) {
            formationActive = !formationActive;
            if (listener != null) listener.onCurvedFormation();
            return true;
        }

        if (mergeAvailable && hitBtn(sx, syFromBottom, cx + CMD_SIZE + CMD_PAD, cy)) {
            // Кнопка разова, а не перемикач: наказ віддається одразу, а далі
            // гармати сходяться самі — тримати «активний» стан нема на чому.
            if (listener != null) listener.onMergeArtillery();
            return true;
        }

        return true;
    }

    public boolean isVisible()         { return slideProgress > 0.01f; }
    public boolean isFormationActive() { return formationActive; }
    public boolean isArtilleryActive() { return artilleryActive; }
    public boolean hasArtillery()      { return hasArtillery; }

    public void dispose() {
        if (panelBg      != null) panelBg.dispose();
        if (portraitBg   != null) portraitBg.dispose();
        if (cmdFormation != null) cmdFormation.dispose();
        if (cmdArtillery != null) cmdArtillery.dispose();
        if (cmdMerge     != null) cmdMerge.dispose();
        for (Texture t : portraitTextures) t.dispose();
        portraitTextures.clear();
        if (fallbackTex  != null) fallbackTex.dispose();
    }

    // ── Приватне ─────────────────────────────────────────────────────────

    /**
     * Кнопка команди. Увімкнений стан — акцентна рамка й світліша заливка, а
     * не інший колір іконки: іконка має лишатись упізнаваною в обох станах.
     */
    private void drawCmdButton(SpriteBatch batch, Texture tex,
                               float cx, float cy, boolean active) {
        fill(batch, cx, cy, CMD_SIZE, CMD_SIZE,
             active ? UIFactory.COLOR_HUD_SLOT_ON : UIFactory.COLOR_HUD_SLOT);
        stroke(batch, cx, cy, CMD_SIZE, CMD_SIZE,
               active ? UIFactory.COLOR_ACCENT : UIFactory.COLOR_HUD_SLOT_EDGE);

        if (tex != null) {
            batch.setColor(1f, 1f, 1f, 1f);
            batch.draw(tex, cx + ICON_INSET, cy + ICON_INSET,
                       CMD_SIZE - ICON_INSET * 2f, CMD_SIZE - ICON_INSET * 2f);
            batch.setColor(1f, 1f, 1f, 1f);
        }
    }

    // ── Примітиви ────────────────────────────────────────────────────────
    //
    // Панель малюється у SpriteBatch, а не ShapeRenderer: разом із нею йдуть
    // портрети-текстури, і переключатись між двома рендерерами заради рамок
    // означало б рвати батч на кожну комірку.

    private void fill(SpriteBatch batch, float x, float y, float w, float h, Color c) {
        batch.setColor(c);
        batch.draw(white(), x, y, w, h);
        batch.setColor(1f, 1f, 1f, 1f);
    }

    /** Рамка в один піксель — чотири смужки по краях. */
    private void stroke(SpriteBatch batch, float x, float y, float w, float h, Color c) {
        batch.setColor(c);
        Texture px = white();
        batch.draw(px, x,           y,           w,      EDGE);
        batch.draw(px, x,           y + h - EDGE, w,     EDGE);
        batch.draw(px, x,           y,           EDGE,   h);
        batch.draw(px, x + w - EDGE, y,          EDGE,   h);
        batch.setColor(1f, 1f, 1f, 1f);
    }

    private boolean hitBtn(float sx, float sy, float bx, float by) {
        return sx >= bx && sx <= bx + CMD_SIZE && sy >= by && sy <= by + CMD_SIZE;
    }

    private int maxPortraitsPerRow() {
        return Math.max(1, (int) ((currentPanelW - INNER_PAD_X * 2f) / (PORTRAIT_SIZE + PORTRAIT_PAD)));
    }

    private float calcPanelHeight(int n) {
        if (n == 0) return CMDS_H + 20f * K;
        int rows = (int) Math.ceil((double) n / maxPortraitsPerRow());
        return rows * (PORTRAIT_SIZE + PORTRAIT_PAD) + PORTRAITS_TOP + CMDS_H + 8f * K;
    }

    private Texture getPortrait(String path) {
        if (path == null) return null;
        for (int i = 0; i < portraitPaths.size; i++)
            if (portraitPaths.get(i).equals(path)) return portraitTextures.get(i);
        if (Gdx.files.internal(path).exists()) {
            Texture t = new Texture(Gdx.files.internal(path));
            portraitTextures.add(t); portraitPaths.add(path); return t;
        }
        return null;
    }

    /**
     * Чи можна звести бодай дві виділені гармати.
     *
     * <p>Умова та сама, за якою наказ виконає симуляція: дві найменші батареї
     * у виділенні мусять разом влізти в {@link Artillery#MAX_STACK}. Тому дуо
     * плюс дуо кнопки не дає — 2 + 2 не влазить у 3, і кнопка, яка нічого не
     * робить, гірша за відсутню.
     */
    private boolean calcMergeAvailable() {
        int smallest = Integer.MAX_VALUE, second = Integer.MAX_VALUE;
        for (int i = 0; i < selectedUnits.size; i++) {
            Unit u = selectedUnits.get(i);
            if (!(u instanceof Artillery)) continue;
            int s = ((Artillery) u).stack;
            if (s < smallest) { second = smallest; smallest = s; }
            else if (s < second) second = s;
        }
        if (second == Integer.MAX_VALUE) return false;   // менше двох гармат
        return smallest + second <= Artillery.MAX_STACK;
    }

    /**
     * Замінник іконки зведення, поки немає {@code ui/cmd_merge.png}: два
     * квадрати, що заходять один на одного. Мальований, а не текстовий, бо в
     * {@code main.ttf} немає жодного знака, схожого на «злити» (DESIGN §3).
     */
    private void drawMergeGlyph(SpriteBatch batch, float bx, float by) {
        float s = CMD_SIZE * 0.30f;
        float x = bx + CMD_SIZE * 0.22f;
        float y = by + CMD_SIZE * 0.24f;
        fill(batch, x, y, s, s, UIFactory.COLOR_HUD_SLOT_EDGE);
        fill(batch, x + s * 0.62f, y + s * 0.62f, s, s, UIFactory.COLOR_ACCENT);
    }

    private boolean sameSelection(Array<Unit> other) {
        if (selectedUnits.size != other.size) return false;
        for (int i = 0; i < other.size; i++)
            if (selectedUnits.get(i) != other.get(i)) return false;
        return true;
    }

    private static Texture loadTex(String path) {
        return Gdx.files.internal(path).exists() ? new Texture(Gdx.files.internal(path)) : null;
    }

    private Texture white() {
        if (fallbackTex == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE); pm.fill();
            fallbackTex = new Texture(pm); pm.dispose();
        }
        return fallbackTex;
    }
}
