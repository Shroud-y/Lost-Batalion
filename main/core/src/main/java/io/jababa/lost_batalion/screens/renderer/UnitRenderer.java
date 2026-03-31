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

    private static final float OUTLINE_PAD = 0f;
    private static final float OUTLINE_THICKNESS = 2f;


    private static final float BAR_H     = 1f;
    private static final float BAR_ABOVE = 1f;

    public UnitRenderer() {
        shapes = new ShapeRenderer();
    }

    public void drawSprites(SpriteBatch batch, Iterable<Unit> units) {
        for (Unit u : units) {
            if (!u.alive) continue;
            Texture tex = getTexture(u);
            float size = u.getSize();
            float x = u.position.x - size / 2f;
            float y = u.position.y - size / 2f;
            batch.draw(tex, x, y, size, size);
        }
    }

    public void drawOverlays(Iterable<Unit> units) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (Unit u : units) {
            if (!u.alive) continue;
            if (u.selected) drawOutline(u);
        }
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Unit u : units) {
            if (!u.alive) continue;
            drawHpBar(u);
        }
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    public void setProjectionMatrix(com.badlogic.gdx.math.Matrix4 combined) {
        shapes.setProjectionMatrix(combined);
    }


    private void drawOutline(Unit u) {
        float size = u.getSize();
        float pad  = OUTLINE_PAD;
        float x = u.position.x - size / 2f - pad;
        float y = u.position.y - size / 2f - pad;
        float w = size + pad * 2f;
        float h = size + pad * 2f;

        com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType st =
            com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType.Line;

        shapes.setColor(1f, 1f, 1f, 0.9f);

        shapes.rect(x, y, w, h);
        shapes.rect(x - 1, y - 1, w + 2, h + 2);
    }


    private void drawHpBar(Unit u) {
        float size = u.getSize();
        float barW = size;
        float x    = u.position.x - size / 2f;
        float y    = u.position.y + size / 2f + BAR_ABOVE;

        // Фон (темно-червоний)
        shapes.setColor(0.3f, 0f, 0f, 0.85f);
        shapes.rect(x, y, barW, BAR_H);

        float ratio = u.hp / u.maxHp;
        if (ratio > 0.5f) {
            shapes.setColor(0.2f, 0.8f, 0.2f, 0.9f);
        } else if (ratio > 0.25f) {
            shapes.setColor(0.9f, 0.8f, 0.1f, 0.9f);
        } else {
            shapes.setColor(0.9f, 0.2f, 0.1f, 0.9f);
        }
        shapes.rect(x, y, barW * ratio, BAR_H);
    }

    private Texture getTexture(Unit u) {
        String path = u.getTexturePath();
        if (textureCache.containsKey(path)) {
            return textureCache.get(path);
        }

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

        if (u.team == Team.PLAYER) {
            pm.setColor(0.2f, 0.4f, 0.9f, 1f);
        } else {
            pm.setColor(0.85f, 0.2f, 0.2f, 1f);
        }
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
        for (Texture tex : textureCache.values()) {
            tex.dispose();
        }
        textureCache.clear();
    }
}
