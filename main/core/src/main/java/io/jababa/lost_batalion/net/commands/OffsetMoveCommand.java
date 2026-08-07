package io.jababa.lost_batalion.net.commands;

/**
 * Shift + протяг ПКМ: усі виділені йдуть на ОДИН і той самий зсув.
 *
 * <p>Відрізняється від {@link MoveCommand} тим, що цілі немає взагалі. Там
 * загін збирається в сітку навколо точки — тобто стрій розсипається і
 * складається наново; тут кожен робить той самий крок у той самий бік, і
 * взаємне розташування зберігається точно. Це «посунься на сто кроків
 * праворуч», а не «збирайся отам».
 *
 * <p>По мережі їде САМЕ ЗСУВ, а не порахований список цілей. Позиції юнітів
 * на тіку виконання однакові в усіх (на тому й тримається lockstep), тож
 * кожен клієнт додасть зсув до тих самих чисел і отримає ті самі цілі. Слати
 * готові цілі означало б везти по два числа на юніта замість двох на весь
 * загін, і при цьому довіряти відправнику те, що приймач може порахувати сам.
 */
public class OffsetMoveCommand extends GameCommand {

    public int[] unitIds;
    /** Зсув у fixed-point (Q47.16). */
    public long dx, dy;

    public OffsetMoveCommand() {}

    public OffsetMoveCommand(int playerId, int[] unitIds, long dx, long dy) {
        super(playerId);
        this.unitIds = unitIds;
        this.dx = dx;
        this.dy = dy;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.offsetMoveUnits(playerId, unitIds, dx, dy);
    }
}
