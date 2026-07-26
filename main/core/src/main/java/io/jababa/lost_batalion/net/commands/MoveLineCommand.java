package io.jababa.lost_batalion.net.commands;

/** ПКМ-драг: виділені юніти шикуються в лінію між двома точками. */
public class MoveLineCommand extends GameCommand {

    public int[] unitIds;
    /** Кінці лінії у fixed-point (Q47.16). */
    public long x1, y1, x2, y2;

    public MoveLineCommand() {}

    public MoveLineCommand(int playerId, int[] unitIds, long x1, long y1, long x2, long y2) {
        super(playerId);
        this.unitIds = unitIds;
        this.x1 = x1; this.y1 = y1;
        this.x2 = x2; this.y2 = y2;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.moveUnitsToLine(playerId, unitIds, x1, y1, x2, y2);
    }
}
