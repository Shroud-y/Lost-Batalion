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

    /**
     * Намалювати підказку біля курсора.
     *
     * <p>Координати ЛОГІЧНІ, з нулем УНИЗУ — перевертає й ділить їх викликач
     * через {@code UIScale.inputXToLogical} / {@code inputYToLogical}. Раніше
     * підказка перевертала y сама, і після переходу HUD у логічні одиниці це
     * була б єдина річ на екрані, що досі рахує в пікселях кадру.
     */
    public void draw(SpriteBatch batch, float logicalX, float logicalY) {
        if (icon == null) return;

        float drawX = logicalX + OFFSET_X;
        float drawY = logicalY - TOOLTIP_H - OFFSET_Y_BELOW;

        batch.draw(icon, drawX, drawY, TOOLTIP_W, TOOLTIP_H);
    }

    public void dispose() {
        if (owned && icon != null) icon.dispose();
    }
}
