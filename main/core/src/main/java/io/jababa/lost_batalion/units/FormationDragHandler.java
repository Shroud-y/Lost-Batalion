package io.jababa.lost_batalion.units;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.Gdx;

public class FormationDragHandler {


    private static final float HOLD_TIME = 0.5f;

    private static final float MIN_LINE_LENGTH = 8f;

    /**
     * Білий, а не акцентне золото.
     *
     * <p>Той самий виняток, що для рамки видимої ділянки на мінікарті
     * (DESIGN §2): лінія наказу лежить ПОВЕРХ карти, постійно рухається і
     * мусить читатись однаково над травою, ріллею й водою. Жовта губилась на
     * жовтуватих ділянках саме тоді, коли на неї дивишся.
     */
    private static final float LINE_R = 1f, LINE_G = 1f, LINE_B = 1f;

    /**
     * Товщина в ЕКРАННИХ пікселях. Множиться на zoom камери, бо {@code
     * ShapeRenderer} малює у світових одиницях: без цього лінія на віддаленій
     * камері ставала волосиною, тобто товщою вона була б рівно там, де й так
     * усе видно.
     */
    private static final float LINE_PX = 3f;

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

    public void draw(ShapeRenderer shapes, float zoom) {
        if (!active) return;

        float len = lineLength();
        if (len < 1f) return;

        float thickness = LINE_PX * zoom;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // rectLine, а не line: товщину лінії GL задає glLineWidth, який на
        // сучасних драйверах або обмежений одиницею, або ігнорується зовсім.
        // Прямокутник уздовж вектора — єдиний надійний спосіб.
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(LINE_R, LINE_G, LINE_B, 0.95f);
        shapes.rectLine(startX, startY, endX, endY, thickness);
        shapes.circle(startX, startY, thickness, 12);
        shapes.circle(endX, endY, thickness, 12);

        float dx  = endX - startX;
        float dy  = endY - startY;
        float inv = 1f / len;
        float nx = dx * inv;
        float ny = dy * inv;
        float px = -ny;
        float py = nx;

        float arrowSize = LINE_PX * 3f * zoom;
        float ax1 = endX - nx * arrowSize + px * arrowSize * 0.5f;
        float ay1 = endY - ny * arrowSize + py * arrowSize * 0.5f;
        float ax2 = endX - nx * arrowSize - px * arrowSize * 0.5f;
        float ay2 = endY - ny * arrowSize - py * arrowSize * 0.5f;
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
