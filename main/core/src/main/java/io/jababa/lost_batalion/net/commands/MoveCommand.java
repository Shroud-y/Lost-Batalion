package io.jababa.lost_batalion.net.commands;

/** ПКМ по землі: виділені юніти йдуть у точку і шикуються сіткою. */
public class MoveCommand extends GameCommand {

    public int[] unitIds;
    /** Ціль у fixed-point (Q47.16). */
    public long targetX, targetY;

    public MoveCommand() {}

    public MoveCommand(int playerId, int[] unitIds, long targetX, long targetY) {
        super(playerId);
        this.unitIds = unitIds;
        this.targetX = targetX;
        this.targetY = targetY;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.moveUnits(playerId, unitIds, targetX, targetY);
    }
}
