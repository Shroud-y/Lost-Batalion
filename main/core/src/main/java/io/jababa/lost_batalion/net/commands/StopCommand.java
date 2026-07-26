package io.jababa.lost_batalion.net.commands;

/** Скасувати накази атаки й зупинити юнітів на місці. */
public class StopCommand extends GameCommand {

    public int[] unitIds;

    public StopCommand() {}

    public StopCommand(int playerId, int[] unitIds) {
        super(playerId);
        this.unitIds = unitIds;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.stopUnits(playerId, unitIds);
    }
}
