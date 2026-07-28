package io.jababa.lost_batalion.net.commands;

/**
 * Замовлення війська: тип і точка, куди воно має прийти.
 *
 * <p>Юніт з'являється не тут і не одразу — команда лише ставить замовлення в
 * чергу й списує золото. Через дві секунди військо виходить із краю карти й
 * вирушає в цю точку; до того замовлення можна зняти
 * {@link CancelSpawnCommand}.
 *
 * <p>Тип їде номером ({@code UnitType.ordinal()}), а не назвою класу: по мережі
 * ходять тільки числа.
 */
public class SpawnCommand extends GameCommand {

    /** {@code UnitType.ordinal()}. */
    public int unitType;

    /** Куди вести військо, Q47.16. */
    public long targetX, targetY;

    public SpawnCommand() {}

    public SpawnCommand(int playerId, int unitType, long targetX, long targetY) {
        super(playerId);
        this.unitType = unitType;
        this.targetX  = targetX;
        this.targetY  = targetY;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.spawnUnit(playerId, unitType, targetX, targetY);
    }
}
