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
     * Маршрут: пари координат підряд (x0, y0, x1, y1, …) у Q47.16.
     *
     * <p>Порожній для звичайного наказу — там юніт іде прямо в точку. Маршрут
     * з'являється лише за наказом пошуку шляху; юніт іде від точки до точки, і
     * коли доходить останньої, {@link #moving} гасне як завжди.
     *
     * <p>Це СТАН гри: він входить у checksum і в знімок для ресинку. Інакше
     * після ресинку юніти пішли б навпростець, а їхні супротивники бачили б
     * обхід — і матч розійшовся б удруге.
     */
    private long[] path;
    private int    pathIndex;

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
        path      = null;      // прямий наказ скасовує маршрут
        pathIndex = 0;
    }

    /**
     * Іти заданим маршрутом, а в кінці стати в {@code finalX/finalY}.
     *
     * <p>Остання точка маршруту й особисте місце юніта в строю — різні речі:
     * група йде спільним шляхом, але розходиться по місцях лише прийшовши.
     *
     * @param waypoints пари координат підряд; null або порожній → звичайний рух
     */
    public void followPath(long[] waypoints, long finalX, long finalY) {
        if (waypoints == null || waypoints.length < 2) {
            moveTo(finalX, finalY);
            return;
        }
        path   = waypoints;
        moving = true;
        this.finalX = finalX;
        this.finalY = finalY;

        // Починаємо з найближчої до себе точки, а не з нульової. Маршрут
        // будується від центроїда групи, тож для юніта з переднього краю
        // перші точки лежать ПОЗАДУ — без цього він спершу йшов би назад.
        pathIndex = nearestWaypoint(waypoints);
        targetX = waypoints[pathIndex * 2];
        targetY = waypoints[pathIndex * 2 + 1];
    }

    private int nearestWaypoint(long[] waypoints) {
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i * 2 + 1 < waypoints.length; i++) {
            long d = Fixed.dstSq(x, y, waypoints[i * 2], waypoints[i * 2 + 1]);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /** Куди юніт стане, коли пройде весь маршрут. */
    private long finalX, finalY;

    public long getTargetX() { return targetX; }
    public long getTargetY() { return targetY; }

    public boolean hasPath()      { return path != null && pathIndex * 2 < path.length; }
    public int     getPathIndex() { return pathIndex; }
    public long[]  getPath()      { return path; }

    /** Запам'ятати, звідки юніт стартував цей тік. Викликається перед {@link #tick}. */
    public void beginTick() {
        prevX = x;
        prevY = y;
    }

    /**
     * Радіус, у якому проміжна точка маршруту вважається пройденою.
     *
     * <p>Набагато більший за {@link #ARRIVE_EPSILON} — і це не недбалість.
     * Група йде СПІЛЬНИМ маршрутом, тобто всі націлені на ту саму точку. Із
     * жорстким порогом у 2 одиниці вони збиваються в купу навколо неї,
     * розштовхуються і ніхто не підходить достатньо близько, щоб перемкнутись
     * на наступну точку: група застрягає на місці назавжди. Проміжну точку
     * досить ПРОМИНУТИ, а не стати в неї.
     */
    private static final long WAYPOINT_RADIUS = Fixed.fromInt(14);

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

        if (!moving) return;

        // Бюджет кроку на цей тік. Він витрачається, а не «застосовується до
        // цілі»: інакше перемикання на наступну точку маршруту коштувало б
        // юніту зайвий крок або, навпаки, дарувало б йому цілий відрізок.
        long budget = Fixed.mul(speedPerTick, terrainSpeedMultiplier);

        for (int guard = 0; moving && guard <= MAX_WAYPOINTS_PER_TICK; guard++) {
            long dx = targetX - x, dy = targetY - y;
            long dist = Fixed.length(dx, dy);

            if (hasPath()) {
                // Проміжну точку треба ПРОМИНУТИ, а не стати в неї. Стрибок у
                // точку зсував юніта на цілий WAYPOINT_RADIUS за тік — при
                // швидкості 0.5/тік це до 28 швидкостей, і саме так виглядало
                // «юніти смикаються й летять» при русі з пошуком шляху.
                if (dist < WAYPOINT_RADIUS) {
                    if (!advanceWaypoint()) moving = false;
                    continue;   // бюджет не витрачено — йдемо далі цього ж тіку
                }
            }

            if (budget <= 0) break;

            if (budget >= dist) {
                // Крок дістає до цілі — стаємо в неї, решта бюджету йде далі.
                // Окремий поріг «дійшов» тут не потрібен: саме ця гілка й
                // ставить юніта рівно в точку, і робить це не раніше, ніж
                // до неї справді лишився один крок. Раніше тут стояв
                // ARRIVE_EPSILON = 2, і кожен рух завершувався стрибком на
                // цілих дві одиниці — вчетверо більше за крок піхоти.
                budget -= dist;
                x = targetX;
                y = targetY;
                if (!advanceWaypoint()) { moving = false; break; }
            } else {
                x += Fixed.mul(Fixed.div(dx, dist), budget);
                y += Fixed.mul(Fixed.div(dy, dist), budget);
                break;
            }
        }
    }

    /**
     * Скільки точок маршруту юніт може перемкнути за один тік.
     *
     * <p>Перемикання без витрати бюджету скінченне за побудовою — маршрут
     * колись закінчиться, — але цикл, вихід з якого залежить від даних, у
     * симуляції лишати не можна. Точки стоять по сітці 8 px, а крок за тік
     * менший за одиницю, тож реально їх перемикається одна-дві.
     */
    private static final int MAX_WAYPOINTS_PER_TICK = 64;

    /**
     * Перейти до наступної точки маршруту.
     *
     * @return true, якщо є куди йти далі; false — маршрут пройдено
     */
    private boolean advanceWaypoint() {
        if (path == null) return false;

        pathIndex++;
        if (pathIndex * 2 + 1 < path.length) {
            targetX = path[pathIndex * 2];
            targetY = path[pathIndex * 2 + 1];
            return true;
        }

        // Маршрут скінчився — лишається стати на своє місце в строю.
        path      = null;
        pathIndex = 0;
        if (finalX != x || finalY != y) {
            targetX = finalX;
            targetY = finalY;
            return true;
        }
        return false;
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
        path      = null;
        pathIndex = 0;
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

        out.writeLong(finalX);
        out.writeLong(finalY);
        out.writeInt(pathIndex);
        out.writeInt(path == null ? 0 : path.length);
        if (path != null) for (int i = 0; i < path.length; i++) out.writeLong(path[i]);
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

        finalX    = in.readLong();
        finalY    = in.readLong();
        pathIndex = in.readInt();
        int pathLength = in.readInt();
        if (pathLength <= 0) {
            path = null;
        } else {
            path = new long[pathLength];
            for (int i = 0; i < pathLength; i++) path[i] = in.readLong();
        }

        // Інтерполяція рендеру після ресинку стартує з нової позиції: інакше
        // юніт «прилетів» би через пів карти за один кадр.
        prevX = x;
        prevY = y;
    }

    public boolean canAttack() { return attackTimerTicks <= 0; }

    /** Частка здоров'я 0..1 — тільки для HP-бару. */
    public float hpRatio() { return maxHp <= 0 ? 0f : (float) ((double) hp / (double) maxHp); }

    /**
     * Розмір юніта у світових одиницях (Q47.16).
     *
     * <p>Суфікс {@code Fixed} тут не з любові до довгих імен. Раніше метод
     * звався {@code getSize()}, і рендер написав {@code float size = u.getSize()}
     * — Java мовчки розширила {@code long} у {@code float}, юніт розміром 10
     * почав малюватись розміром 655360 і закрив собою всю карту. Компілятор
     * такого не ловить, тому ловить назва: {@code sizeFixed} у float-вираз
     * рефлекторно не пишуть.
     */
    public abstract long sizeFixed();

    /** Радіус влучання (Q47.16). */
    public long hitRadiusFixed() { return sizeFixed() >> 1; }

    /** Розмір у пікселях — єдине, що можна брати в рендер. */
    public float getSizePx() { return Fixed.toFloat(sizeFixed()); }

    public abstract String getTexturePath();
}
