package io.jababa.lost_batalion.screens.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
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

    private static final float PANEL_W       = 400f;
    private static final float PORTRAIT_SIZE =  60f;
    private static final float PORTRAIT_PAD  =   6f;
    private static final float CMD_SIZE      =  52f;
    private static final float CMD_PAD       =   8f;
    private static final float INNER_PAD_X   =  10f;
    private static final float PORTRAITS_TOP =  10f;
    private static final float CMDS_H        = CMD_SIZE + CMD_PAD * 2f;
    private static final float PANEL_LEFT    =  10f;
    private static final float SLIDE_TIME    =  0.20f;

    private float   slideProgress = 0f;
    private boolean visible       = false;

    private Array<Unit> selectedUnits = new Array<>();
    private boolean     hasArtillery  = false;
    private float       currentPanelH = 0f;

    public interface CommandListener {
        void onCurvedFormation();
        void onArtilleryFire();
    }
    private CommandListener listener;

    private final Texture panelBg;
    private final Texture portraitBg;
    private final Texture cmdFormation;
    private final Texture cmdArtillery;

    private final Array<Texture> portraitTextures = new Array<>();
    private final Array<String>  portraitPaths    = new Array<>();
    private Texture fallbackTex = null;

    private boolean formationActive = false;
    private boolean artilleryActive = false;

    public SelectionPanel() {
        panelBg      = loadTex("ui/panel_bg.png");
        portraitBg   = loadTex("ui/portrait_bg.png");
        cmdFormation = loadTex("ui/cmd_formation.png");
        cmdArtillery = loadTex("ui/cmd_artillery.png");
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

    public void draw(SpriteBatch batch, ShapeRenderer shapes, int screenW, int screenH) {
        if (slideProgress <= 0.001f) return;

        float panelH = currentPanelH;
        float panelX = PANEL_LEFT;
        float panelY = -panelH + slideProgress * panelH;

        batch.begin();

        // Фон
        if (panelBg != null) {
            batch.draw(panelBg, panelX, panelY, PANEL_W, panelH);
        } else {
            batch.setColor(0.06f, 0.06f, 0.10f, 0.92f);
            batch.draw(white(), panelX, panelY, PANEL_W, panelH);
            batch.setColor(1f, 1f, 1f, 1f);
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
                // Артилерія — біла рамка, решта — стандартна
                if (isArt) batch.setColor(1f, 1f, 1f, 1f);
                batch.draw(portraitBg, px, py, PORTRAIT_SIZE, PORTRAIT_SIZE);
                batch.setColor(1f, 1f, 1f, 1f);
            } else {
                batch.setColor(isArt ? 0.55f : 0.20f, isArt ? 0.55f : 0.20f, isArt ? 0.55f : 0.32f, 1f);
                batch.draw(white(), px, py, PORTRAIT_SIZE, PORTRAIT_SIZE);
                batch.setColor(1f, 1f, 1f, 1f);
            }

            Texture portrait = getPortrait(u.getTexturePath());
            if (portrait != null)
                batch.draw(portrait, px + 2f, py + 2f, PORTRAIT_SIZE - 4f, PORTRAIT_SIZE - 4f);

            px += PORTRAIT_SIZE + PORTRAIT_PAD;
        }

        // Кнопка формації
        float cx = panelX + CMD_PAD;
        float cy = panelY + CMD_PAD;
        drawCmdButton(batch, cmdFormation, cx, cy, formationActive,
            0.22f, 0.22f, 0.30f,   // inactive bg
            0.85f, 0.85f, 0.85f);  // active (білуватий)

        // Кнопка артилерії (лише якщо є)
        if (hasArtillery) {
            float artX = cx + CMD_SIZE + CMD_PAD;
            drawCmdButton(batch, cmdArtillery, artX, cy, artilleryActive,
                0.30f, 0.16f, 0.10f,   // inactive bg (темно-теракотовий)
                0.90f, 0.90f, 0.90f);  // active (білий)
        }

        batch.end();
    }

    public boolean containsScreenPoint(float sx, float syFromBottom) {
        if (slideProgress < 0.1f) return false;
        float visibleH = slideProgress * currentPanelH;
        return sx >= PANEL_LEFT && sx <= PANEL_LEFT + PANEL_W
            && syFromBottom >= 0 && syFromBottom <= visibleH;
    }

    public boolean handleClick(float sx, float syFromBottom) {
        if (!containsScreenPoint(sx, syFromBottom)) return false;

        float cx = PANEL_LEFT + CMD_PAD;
        float cy = CMD_PAD;

        if (hitBtn(sx, syFromBottom, cx, cy)) {
            formationActive = !formationActive;
            if (listener != null) listener.onCurvedFormation();
            return true;
        }

        if (hasArtillery) {
            float artX = cx + CMD_SIZE + CMD_PAD;
            if (hitBtn(sx, syFromBottom, artX, cy)) {
                artilleryActive = !artilleryActive;
                if (listener != null) listener.onArtilleryFire();
                return true;
            }
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
        for (Texture t : portraitTextures) t.dispose();
        portraitTextures.clear();
        if (fallbackTex  != null) fallbackTex.dispose();
    }

    // ── Приватне ─────────────────────────────────────────────────────────

    private void drawCmdButton(SpriteBatch batch, Texture tex,
                               float cx, float cy, boolean active,
                               float rI, float gI, float bI,
                               float rA, float gA, float bA) {
        if (tex != null) {
            batch.setColor(active ? rA : 1f, active ? gA : 1f, active ? bA : 1f, 1f);
            batch.draw(tex, cx, cy, CMD_SIZE, CMD_SIZE);
        } else {
            batch.setColor(active ? rA : rI, active ? gA : gI, active ? bA : bI, 1f);
            batch.draw(white(), cx, cy, CMD_SIZE, CMD_SIZE);
        }
        batch.setColor(1f, 1f, 1f, 1f);
    }

    private boolean hitBtn(float sx, float sy, float bx, float by) {
        return sx >= bx && sx <= bx + CMD_SIZE && sy >= by && sy <= by + CMD_SIZE;
    }

    private int maxPortraitsPerRow() {
        return Math.max(1, (int) ((PANEL_W - INNER_PAD_X * 2f) / (PORTRAIT_SIZE + PORTRAIT_PAD)));
    }

    private float calcPanelHeight(int n) {
        if (n == 0) return CMDS_H + 20f;
        int rows = (int) Math.ceil((double) n / maxPortraitsPerRow());
        return rows * (PORTRAIT_SIZE + PORTRAIT_PAD) + PORTRAITS_TOP + CMDS_H + 8f;
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
