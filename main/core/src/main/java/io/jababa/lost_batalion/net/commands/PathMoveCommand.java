package io.jababa.lost_batalion.net.commands;

/**
 * Подвійний ПКМ по землі: юніти йдуть найшвидшим шляхом, обходячи ліс,
 * річки й підйоми, якщо обхід виходить дешевшим за прямий прохід.
 *
 * <p>Несе рівно те саме, що й звичайний {@link MoveCommand} — точку. Маршрут
 * рахує кожен клієнт самостійно: він однозначно виводиться з масок карти,
 * тож слати його по мережі було б і дорожче, і небезпечніше.
 */
public class PathMoveCommand extends GameCommand {

    public int[] unitIds;
    /** Ціль у fixed-point (Q47.16). */
    public long targetX, targetY;

    public PathMoveCommand() {}

    public PathMoveCommand(int playerId, int[] unitIds, long targetX, long targetY) {
        super(playerId);
        this.unitIds = unitIds;
        this.targetX = targetX;
        this.targetY = targetY;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.pathMoveUnits(playerId, unitIds, targetX, targetY);
    }
}
