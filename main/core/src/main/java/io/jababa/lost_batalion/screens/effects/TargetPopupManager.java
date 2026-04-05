package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.utils.Array;

public class TargetPopupManager {

    private static final int POOL_SIZE = 32;

    private final Array<TargetPopup> pool = new Array<>();
    private Texture icon;

    public TargetPopupManager(String iconPath) {
        for (int i = 0; i < POOL_SIZE; i++) pool.add(new TargetPopup());

        if (iconPath != null && Gdx.files.internal(iconPath).exists()) {
            icon = new Texture(Gdx.files.internal(iconPath));
        }
    }

    public void spawn(float worldX, float worldY) {
        for (int i = 0; i < pool.size; i++) {
            TargetPopup p = pool.get(i);
            if (!p.active) {
                p.show(worldX, worldY);
                return;
            }
        }
        pool.get(0).show(worldX, worldY);
    }

    public void update(float delta) {
        for (int i = 0; i < pool.size; i++) pool.get(i).update(delta);
    }

    public void draw(SpriteBatch batch) {
        for (int i = 0; i < pool.size; i++) {
            TargetPopup p = pool.get(i);
            if (p.active) p.draw(batch, icon);
        }
    }

    public void dispose() {
        if (icon != null) icon.dispose();
    }
}
