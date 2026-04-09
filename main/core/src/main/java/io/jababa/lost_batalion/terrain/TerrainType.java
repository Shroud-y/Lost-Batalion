package io.jababa.lost_batalion.terrain;

/**
 * Типи місцевості що визначаються за кольором маски.
 *
 * Коди для зручності:
 *   LOWLANDS     = 1а  (нізіни)
 *   PRE_LOWLANDS = 1б  (перед нізіни)
 *   PLAINS       = 2а  (рівнини)
 *   PLAINS_ALT   = 2б  (рівнини хз)
 *   PRE_HIGHLANDS= 3а  (перед височини)
 *   HIGHLANDS    = 3б  (височини)
 *   FOREST           (ліс — окрема маска)
 */
public enum TerrainType {
    NONE,
    FOREST,
    LOWLANDS,       // 1а — нізіни
    PRE_LOWLANDS,   // 1б — перед нізіни
    PLAINS,         // 2а — рівнини
    PLAINS_ALT,     // 2б — рівнини хз
    PRE_HIGHLANDS,  // 3а — перед височини
    HIGHLANDS       // 3б — височини
}
