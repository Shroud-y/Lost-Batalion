package io.jababa.lost_batalion.net.commands;

/**
 * Звести виділені гармати в одну батарею.
 *
 * <p>Несе лише список id: КОГО зводити з ким, вирішує симуляція — і мусить
 * вирішувати сама, бо вибір ведучої гармати має бути однаковим на всіх
 * клієнтах, а клієнт міг би прислати будь-який. Наказ не миттєвий: гармати
 * спершу сходяться пошуком шляху й зливаються лише коли зійшлись.
 */
public class MergeArtilleryCommand extends GameCommand {

    public int[] unitIds;

    public MergeArtilleryCommand() {}

    public MergeArtilleryCommand(int playerId, int[] unitIds) {
        super(playerId);
        this.unitIds = unitIds;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.mergeArtillery(playerId, unitIds);
    }
}
