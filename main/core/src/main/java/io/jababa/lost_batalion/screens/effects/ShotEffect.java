package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.math.MathUtils;
public class ShotEffect {

    private static final float FLIGHT_TIME = 0.10f;
    private static final float TRAIL_FRAC = 0.30f;
    private static final float SPREAD_RADIUS = 1.2f;


    private float sx, sy, ex, ey;

    private float dx, dy;

    private float timer = 0f;
    public  boolean active = false;

    public void show(float fromX, float fromY, float toX, float toY) {

        float angle  = MathUtils.random(0f, MathUtils.PI2);
        float spread = MathUtils.random(0f, SPREAD_RADIUS);
        this.sx = fromX + MathUtils.cos(angle) * spread;
        this.sy = fromY + MathUtils.sin(angle) * spread;
        this.ex = toX;
        this.ey = toY;
        this.dx = ex - sx;
        this.dy = ey - sy;
        this.timer  = 0f;
        this.active = true;
    }

    public void update(float delta) {
        if (!active) return;
        timer += delta;
        if (timer >= FLIGHT_TIME) active = false;
    }

    public void draw(ShapeRenderer shapes) {
        if (!active) return;

        float t = timer / FLIGHT_TIME;
        float alpha = 1f - t;

        float hx = sx + dx * t;
        float hy = sy + dy * t;

        float tailT = Math.max(0f, t - TRAIL_FRAC);
        float tx = sx + dx * tailT;
        float ty = sy + dy * tailT;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Line);

        int   segments = 4;
        float segLen   = 1f / segments;
        for (int i = 0; i < segments; i++) {
            float t0 = tailT + (t - tailT) * (i       * segLen);
            float t1 = tailT + (t - tailT) * ((i + 1) * segLen);
            float ax = sx + dx * t0;
            float ay = sy + dy * t0;
            float bx = sx + dx * t1;
            float by = sy + dy * t1;

            float segAlpha = alpha * ((float)(i + 1) / segments);
            shapes.setColor(1f, 0.95f, 0.85f, segAlpha);
            shapes.line(ax, ay, bx, by);
        }

        shapes.setColor(1f, 1f, 1f, alpha);
        shapes.line(hx - 0.3f, hy - 0.3f, hx + 0.3f, hy + 0.3f);

        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }
}
