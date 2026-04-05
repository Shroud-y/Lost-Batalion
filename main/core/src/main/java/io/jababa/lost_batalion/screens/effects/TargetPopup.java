package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;


public class TargetPopup {

    private static final float RISE_TIME = 0.20f;
    private static final float FADE_OUT_TIME = 0.30f;
    private static final float TOTAL_TIME = RISE_TIME + FADE_OUT_TIME;

    private static final float RISE_DISTANCE = 4f;
    private static final float START_SIZE = 2f;
    private static final float END_SIZE = 10f;

    private float baseX, baseY;
    private float timer = TOTAL_TIME;
    public  boolean active = false;

    public void show(float worldX, float worldY) {
        this.baseX = worldX;
        this.baseY = worldY;
        this.timer = 0f;
        this.active = true;
    }

    public void update(float delta) {
        if (!active) return;
        timer += delta;
        if (timer >= TOTAL_TIME) active = false;
    }

    public void draw(SpriteBatch batch, Texture icon) {
        if (!active || icon == null) return;

        float alpha, size, offsetY;

        if (timer < RISE_TIME) {
            float t  = timer / RISE_TIME;
            float et = easeOut(t);
            alpha = t;
            size = START_SIZE + (END_SIZE - START_SIZE) * et;
            offsetY = RISE_DISTANCE * et;
        } else {
            float t  = (timer - RISE_TIME) / FADE_OUT_TIME;
            alpha = 1f - easeIn(t);
            size = END_SIZE;
            offsetY = RISE_DISTANCE;
        }

        float drawX = baseX - size / 2f;
        float drawY = baseY + offsetY;

        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(icon, drawX, drawY, size, size);
        batch.setColor(1f, 1f, 1f, 1f);
    }

    private float easeOut(float t) { return 1f - (1f - t) * (1f - t); }
    private float easeIn(float t)  { return t * t; }
}
