package io.jababa.lost_batalion.terrain;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;

/**
 * Зчитує технічну маску карти та визначає тип місцевості за кольором пікселя
 */
public class TerrainMaskManager {

    private static final int TOLERANCE = 20;
    private static final int FOREST_R = 46;
    private static final int FOREST_G = 88;
    private static final int FOREST_B = 26;

    private Pixmap maskPixmap;
    private boolean loaded = false;

    public TerrainMaskManager(String maskPath) {
        if (maskPath != null && Gdx.files.internal(maskPath).exists()) {
            maskPixmap = new Pixmap(Gdx.files.internal(maskPath));
            loaded = true;
        }
    }

    public TerrainType getTerrainAt(float worldX, float worldY) {
        if (!loaded) return TerrainType.NONE;

        int px = (int) worldX;
        int py = maskPixmap.getHeight() - 1 - (int) worldY;

        if (px < 0 || py < 0 || px >= maskPixmap.getWidth() || py >= maskPixmap.getHeight()) {
            return TerrainType.NONE;
        }

        int pixel = maskPixmap.getPixel(px, py);
        int r = (pixel >> 24) & 0xFF;
        int g = (pixel >> 16) & 0xFF;
        int b = (pixel >>  8) & 0xFF;

        if (colorMatches(r, g, b, FOREST_R, FOREST_G, FOREST_B)) {
            return TerrainType.FOREST;
        }

        return TerrainType.NONE;
    }

    private boolean colorMatches(int r, int g, int b, int tr, int tg, int tb) {
        return Math.abs(r - tr) <= TOLERANCE
            && Math.abs(g - tg) <= TOLERANCE
            && Math.abs(b - tb) <= TOLERANCE;
    }

    public void dispose() {
        if (maskPixmap != null) {
            maskPixmap.dispose();
            maskPixmap = null;
        }
    }
}
