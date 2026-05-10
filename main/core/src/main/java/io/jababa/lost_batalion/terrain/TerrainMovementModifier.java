package io.jababa.lost_batalion.terrain;

public class TerrainMovementModifier {
    // Коефіцієнти (1.0 = повна швидкість)
    public static final float FOREST_PENALTY = 0.70f; // -30%
    public static final float RIVER_PENALTY  = 0.40f; // -60%
    public static final float HILL_PENALTY   = 0.85f; // -15% (для височин)

    public static float getMultiplier(TerrainType terrain, boolean isInForest) {
        float multiplier = 1.0f;

        // Ефекти накладаються (множаться)
        if (isInForest) multiplier *= FOREST_PENALTY;

        if (terrain == TerrainType.RIVER) {
            multiplier *= RIVER_PENALTY;
        } else if (terrain == TerrainType.HIGHLANDS || terrain == TerrainType.PRE_HIGHLANDS) {
            // Юніти трохи повільніші на крутих підйомах
            multiplier *= HILL_PENALTY;
        }

        return multiplier;
    }
}
