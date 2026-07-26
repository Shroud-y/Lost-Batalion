package io.jababa.lost_batalion.net.commands;

/**
 * Те, що команда може зробити з симуляцією.
 *
 * <p>Команди — це чисті дані, які їздять по мережі; вони не знають ні про
 * UnitManager, ні про CombatManager. Симуляція реалізує цей інтерфейс, і саме
 * вона вирішує, як наказ перетворюється на зміну стану. Завдяки цьому шар
 * повідомлень не тягне за собою залежність від ігрових класів, а виконання
 * команди легко підмінити в тестах детермінізму.
 *
 * <p>Усі координати — сирі значення {@link io.jababa.lost_batalion.math.Fixed}
 * (Q47.16 у {@code long}). Жодних {@code float} на цій межі.
 *
 * <p>{@code unitIds} — стабільні ідентифікатори юнітів, однакові на всіх клієнтах.
 * Реалізація мусить мовчки ігнорувати id, яких уже немає (юніт загинув між
 * віддачею наказу і тіком його виконання — це нормальна гонка при input delay),
 * і юнітів, що не належать {@code playerId} — інакше клієнт зможе командувати
 * чужою армією, підмінивши повідомлення.
 */
public interface CommandContext {

    /** Наказ рухатись у точку; юніти шикуються сіткою навколо неї. */
    void moveUnits(int playerId, int[] unitIds, long targetX, long targetY);

    /** Наказ шикуватись у лінію між двома точками (ПКМ-драг). */
    void moveUnitsToLine(int playerId, int[] unitIds, long x1, long y1, long x2, long y2);

    /**
     * Наказ шикуватись уздовж намальованої кривої.
     *
     * @param pointsXY пари координат підряд: x0, y0, x1, y1, … (довжина завжди парна)
     */
    void moveUnitsToCurve(int playerId, int[] unitIds, long[] pointsXY);

    /** Наказ атакувати конкретного юніта. */
    void orderAttack(int playerId, int[] unitIds, int targetUnitId);

    /** Скасування наказів і зупинка на місці. */
    void stopUnits(int playerId, int[] unitIds);
}
