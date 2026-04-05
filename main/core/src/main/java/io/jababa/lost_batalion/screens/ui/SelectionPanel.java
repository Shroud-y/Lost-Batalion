package io.jababa.lost_batalion.screens.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.units.Unit;

/**
 * Панель виділення — виїжджає знизу-ліворуч коли є виділені юніти.
 *
 * Особливості:
 *  - Динамічна висота: якщо портрети не влазяться в один рядок,
 *    панель розширюється (додаються рядки портретів).
 *  - Захист від кліків: containsScreenPoint() дозволяє GameScreen
 *    ігнорувати кліки що потрапили у зону панелі.
 *  - Кнопка "Curved Formation": колбек onCurvedFormation.
 *
 * PNG шляхи (замінити на свої):
 *   ui/panel_bg.png       — фон панелі
 *   ui/portrait_bg.png    — рамка портрету
 *   ui/cmd_formation.png  — кнопка кривої формації
 */
public class SelectionPanel {

    // ── Розміри ────────────────────────────────────────────────────────────
    private static final float PANEL_W       = 400f;
    private static final float PORTRAIT_SIZE =  60f;
    private static final float PORTRAIT_PAD  =   6f;
    private static final float CMD_SIZE      =  52f;
    private static final float CMD_PAD       =   8f;
    private static final float INNER_PAD_X   =  10f;
    private static final float PORTRAITS_TOP =  10f; // відступ зверху до першого рядка
    private static final float CMDS_H        = CMD_SIZE + CMD_PAD * 2f;
    private static final float PANEL_LEFT    =  10f; // відступ від лівого краю екрану

    // ── Анімація ───────────────────────────────────────────────────────────
    private static final float SLIDE_TIME = 0.20f;

    private float slideProgress = 0f;
    private boolean visible     = false;

    // ── Стан ──────────────────────────────────────────────────────────────
    private Array<Unit> selectedUnits = new Array<>();

    // Поточна динамічна висота панелі (змінюється коли міняється кількість юнітів)
    private float currentPanelH = 0f;

    // ── Колбек ────────────────────────────────────────────────────────────
    public interface CommandListener {
        void onCurvedFormation();
    }
    private CommandListener listener;

    // ── Текстури ──────────────────────────────────────────────────────────
    private final Texture panelBg;
    private final Texture portraitBg;
    private final Texture cmdFormation;

    private final Array<Texture> portraitTextures = new Array<>();
    private final Array<String>  portraitPaths    = new Array<>();
    private Texture fallbackTex = null;

    // ── Стан кнопки ───────────────────────────────────────────────────────
    private boolean formationActive = false;

    public SelectionPanel() {
        panelBg      = loadTex("ui/panel_bg.png");
        portraitBg   = loadTex("ui/portrait_bg.png");
        cmdFormation = loadTex("ui/cmd_formation.png");
    }

    public void setListener(CommandListener l) { this.listener = l; }

    // ── Публічне API ──────────────────────────────────────────────────────

    public void update(float delta, Array<Unit> current) {
        boolean hasSelection = current.size > 0;

        if (hasSelection != visible || !sameSelection(current)) {
            selectedUnits = new Array<>(current);
            visible = hasSelection;
            currentPanelH = calcPanelHeight(selectedUnits.size);
        }

        float target = visible ? 1f : 0f;
        float speed  = 1f / SLIDE_TIME;
        if (slideProgress < target)
            slideProgress = Math.min(slideProgress + speed * delta, target);
        else if (slideProgress > target)
            slideProgress = Math.max(slideProgress - speed * delta, target);

        // Якщо виділення зникло — скидаємо режим формації
        if (!visible) formationActive = false;
    }

    public void setFormationActive(boolean v) { formationActive = v; }

