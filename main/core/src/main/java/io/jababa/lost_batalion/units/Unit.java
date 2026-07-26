package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;

/**
 * Юніт: увесь його стан — цілочисельний.
 *
 * <h3>Чому тут немає жодного float</h3>
 * Lockstep вимагає побітово однакового результату на всіх машинах. Java з 17
 * версії рахує {@code + - * /} строго за IEEE, тож проста арифметика вже
 * детермінована — але {@code Math.sin}, {@code Math.atan2} і {@code MathUtils.*}
 * допускають розбіжність в 1-2 ulp між платформами, а libGDX-івський
 * {@code Vector2.dst} веде саме туди. Один ulp на тіку при 40 тіках/с — це
 * юніт, що через хвилину стоїть в іншому місці. Тому вся математика юніта
 * переведена на {@link Fixed} (Q47.16 у {@code long}), а float лишився
 * виключно на межі рендеру: {@link #renderX(float)} / {@link #renderY(float)}.
 *
 * <h3>Час теж цілий</h3>
 * Таймери рахуються в ТІКАХ, а не в секундах. Причина не лише в float: 0.025 с
 * (крок тіку) непредставна ані у float, ані у Q47.16, тож будь-яке накопичення
 * секунд накопичує і похибку. Саме через це перезарядка на 1.2 с давала 49
 * тіків замість 48. У тіках такої проблеми не існує за побудовою.
 *
 * <p>Домовленість про імена: поля з сирими значеннями {@link Fixed} не мають
 * суфікса, бо їх тут більшість; усе, що в тіках, названо {@code *Ticks}.
 */
public abstract class Unit {

    /**
     * Стабільний ідентифікатор, однаковий на всіх клієнтах.
     *
     * <p>Команди по мережі не можуть посилатись на об'єкти — лише на числа. Id
     * роздає {@link UnitManager} у порядку створення, а створення однакове
     * скрізь, бо початкова розстановка детермінована. {@code -1} означає, що
     * юніт ще не доданий у менеджер.
     */
    public int id = -1;

    public final Team team;

    // ── Характеристики (Q47.16) ───────────────────────────────────────────

    public long maxHp;
    public long hp;

    /**
     * Швидкість у світових одиницях за ТІК, а не за секунду.
     *
     * <p>Перерахунок робиться один раз при створенні: множити щотіку на
     * непредставний крок 0.025 означало б накопичувати похибку. 20 одиниць/с
     * дають рівно 0.5 за тік — точне число у Q47.16.
     */
    public long speedPerTick;

    public long damage;
    public long attackRange;

    /** Базовий захист — віднімається від вхідного damage перед множником. */
    public long defense = 0;

    public long sightRange    = Fixed.fromInt(520);
    public long stealthRating = 0;

    // ── Час у тіках ───────────────────────────────────────────────────────

    /** Скільки тіків між пострілами. */
    public int attackCooldownTicks;
    /** Скільки тіків лишилось до готовності. */
    public int attackTimerTicks = 0;

    // ── Позиція ───────────────────────────────────────────────────────────

    /** Позиція, Q47.16. */
    public long x, y;

    private long targetX, targetY;
    private boolean moving = false;

    /**
     * Позиція на початок поточного тіку. Рендер малює юніта між нею і поточною,
     * інакше при 144 Гц рух виглядав би ривками по 25 мс.
     * До симуляції не належить і в checksum не входить.
     */
    private long prevX, prevY;

    public boolean selected = false;
    public boolean alive    = true;

    /**
     * Чи бачить цього юніта кожна зі сторін; індекс — {@link Team#ordinal()}.
     *
     * <p>Раніше тут стояв єдиний прапорець {@code visibleToPlayer}: в одиночній
     * грі спостерігач був завжди один. У матчі 1v1 сторін дві, і видимість
     * входить у симуляцію (наказ атаки знімається, коли ціль зникла з очей),
     * тому вона мусить рахуватись для обох — інакше стан у гостя і хоста
     * розійшовся б уже на першому пострілі з-за пагорба.
     */
    private final boolean[] visibleTo = { true, true };

    /** Чи бачить цього юніта сторона {@code observer}. Свої видимі завжди. */
    public boolean isVisibleTo(Team observer) {
        return team == observer || visibleTo[observer.ordinal()];
    }

    public void setVisibleTo(Team observer, boolean visible) {
        visibleTo[observer.ordinal()] = visible;
    }

    protected Unit(Team team) { this.team = team; }

    /** Поставити юніта в точку (Q47.16). Використовується лише при створенні. */
    protected void setPosition(long rawX, long rawY) {
        x = rawX; y = rawY;
        targetX = rawX; targetY = rawY;
        prevX = rawX; prevY = rawY;
    }

    public void moveTo(long rawX, long rawY) {
        targetX = rawX;
        targetY = rawY;
        moving  = true;
    }

    /** Запам'ятати, звідки юніт стартував цей тік. Викликається перед {@link #tick}. */
    public void beginTick() {
        prevX = x;
        prevY = y;
    }

    /**
     * Поріг «дійшов»: ближче за це до цілі юніт просто стає в неї.
     *
     * <p>Потрібен, бо крок за тік скінченний і рівно в точку майже ніколи не
     * потрапляє — без порога юніт вічно смикався б навколо цілі.
     */
    private static final long ARRIVE_EPSILON = Fixed.fromInt(2);

