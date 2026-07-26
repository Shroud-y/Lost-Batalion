package io.jababa.lost_batalion.terrain;

import io.jababa.lost_batalion.math.Fixed;

/**
 * Множник швидкості від місцевості.
 *
 * <p>Коефіцієнти — константи у Q47.16. Це не педантизм: множник потрапляє
 * прямо в крок руху юніта кожен тік, тобто це найгарячіше місце симуляції.
 */
public class TerrainMovementModifier {

    /** -30% у лісі. */
    public static final long FOREST_PENALTY = Fixed.fromFloat(0.70f);
    /** -60% у річці. */
    public static final long RIVER_PENALTY  = Fixed.fromFloat(0.40f);
    /** -15% на височинах. */
    public static final long HILL_PENALTY   = Fixed.fromFloat(0.85f);

    /** @return множник у Q47.16, {@link Fixed#ONE} = повна швидкість */
    public static long getMultiplier(TerrainType terrain, boolean isInForest) {
        long multiplier = Fixed.ONE;

        // Ефекти накладаються (множаться)
        if (isInForest) multiplier = Fixed.mul(multiplier, FOREST_PENALTY);

        if (terrain == TerrainType.RIVER) {
            multiplier = Fixed.mul(multiplier, RIVER_PENALTY);
        } else if (terrain == TerrainType.HIGHLANDS || terrain == TerrainType.PRE_HIGHLANDS) {
            // Юніти трохи повільніші на крутих підйомах
            multiplier = Fixed.mul(multiplier, HILL_PENALTY);
        }

        return multiplier;
    }
}