    /**
     * Рендер панелі.
     * batch має бути БЕЗ camera.combined — тільки матриця екрану.
     */
    public void draw(SpriteBatch batch, ShapeRenderer shapes, int screenW, int screenH) {
        if (slideProgress <= 0.001f) return;

        float panelH = currentPanelH;
        float panelX = PANEL_LEFT;
        float panelY = -panelH + slideProgress * panelH; // виїжджає знизу

        batch.begin();

        // ── Фон ───────────────────────────────────────────────────────────
        if (panelBg != null) {
            batch.draw(panelBg, panelX, panelY, PANEL_W, panelH);
        } else {
            batch.setColor(0.08f, 0.08f, 0.14f, 0.93f);
            batch.draw(white(), panelX, panelY, PANEL_W, panelH);
            batch.setColor(1f, 1f, 1f, 1f);
        }

        // ── Портрети ──────────────────────────────────────────────────────
        int maxPerRow = maxPortraitsPerRow();
        float px = panelX + INNER_PAD_X;
        float py = panelY + panelH - PORTRAITS_TOP - PORTRAIT_SIZE;

        for (int i = 0; i < selectedUnits.size; i++) {
            if (i > 0 && i % maxPerRow == 0) {
                px  = panelX + INNER_PAD_X;
                py -= PORTRAIT_SIZE + PORTRAIT_PAD;
            }

            // Рамка
            if (portraitBg != null) {
                batch.draw(portraitBg, px, py, PORTRAIT_SIZE, PORTRAIT_SIZE);
            } else {
                batch.setColor(0.20f, 0.20f, 0.32f, 1f);
                batch.draw(white(), px, py, PORTRAIT_SIZE, PORTRAIT_SIZE);
                batch.setColor(1f, 1f, 1f, 1f);
            }

            // Портрет
            Texture portrait = getPortrait(selectedUnits.get(i).getTexturePath());
            if (portrait != null) {
                batch.draw(portrait, px + 2f, py + 2f,
                    PORTRAIT_SIZE - 4f, PORTRAIT_SIZE - 4f);
            }

            px += PORTRAIT_SIZE + PORTRAIT_PAD;
        }

        // ── Кнопка кривої формації ─────────────────────────────────────────
        float cx = panelX + CMD_PAD;
        float cy = panelY + CMD_PAD;

        if (cmdFormation != null) {
            // Підсвічуємо якщо активна
            if (formationActive) batch.setColor(1f, 0.85f, 0.2f, 1f);
            batch.draw(cmdFormation, cx, cy, CMD_SIZE, CMD_SIZE);
            batch.setColor(1f, 1f, 1f, 1f);
        } else {
            // Fallback: кольоровий квадрат
            batch.setColor(formationActive ? 0.8f : 0.25f, 0.5f, 0.15f, 1f);
            batch.draw(white(), cx, cy, CMD_SIZE, CMD_SIZE);
            batch.setColor(1f, 1f, 1f, 1f);
        }

        batch.end();
    }

    public boolean containsScreenPoint(float screenX, float screenYFromBottom) {
        if (slideProgress < 0.1f) return false;

        float panelH = currentPanelH;
        float visibleH  = slideProgress * panelH;

        return screenX >= PANEL_LEFT
            && screenX <= PANEL_LEFT + PANEL_W
            && screenYFromBottom >= 0
            && screenYFromBottom <= visibleH;
    }

    public boolean handleClick(float screenX, float screenYFromBottom) {
        if (!containsScreenPoint(screenX, screenYFromBottom)) return false;

        float panelH = currentPanelH;
        float panelY = -panelH + slideProgress * panelH;

        float cx = PANEL_LEFT + CMD_PAD;
        float cy = CMD_PAD;

        if (screenX >= cx && screenX <= cx + CMD_SIZE
            && screenYFromBottom >= cy && screenYFromBottom <= cy + CMD_SIZE) {
            formationActive = !formationActive;
            if (listener != null) listener.onCurvedFormation();
            return true;
        }

        return true;
    }

    public boolean isVisible() { return slideProgress > 0.01f; }

    public void dispose() {
        if (panelBg != null) panelBg.dispose();
        if (portraitBg != null) portraitBg.dispose();
        if (cmdFormation != null) cmdFormation.dispose();
        for (int i = 0; i < portraitTextures.size; i++) portraitTextures.get(i).dispose();
        portraitTextures.clear();
        if (fallbackTex  != null) fallbackTex.dispose();
    }

    private int maxPortraitsPerRow() {
        float available = PANEL_W - INNER_PAD_X * 2f;
        return Math.max(1, (int) (available / (PORTRAIT_SIZE + PORTRAIT_PAD)));
    }
    private float calcPanelHeight(int unitCount) {
        if (unitCount == 0) return CMDS_H + 20f;
        int maxPerRow = maxPortraitsPerRow();
        int rows = (int) Math.ceil((double) unitCount / maxPerRow);
        float portraitsH = rows * (PORTRAIT_SIZE + PORTRAIT_PAD) + PORTRAITS_TOP;
        return portraitsH + CMDS_H + 8f;
    }

    private Texture getPortrait(String path) {
        if (path == null) return null;
        for (int i = 0; i < portraitPaths.size; i++) {
            if (portraitPaths.get(i).equals(path)) return portraitTextures.get(i);
        }
        if (Gdx.files.internal(path).exists()) {
            Texture t = new Texture(Gdx.files.internal(path));
            portraitTextures.add(t);
            portraitPaths.add(path);
            return t;
        }
        return null;
    }

    private boolean sameSelection(Array<Unit> other) {
        if (selectedUnits.size != other.size) return false;
        for (int i = 0; i < other.size; i++) {
            if (selectedUnits.get(i) != other.get(i)) return false;
        }
        return true;
    }

    private static Texture loadTex(String path) {
        if (Gdx.files.internal(path).exists()) return new Texture(Gdx.files.internal(path));
        return null;
    }

    private Texture white() {
        if (fallbackTex == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE); pm.fill();
            fallbackTex = new Texture(pm);
            pm.dispose();
        }
        return fallbackTex;
    }

    public boolean isFormationActive() { return formationActive; }
}
