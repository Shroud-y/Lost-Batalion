package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;

public class ForestTooltip {

    private static final float TOOLTIP_W = 26f;
    private static final float TOOLTIP_H = 26f;
    private static final float OFFSET_X = 12f;
    private static final float OFFSET_Y_BELOW = 16f;

    private final Texture icon;
    private boolean owned;

    public ForestTooltip(String iconPath) {
        if (iconPath != null && Gdx.files.internal(iconPath).exists()) {
            icon  = new Texture(Gdx.files.internal(iconPath));
            owned = true;
        } else {
            icon  = null;
            owned = false;
        }
    }

    public void draw(SpriteBatch batch, int screenX, int screenY) {
        if (icon == null) return;

        float glY = Gdx.graphics.getHeight() - screenY;

        float drawX = screenX + OFFSET_X;
        float drawY = glY - TOOLTIP_H - OFFSET_Y_BELOW;

        batch.draw(icon, drawX, drawY, TOOLTIP_W, TOOLTIP_H);
    }

    public void dispose() {
        if (owned && icon != null) icon.dispose();
    }
}
