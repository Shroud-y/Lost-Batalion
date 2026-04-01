package io.jababa.lost_batalion.units;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.Gdx;

public class FormationDragHandler {


    private static final float HOLD_TIME = 0.5f;

    private static final float MIN_LINE_LENGTH = 8f;

    private static final float LINE_R = 1f, LINE_G = 0.85f, LINE_B = 0f;

    private float startX, startY;
    private float endX, endY;

    private float holdTimer  = 0f;
    private boolean pressed = false;
    private boolean active = false;


    public void onRmbDown(float worldX, float worldY) {
        startX    = worldX;
        startY    = worldY;
        endX      = worldX;
        endY      = worldY;
        holdTimer = 0f;
        pressed   = true;
        active    = false;
    }

    public void update(float delta, float worldX, float worldY) {
        if (!pressed) return;

        endX = worldX;
        endY = worldY;

        if (!active) {
            holdTimer += delta;
            if (holdTimer >= HOLD_TIME) {
                active = true;
            }
        }
    }

    public boolean onRmbUp() {
        boolean wasActive = active && lineLength() >= MIN_LINE_LENGTH;
        pressed  = false;
        active   = false;
        holdTimer = 0f;
        return wasActive;
    }

    public void cancel() {
        pressed  = false;
        active   = false;
        holdTimer = 0f;
    }

    public boolean isActive()  { return active; }
    public boolean isPressed() { return pressed; }

    public float getStartX() { return startX; }
    public float getStartY() { return startY; }
    public float getEndX() { return endX; }
    public float getEndY() { return endY; }
    public void forceActivate() {
        if (pressed) active = true;
    }

    public void draw(ShapeRenderer shapes) {
        if (!active) return;

        float len = lineLength();
        if (len < 1f) return;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(LINE_R, LINE_G, LINE_B, 0.9f);
        shapes.line(startX, startY, endX, endY);
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(LINE_R, LINE_G, LINE_B, 0.8f);
        shapes.circle(startX, startY, 2f, 12);
        shapes.circle(endX, endY, 2f,12);
        shapes.end();

        float dx  = endX - startX;
        float dy  = endY - startY;
        float inv = 1f / len;
        float nx = dx * inv;
        float ny = dy * inv;
        float px = -ny;
        float py = nx;

        float arrowSize = 4f;
        float ax1 = endX - nx * arrowSize + px * arrowSize * 0.5f;
        float ay1 = endY - ny * arrowSize + py * arrowSize * 0.5f;
        float ax2 = endX - nx * arrowSize - px * arrowSize * 0.5f;
        float ay2 = endY - ny * arrowSize - py * arrowSize * 0.5f;

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(LINE_R, LINE_G, LINE_B, 0.9f);
        shapes.triangle(endX, endY, ax1, ay1, ax2, ay2);
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }


    private float lineLength() {
        float dx = endX - startX;
        float dy = endY - startY;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
