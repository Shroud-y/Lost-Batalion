package io.jababa.lost_batalion.terrain;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;

/**
 * Зчитує технічну маску карти та визначає тип місцевості за кольором пікселя.
 *
 * Кольори масок (hex → RGB):
 *   FOREST       #2E5819 → ( 46,  88,  26)  — стара маска лісу
 *   LOWLANDS     #57C371 → ( 87, 195, 113)  — 1а нізіни
 *   PRE_LOWLANDS #00E02F → (  0, 224,  47)  — 1б перед нізіни
 *   PLAINS       #689055 → (104, 144,  85)  — 2а рівнини
 *   PLAINS_ALT   #35880E → ( 53, 136,  14)  — 2б рівнини хз
 *   PRE_HIGHLANDS#C0AF00 → (192, 175,   0)  — 3а перед височини
 *   HIGHLANDS    #C06F00 → (192, 111,   0)  — 3б височини
 */
public class TerrainMaskManager {

    private static final int TOLERANCE = 20;

    // Кольори з технічного завдання
    private static final int[] FOREST_RGB        = { 93, 99, 0 };
    private static final int[] RIVER_RGB = { 0, 83, 255 };
    private static final int[] LOWLANDS_RGB       = { 87, 195, 113};
    private static final int[] PRE_LOWLANDS_RGB   = {  0, 224,  47};
    private static final int[] PLAINS_RGB         = {104, 144,  85};
    private static final int[] PLAINS_ALT_RGB     = { 53, 136,  14};
    private static final int[] PRE_HIGHLANDS_RGB  = {192, 175,   0};
    private static final int[] HIGHLANDS_RGB      = {192, 111,   0};

    private Pixmap maskPixmap;
    private boolean loaded = false;

    public TerrainMaskManager(String maskPath) {
        if (maskPath != null && Gdx.files.internal(maskPath).exists()) {
            maskPixmap = new Pixmap(Gdx.files.internal(maskPath));
            loaded = true;
        }
    }

    // Додай ці методи замість старого getTerrainAt
    public boolean isForestAt(float worldX, float worldY) {
        if (!loaded) return false;

        // px/py розрахунок винесено в окремий метод для зручності
        int pixel = getPixelFromMask(worldX, worldY);
        if (pixel == 0) return false;

        int r = (pixel >> 24) & 0xFF;
        int g = (pixel >> 16) & 0xFF;
        int b = (pixel >>  8) & 0xFF;

        return matches(r, g, b, FOREST_RGB);
    }

    public TerrainType getElevationAt(float worldX, float worldY) {
        if (!loaded) return TerrainType.NONE;

        int pixel = getPixelFromMask(worldX, worldY);
        if (pixel == 0) return TerrainType.NONE;

        int r = (pixel >> 24) & 0xFF;
        int g = (pixel >> 16) & 0xFF;
        int b = (pixel >>  8) & 0xFF;

        if (matches(r, g, b, RIVER_RGB)) return TerrainType.RIVER;

        // Перевіряємо тільки висоти. Ліс ігноруємо, бо він перевіряється окремим методом.
        if (matches(r, g, b, LOWLANDS_RGB))       return TerrainType.LOWLANDS;
        if (matches(r, g, b, PRE_LOWLANDS_RGB))   return TerrainType.PRE_LOWLANDS;
        if (matches(r, g, b, PLAINS_RGB))         return TerrainType.PLAINS;
        if (matches(r, g, b, PLAINS_ALT_RGB))     return TerrainType.PLAINS_ALT;
        if (matches(r, g, b, PRE_HIGHLANDS_RGB))  return TerrainType.PRE_HIGHLANDS;
        if (matches(r, g, b, HIGHLANDS_RGB))      return TerrainType.HIGHLANDS;

        return TerrainType.NONE;
    }

    // Допоміжний метод для отримання пікселя (щоб не дублювати математику)
    private int getPixelFromMask(float worldX, float worldY) {
        int px = (int) worldX;
        int py = maskPixmap.getHeight() - 1 - (int) worldY;
        if (px < 0 || py < 0 || px >= maskPixmap.getWidth() || py >= maskPixmap.getHeight()) {
            return 0;
        }
        return maskPixmap.getPixel(px, py);
    }

    private boolean matches(int r, int g, int b, int[] target) {
        return Math.abs(r - target[0]) <= TOLERANCE
            && Math.abs(g - target[1]) <= TOLERANCE
            && Math.abs(b - target[2]) <= TOLERANCE;
    }

    public void dispose() {
        if (maskPixmap != null) {
            maskPixmap.dispose();
            maskPixmap = null;
        }
    }
}
