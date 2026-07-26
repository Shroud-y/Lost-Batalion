package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.ObjectMap;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.units.Unit;

public class UnitRenderer {

    private final ObjectMap<String, Texture> textureCache = new ObjectMap<>();
    private final ShapeRenderer shapes;

    private static final float OUTLINE_PAD       = 0f;
    private static final float BAR_W             = 0.7f;
    private static final float BAR_LEFT          = 0.5f;

    public UnitRenderer() {
        shapes = new ShapeRenderer();
    }

    /**
     * Рендер спрайтів очима сторони {@code viewer}.
     *
     * <p>Видимість рахується для обох сторін і належить симуляції; рендер лише
     * бере той її бік, за який грає локальний гравець. Кольори спрайтів при
     * цьому абсолютні (хост синій, гість червоний) — гість бачить свою армію
     * червоною, зате обидва бачать однакову картинку.
     *
     * @param alpha частка тіку, що вже минула (0..1). Позиція береться між
     *              станом на початок тіку і поточним — симуляція йде 40 разів
     *              на секунду, і без цього рух виглядав би ривками.
     */
    public void drawSprites(SpriteBatch batch, Iterable<Unit> units, float alpha, Team viewer) {
        for (Unit u : units) {
            if (!u.alive) continue;
            if (!u.isVisibleTo(viewer)) continue; // туман

            Texture tex = getTexture(u);
            // getSizePx(), а НЕ getSize(): друге повертає fixed-point long, і
            // Java мовчки розширила б його у float — юніт розміром 10 малювався б
            // розміром 655360 і закривав би собою всю карту.
            float size  = u.getSizePx();
            float x     = u.renderX(alpha) - size / 2f;
            float y     = u.renderY(alpha) - size / 2f;
            batch.draw(tex, x, y, size, size);
        }
    }

    /** Рендер оверлеїв (виділення, HP-бар) очима сторони {@code viewer}. */
    public void drawOverlays(Iterable<Unit> units, float alpha, Team viewer) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (Unit u : units) {
            if (!u.alive) continue;
            if (!u.isVisibleTo(viewer)) continue;
            if (u.selected) drawOutline(u, alpha);
        }
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Unit u : units) {
            if (!u.alive) continue;
            if (!u.isVisibleTo(viewer)) continue;
            drawHpBar(u, alpha);
        }
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    public void setProjectionMatrix(com.badlogic.gdx.math.Matrix4 combined) {
        shapes.setProjectionMatrix(combined);
    }

    // ── Приватні методи ───────────────────────────────────────────────────

    private void drawOutline(Unit u, float alpha) {
        float size = u.getSizePx();
        float pad  = OUTLINE_PAD;
        float x    = u.renderX(alpha) - size / 2f - pad;
        float y    = u.renderY(alpha) - size / 2f - pad;
        float w    = size + pad * 2f;
        float h    = size + pad * 2f;

        shapes.setColor(1f, 1f, 1f, 0.9f);
        shapes.rect(x, y, w, h);

    }

    private void drawHpBar(Unit u, float alpha) {
        float size = u.getSizePx();
        float barH = size;

        float x = u.renderX(alpha) - size / 2f - BAR_LEFT - BAR_W;
        float y = u.renderY(alpha) - size / 2f;

        shapes.setColor(0.3f, 0f, 0f, 0.85f);
        shapes.rect(x, y, BAR_W, barH);

        // hp і maxHp — цілі (Q47.16), тож пряме ділення дало б 1 або 0.
        float ratio = u.hpRatio();
        if (ratio > 0.5f)       shapes.setColor(0.2f, 0.8f, 0.2f, 0.9f);
        else if (ratio > 0.25f) shapes.setColor(0.9f, 0.8f, 0.1f, 0.9f);
        else                    shapes.setColor(0.9f, 0.2f, 0.1f, 0.9f);

        shapes.rect(x, y, BAR_W, barH * ratio);
    }

    private Texture getTexture(Unit u) {
        String path = u.getTexturePath();
        if (textureCache.containsKey(path)) return textureCache.get(path);

        Texture tex;
        if (Gdx.files.internal(path).exists()) {
            tex = new Texture(Gdx.files.internal(path));
        } else {
            tex = buildFallbackTexture(u);
        }
        textureCache.put(path, tex);
        return tex;
    }

    private Texture buildFallbackTexture(Unit u) {
        int sz = 32;
        Pixmap pm = new Pixmap(sz, sz, Pixmap.Format.RGBA8888);

        if (u.team == Team.PLAYER) pm.setColor(0.2f, 0.4f, 0.9f, 1f);
        else                       pm.setColor(0.85f, 0.2f, 0.2f, 1f);
        pm.fill();

        pm.setColor(1f, 1f, 1f, 0.5f);
        pm.drawRectangle(0, 0, sz, sz);
        pm.setColor(1f, 1f, 1f, 0.6f);
        pm.drawLine(sz / 2, 4, sz / 2, sz - 4);
        pm.drawLine(4, sz / 2, sz - 4, sz / 2);

        Texture tex = new Texture(pm);
        pm.dispose();
        return tex;
    }

    public void dispose() {
        shapes.dispose();
        for (Texture tex : textureCache.values()) tex.dispose();
        textureCache.clear();
    }
}
