package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.Gdx;
public class MoveMarker {

    private static final float FADE_IN_TIME = 0.05f;
    private static final float HOLD_TIME = 0.10f;
    private static final float FADE_OUT_TIME = 0.15f;
    private static final float TOTAL_TIME = FADE_IN_TIME + HOLD_TIME + FADE_OUT_TIME;

    private static final float MAX_RADIUS = 3f;
    private static final float MIN_RADIUS = 1f;

    private static final float MAX_ALPHA = 0.7f;

    private float x, y;
    private float timer = TOTAL_TIME;
    private boolean active = false;

    public void show(float worldX, float worldY) {
        this.x = worldX;
        this.y = worldY;
        this.timer  = 0f;
        this.active = true;
    }

    public void update(float delta) {
        if (!active) return;
        timer += delta;
        if (timer >= TOTAL_TIME) active = false;
    }

    public boolean isActive() { return active; }

    public void draw(ShapeRenderer shapes) {
        if (!active) return;

        float alpha, radius;

        if (timer < FADE_IN_TIME) {
            float t = timer / FADE_IN_TIME;

            alpha  = t * MAX_ALPHA;
            radius = MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * t;
        } else if (timer < FADE_IN_TIME + HOLD_TIME) {
            alpha  = MAX_ALPHA;
            radius = MAX_RADIUS;
        } else {
            float t = (timer - FADE_IN_TIME - HOLD_TIME) / FADE_OUT_TIME;
            alpha  = (1f - t) * MAX_ALPHA;
            radius = MAX_RADIUS;
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Filled);

        shapes.setColor(1f, 1f, 1f, alpha);

        shapes.circle(x, y, radius, 10);

        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }
}