    /**
     * Один крок симуляції.
     *
     * @param terrainSpeedMultiplier множник швидкості від місцевості (Q47.16,
     *                               {@link Fixed#ONE} = без змін)
     */
    public void tick(long terrainSpeedMultiplier) {
        if (!alive) return;

        // Один раз за тік. У найпершій версії таймер зменшувався двічі за
        // виклик, через що перезарядка йшла вдвічі швидше за задану.
        if (attackTimerTicks > 0) attackTimerTicks--;

        if (moving) {
            long dx = targetX - x, dy = targetY - y;
            long dist = Fixed.length(dx, dy);

            if (dist < ARRIVE_EPSILON) {
                x = targetX;
                y = targetY;
                moving = false;
            } else {
                long step = Fixed.mul(speedPerTick, terrainSpeedMultiplier);
                // Крок, що перестрибнув би ціль, обрізається до неї — інакше
                // повільна ціль і швидкий юніт дають нескінченне коливання.
                if (step >= dist) {
                    x = targetX;
                    y = targetY;
                    moving = false;
                } else {
                    x += Fixed.mul(Fixed.div(dx, dist), step);
                    y += Fixed.mul(Fixed.div(dy, dist), step);
                }
            }
        }
    }

    // ── Межа рендеру: єдине місце, де з'являється float ───────────────────

    /** Позиція для малювання: між станом на початок тіку і поточним. */
    public float renderX(float alpha) {
        return Fixed.toFloat(prevX) + (Fixed.toFloat(x) - Fixed.toFloat(prevX)) * alpha;
    }

    public float renderY(float alpha) {
        return Fixed.toFloat(prevY) + (Fixed.toFloat(y) - Fixed.toFloat(prevY)) * alpha;
    }

    /** Позиція у світових одиницях для UI-запитів (клік, підказка). */
    public float worldX() { return Fixed.toFloat(x); }
    public float worldY() { return Fixed.toFloat(y); }

    public boolean isMoving() { return moving; }

    // ── Урон ──────────────────────────────────────────────────────────────

    /** Проста атака без урахування місцевості. */
    public boolean takeDamage(long rawDamage) {
        long effective = Fixed.max(0, rawDamage - defense);
        hp -= effective;
        if (hp <= 0) { hp = 0; alive = false; }
        return !alive;
    }

    /**
     * Атака з урахуванням місцевості.
     *
     * @param rawDamage         базовий damage атакуючого (Q47.16)
     * @param defenseMultiplier множник захисту від TerrainCombatModifier
     *                          (> ONE = захищений, < ONE = вразливий)
     */
    public boolean takeDamageWithTerrain(long rawDamage, long defenseMultiplier) {
        long afterArmor = Fixed.max(0, rawDamage - defense);
        // defenseMultiplier > 1 → ділимо (захист більший)
        // defenseMultiplier < 1 → ціль вразлива, урон більший
        long effective = Fixed.div(afterArmor, defenseMultiplier);
        hp -= effective;
        if (hp <= 0) { hp = 0; alive = false; }
        return !alive;
    }

    /** Базова атака (без місцевості). */
    public boolean attack(Unit target) {
        if (attackTimerTicks > 0) return false;
        target.takeDamage(damage);
        attackTimerTicks = attackCooldownTicks;
        return true;
    }

    /** Атака з урахуванням місцевості. Викликається з CombatManager. */
    public boolean attackWithTerrain(Unit target, long defenseMultiplier) {
        if (attackTimerTicks > 0) return false;
        target.takeDamageWithTerrain(damage, defenseMultiplier);
        attackTimerTicks = attackCooldownTicks;
        return true;
    }

    public void stopMoving() {
        targetX = x;
        targetY = y;
        moving  = false;
    }

    // ── Знімок стану (ресинк) ─────────────────────────────────────────────
    //
    // Пишеться і читається лише SimulationSnapshot. Порядок полів — частина
    // формату: переставити рядки місцями означає, що знімок від однієї збірки
    // розпакується в іншій як каша. Підкласи дописують СВОЇ поля після
    // super-виклику і читають у тому ж порядку.

    public void writeSnapshot(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(id);
        out.writeLong(x);
        out.writeLong(y);
        out.writeLong(targetX);
        out.writeLong(targetY);
        out.writeBoolean(moving);
        out.writeLong(hp);
        out.writeInt(attackTimerTicks);
        out.writeBoolean(alive);
        out.writeBoolean(visibleTo[0]);
        out.writeBoolean(visibleTo[1]);
    }

    public void readSnapshot(java.io.DataInputStream in) throws java.io.IOException {
        id               = in.readInt();
        x                = in.readLong();
        y                = in.readLong();
        targetX          = in.readLong();
        targetY          = in.readLong();
        moving           = in.readBoolean();
        hp               = in.readLong();
        attackTimerTicks = in.readInt();
        alive            = in.readBoolean();
        visibleTo[0]     = in.readBoolean();
        visibleTo[1]     = in.readBoolean();

        // Інтерполяція рендеру після ресинку стартує з нової позиції: інакше
        // юніт «прилетів» би через пів карти за один кадр.
        prevX = x;
        prevY = y;
    }

    public boolean canAttack() { return attackTimerTicks <= 0; }

    /** Частка здоров'я 0..1 — тільки для HP-бару. */
    public float hpRatio() { return maxHp <= 0 ? 0f : (float) ((double) hp / (double) maxHp); }

    /** Розмір юніта у світових одиницях (Q47.16). */
    public abstract long getSize();

    /** Радіус влучання (Q47.16). */
    public long getHitRadius() { return getSize() >> 1; }

    /** Розмір для рендеру. */
    public float getSizePx() { return Fixed.toFloat(getSize()); }

    public abstract String getTexturePath();
}
