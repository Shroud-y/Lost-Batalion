package io.jababa.lost_batalion;

import com.badlogic.gdx.Game;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import io.jababa.lost_batalion.screens.MainMenuScreen;

/**
 * Ентріпоінт
 */
public class LostBatalion extends Game {

    public SpriteBatch batch;

    @Override
    public void create() {
        batch = new SpriteBatch();
        setScreen(new MainMenuScreen(this));
    }

    @Override
    public void dispose() {

        super.dispose();
        batch.dispose();
    }
}
