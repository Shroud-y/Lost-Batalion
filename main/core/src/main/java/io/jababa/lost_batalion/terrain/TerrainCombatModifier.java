package io.jababa.lost_batalion.terrain;

/**
 * Рахує модифікатор захисту цілі залежно від місцевості атакуючого і цілі.
 *
 * Таблиця бафів/дебафів (defenseMultiplier цілі):
 *
 *   Ціль \ Атакуючий   | 1а(LOW) | 1б(PLOW) | 2а(PLA) | 2б(PALT) | 3а(PHIGH) | 3б(HIGH)
 *   ─────────────────────────────────────────────────────────────────────────────────────
 *   1а нізіни           |  base   |  base    |  -30%  |   -30%  |   -30%   |   -30%
 *   1б перед нізіни     |  +20%   |  base    |  -30%  |   -30%  |   -30%   |   -30%
 *   2а рівнини          |  +20%   |  +20%   |  base  |   base  |   base   |   base
 *   2б рівнини хз       |  +20%   |  +20%   |  base  |   base  |   base   |   base
 *   3а перед височини   |  base   |  +20%   |  +20%  |   +20%  |   base   |   base
 *   3б височини         |  +30%   |  +30%   |  +30%  |   +30%  |   +30%   |   base
 *   FOREST / NONE       |  base   |  base    |  base  |   base  |   base   |   base
 *
 * defenseMultiplier > 1.0 = ціль захищена (атакуючий наносить менше)
 * defenseMultiplier < 1.0 = ціль вразлива (атакуючий наносить більше)
 *
 * Фінальний damage = (attacker.damage - target.defense) * defenseMultiplier
 */
public class TerrainCombatModifier {

    // Відсотки (значення понад 1.0 = баф захисту, менше = дебаф)
    private static final float BUFF_SMALL  = 1.20f; // +20% захист
    private static final float BUFF_LARGE  = 1.30f; // +30% захист
    private static final float DEBUFF      = 0.70f; // -30% захист (вразливість)
    private static final float NEUTRAL     = 1.00f;

    private TerrainCombatModifier() {}

    /**
     * Повертає множник захисту ЦІЛІ з урахуванням місцевості обох юнітів.
     *
     * @param attackerTerrain місцевість атакуючого юніта
     * @param defenderTerrain місцевість юніта-цілі
     * @return множник: > 1 = більший захист (менше damage), < 1 = менший захист
     */
    public static float getDefenseMultiplier(TerrainType attackerTerrain,
                                             TerrainType defenderTerrain) {
        switch (defenderTerrain) {

            case LOWLANDS: // 1а — дебаф від 2а,2б,3а,3б
                switch (attackerTerrain) {
                    case PLAINS:
                    case PLAINS_ALT:
                    case PRE_HIGHLANDS:
                    case HIGHLANDS:
                        return DEBUFF;
                    default:
                        return NEUTRAL;
                }

            case PRE_LOWLANDS: // 1б — баф від 1а, дебаф від 2а,2б,3а,3б
                switch (attackerTerrain) {
                    case LOWLANDS:
                        return BUFF_SMALL;
                    case PLAINS:
                    case PLAINS_ALT:
                    case PRE_HIGHLANDS:
                    case HIGHLANDS:
                        return DEBUFF;
                    default:
                        return NEUTRAL;
                }

            case PLAINS: // 2а — баф від 1а,1б
                switch (attackerTerrain) {
                    case LOWLANDS:
                    case PRE_LOWLANDS:
                        return BUFF_SMALL;
                    default:
                        return NEUTRAL;
                }

            case PLAINS_ALT: // 2б — баф від 1а,1б
                switch (attackerTerrain) {
                    case LOWLANDS:
                    case PRE_LOWLANDS:
                        return BUFF_SMALL;
                    default:
                        return NEUTRAL;
                }

            case PRE_HIGHLANDS: // 3а — баф від 1б,2а,2б
                switch (attackerTerrain) {
                    case PRE_LOWLANDS:
                    case PLAINS:
                    case PLAINS_ALT:
                        return BUFF_SMALL;
                    default:
                        return NEUTRAL;
                }

            case HIGHLANDS: // 3б — баф від всього
                switch (attackerTerrain) {
                    case HIGHLANDS:
                        return NEUTRAL; // від рівного — нейтрально
                    default:
                        return BUFF_LARGE;
                }

            default: // FOREST, NONE
                return NEUTRAL;
        }
    }
}
