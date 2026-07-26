package io.jababa.lost_batalion.net.commands;

/** ПКМ по ворожому юніту: виділені юніти отримують наказ атаки. */
public class AttackCommand extends GameCommand {

    public int[] unitIds;
    /** Стабільний id цілі. Якщо ціль загине до тіку виконання — наказ ігнорується. */
    public int targetUnitId;

    public AttackCommand() {}

    public AttackCommand(int playerId, int[] unitIds, int targetUnitId) {
        super(playerId);
        this.unitIds      = unitIds;
        this.targetUnitId = targetUnitId;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.orderAttack(playerId, unitIds, targetUnitId);
    }
}
