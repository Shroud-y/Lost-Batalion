package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;

/**
 * Артснаряд у польоті — частина СТАНУ гри, а не візуалу.
 *
 * <p>Раніше політ жив усередині {@code ArtilleryStrikeEffect} і рухався за
 * часом кадру, а урон наносив колбеком у момент прильоту. Тобто момент
 * влучання залежав від FPS: на 144 Гц снаряд долітав на іншому тіку, ніж на
 * 60. Зовні це виглядало як «у нього артилерія б'є точніше», а насправді це
 * був десинхрон у чистому вигляді.
 *
 * <p>Тепер політ рахується в тіках і в fixed-point, а ефект лише малює те, що
 * порахувала симуляція.
 */
public class ArtilleryShell {

    /** Швидкість снаряду за тік (Q47.16). */
    private static final long SPEED_PER_TICK =
        Fixed.divInt(Fixed.fromFloat(Artillery.SHELL_SPEED_PX), TickRate.TICKS_PER_SECOND);

    public final long fromX, fromY;
    public final long targetX, targetY;
    public final long splashRadius;
    public final long damage;

    /** Скільки тіків триває політ загалом і скільки лишилось. */
    public final int totalTicks;
    private int ticksLeft;

    /** Знято з обліку після прильоту. */
    private boolean impacted;

    public ArtilleryShell(long fromX, long fromY, long targetX, long targetY,
                          long splashRadius, long damage) {
        this.fromX  = fromX;  this.fromY  = fromY;
        this.targetX = targetX; this.targetY = targetY;
        this.splashRadius = splashRadius;
        this.damage       = damage;

        long dist = Fixed.dst(fromX, fromY, targetX, targetY);
        // Округлення вгору: снаряд не має долітати «трохи раніше» за рахунок
        // відкидання дробової частини — інакше на коротких дистанціях приліт
        // стався б у тому ж тіку, що й постріл.
        int ticks = (int) ((dist + SPEED_PER_TICK - 1) / SPEED_PER_TICK);
        this.totalTicks = Math.max(1, ticks);
        this.ticksLeft  = this.totalTicks;
    }

    /**
     * Відновлення зі знімка: усе, включно з уже пройденим шляхом.
     *
     * <p>Окремий конструктор, а не сеттери, бо політ описується парою
     * {@code totalTicks}/{@code ticksLeft}, і перерахувати їх із дистанції
     * після відновлення не можна — снаряд уже частково пролетів.
     */
    public ArtilleryShell(long fromX, long fromY, long targetX, long targetY,
                          long splashRadius, long damage, int totalTicks, int ticksLeft) {
        this.fromX = fromX; this.fromY = fromY;
        this.targetX = targetX; this.targetY = targetY;
        this.splashRadius = splashRadius;
        this.damage       = damage;
        this.totalTicks   = Math.max(1, totalTicks);
        this.ticksLeft    = ticksLeft;
    }

    /** @return true, якщо саме цього тіку снаряд влучив */
    public boolean tick() {
        if (impacted) return false;
        ticksLeft--;
        if (ticksLeft <= 0) {
            impacted = true;
            return true;
        }
        return false;
    }

    public boolean hasImpacted() { return impacted; }

    /** Скільки тіків лишилось летіти — входить у checksum. */
    public int ticksLeft() { return ticksLeft; }

    /** Позиція снаряда у Q47.16 на початок поточного тіку. */
    public long currentX() { return fromX + Fixed.mul(targetX - fromX, progress()); }
    public long currentY() { return fromY + Fixed.mul(targetY - fromY, progress()); }

    private long progress() {
        int done = totalTicks - ticksLeft;
        return Fixed.divInt(Fixed.fromInt(done), totalTicks);
    }

    /** Кут польоту в градусах — тільки для повороту спрайту. */
    public float angleDegrees() {
        return (float) Math.toDegrees(Math.atan2(Fixed.toFloat(targetY - fromY),
                                                 Fixed.toFloat(targetX - fromX)));
    }
}
