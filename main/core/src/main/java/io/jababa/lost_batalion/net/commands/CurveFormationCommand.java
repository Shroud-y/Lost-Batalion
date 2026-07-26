package io.jababa.lost_batalion.net.commands;

/**
 * Намальована мишею крива формації.
 *
 * <p>Передається вже проріджений набір точок, а не сирий слід курсора: кількість
 * семплів у сирому сліді залежить від FPS малювальника, а FPS у кожного свій —
 * якби прорідження робив кожен клієнт сам, юніти стали б у різні місця.
 * Прорідження виконується один раз на боці автора, до відправки.
 */
public class CurveFormationCommand extends GameCommand {

    public int[] unitIds;
    /** Пари координат підряд: x0, y0, x1, y1, … у fixed-point (Q47.16). */
    public long[] pointsXY;

    public CurveFormationCommand() {}

    public CurveFormationCommand(int playerId, int[] unitIds, long[] pointsXY) {
        super(playerId);
        this.unitIds  = unitIds;
        this.pointsXY = pointsXY;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.moveUnitsToCurve(playerId, unitIds, pointsXY);
    }
}
