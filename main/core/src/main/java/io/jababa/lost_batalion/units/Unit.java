package io.jababa.lost_batalion.units;

import com.badlogic.gdx.math.MathUtils;
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
     * Особистий зсув юніта відносно спільного маршруту (Q47.16).
     *
     * <p>Маршрут будується один на всю групу, але йти всім в ОДНУ точку не
     * можна: біля кожної проміжної точки юніти збивались у купу, розштовхування
     * крутило пари одне навколо одного, і лише потім група рушала далі. Тому
     * кожен іде своєю «смугою» — маршрутом, зсунутим на його місце в строю.
     * Зсув сталий на весь шлях, тож строй складається САМ по дорозі, а не
     * збирається на місці перед виходом.
     *
     * <p>Це стан симуляції — пишеться у знімок разом із маршрутом.
     */
    private long pathOffX, pathOffY;

    /**
     * Позиція на початок поточного тіку. Рендер малює юніта між нею і поточною,
     * інакше при 144 Гц рух виглядав би ривками по 25 мс.
     * До симуляції не належить і в checksum не входить.
     */
    private long prevX, prevY;

    public boolean selected = false;
    public boolean alive    = true;

    // ── Напрямок ──────────────────────────────────────────────────────────

    /**
     * Куди юніт дивиться: радіани Q47.16, 0 = вправо (+X), проти годинникової.
     *
     * <p>Це СТАН симуляції, а не рендер: поки гармата не довернулась, вона не
     * їде і не стріляє, тож від кута залежить момент пострілу. Входить у
     * checksum і в знімок.
     */
    public long facing = 0;

    /**
     * Швидкість розвороту в радіанах за тік (Q47.16).
     *
     * <p>{@code 0} означає «напрямку немає»: піхота розвертається миттєво і
     * взагалі не малюється повернутою. Перевіряти напрямок дорого лише там,
     * де він щось значить — тому за замовчуванням механіка вимкнена.
     */
    public long turnRatePerTick() { return 0; }

    public boolean hasFacing() { return turnRatePerTick() > 0; }

    /**
     * Допуск «дивиться куди треба» — трохи більший за крок розвороту не
     * потрібен, але й нуль не годиться: наближення atan2 має свою похибку,
     * і без допуску юніт міг би довертатись вічно.
     */
    private static final long FACING_EPSILON = Fixed.fromFloat(0.02f);

    /**
     * Довернути на кут {@code desired} на один тік.
     *
     * @return true, якщо юніт уже дивиться в потрібний бік
     */
    public boolean turnTo(long desired) {
        if (!hasFacing()) { facing = Fixed.wrapAngle(desired); return true; }

        long diff = Fixed.angleDiff(desired, facing);
        if (Fixed.abs(diff) <= FACING_EPSILON) { facing = Fixed.wrapAngle(desired); return true; }

        long step = Fixed.min(turnRatePerTick(), Fixed.abs(diff));
        facing = Fixed.wrapAngle(facing + (diff > 0 ? step : -step));
        return false;
    }

    /** Те саме, але ціль задана точкою. Точка «під собою» вважається досягнутою. */
    public boolean turnToward(long tx, long ty) {
        long dx = tx - x, dy = ty - y;
        if (dx == 0 && dy == 0) return true;
        return turnTo(Fixed.atan2(dy, dx));
    }

    /** Чи дивиться юніт на точку зараз (без спроби довернутись). */
    public boolean isFacingPoint(long tx, long ty) {
        if (!hasFacing()) return true;
        long dx = tx - x, dy = ty - y;
        if (dx == 0 && dy == 0) return true;
        return Fixed.abs(Fixed.angleDiff(Fixed.atan2(dy, dx), facing)) <= FACING_EPSILON;
    }

    /**
     * Кут спрайту в градусах для рендеру: 0 = так, як намальовано у файлі.
     * Спрайт артилерії дивиться вгору, тобто його «нуль» — це +90°.
     */
    public float facingDegrees() {
        return (float) Math.toDegrees(Fixed.toFloat(facing)) + spriteFacingOffsetDeg();
    }

    /** Наскільки картинка вже повернута відносно напрямку «вправо». */
    public float spriteFacingOffsetDeg() { return 0f; }

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
        pathOffX  = 0;
        pathOffY  = 0;
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
        followPath(waypoints, 0, 0, finalX, finalY);
    }

    /**
     * Те саме, але зі зсувом смуги (див. {@link #pathOffX}).
     *
     * @param offX,offY зсув, який додається до КОЖНОЇ точки маршруту
     */
    public void followPath(long[] waypoints, long offX, long offY, long finalX, long finalY) {
        if (waypoints == null || waypoints.length < 2) {
            moveTo(finalX, finalY);
            return;
        }
        path   = waypoints;
        moving = true;
        pathOffX = offX;
        pathOffY = offY;
        this.finalX = finalX;
        this.finalY = finalY;

        pathIndex = joinIndex(waypoints);
        targetX = waypoints[pathIndex * 2]     + pathOffX;
        targetY = waypoints[pathIndex * 2 + 1] + pathOffY;
    }

    /**
     * Наскільки далеко юніт може «підхопити» маршрут одразу, навпростець.
     *
     * <p>Точки лежать по сітці 8 px, а розліт групи — кілька десятків, тож цього
     * вистачає, щоб той, хто вже стоїть попереду інших, не йшов назад. Більше
     * ставити не можна: пряма ділянка виходу на маршрут НЕ перевіряється на
     * прохідність, і з великим порогом юніт зрізав би саме ту перешкоду, заради
     * якої шлях і шукали.
     */
    private static final long JOIN_RADIUS = Fixed.fromInt(48);

    /**
     * З якої точки маршруту юніту починати.
     *
     * <p>Маршрут будується від центроїда групи, тож для юніта з переднього краю
     * перші точки лежать ПОЗАДУ — без відбору він спершу йшов би назад. Тому
     * береться НАЙДАЛІ пройдена точка з тих, що поруч: усе, що група вже
     * фактично минула, просто пропускається. Якщо поруч немає жодної (юніт
     * відстав або стоїть збоку) — найближча, і він доганяє.
     */
    private int joinIndex(long[] waypoints) {
        int points = waypoints.length / 2;

        int  nearest     = 0;
        long nearestDist = Long.MAX_VALUE;

        for (int i = points - 1; i >= 0; i--) {
            long dx = waypoints[i * 2]     + pathOffX - x;
            long dy = waypoints[i * 2 + 1] + pathOffY - y;
            long d  = Fixed.length(dx, dy);
            if (d <= JOIN_RADIUS) return i;     // обхід іде з кінця → це найдальша
            if (d < nearestDist) { nearestDist = d; nearest = i; }
        }
        return nearest;
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

        // Спершу розворот, і тільки потім рух: юніт з обмеженим розворотом
        // (артилерія) не повзе боком — тік, у якому він довертається,
        // витрачається саме на розворот.
        if (hasFacing() && !turnToward(targetX, targetY)) return;

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
            targetX = path[pathIndex * 2]     + pathOffX;
            targetY = path[pathIndex * 2 + 1] + pathOffY;
            return true;
        }

        // Маршрут скінчився — лишається стати на своє місце в строю.
        path      = null;
        pathIndex = 0;
        pathOffX  = 0;
        pathOffY  = 0;
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

    // ── Тремтіння бою (чистий візуал) ─────────────────────────────────────
    //
    // У знімок і в checksum НЕ входить — так само, як відкат гармати. Позиція
    // в симуляції під час бою не тремтить: інакше кожен удар зсував би дальність
    // і хто кого дістає, а два клієнти з різними FPS розійшлись би за секунди.

    /**
     * Скільки секунд тремтіння живе після удару.
     *
     * <p>Довше за будь-який кулдаун (найповільніший — 1.2 с у піхоти): удари
     * йдуть частіше, ніж таймер спливає, тож поки бій триває, юніт тремтить
     * БЕЗПЕРЕРВНО. З коротким таймером виходив пульс — смикнувся, завмер,
     * смикнувся, і саме він читався як шаблон.
     */
    private static final float SHAKE_DURATION = 1.5f;
    /**
     * Хвіст затухання. Гасне лише в останні чверть секунди, а не весь час:
     * плавне згасання від самого удару — це знову той самий пульс.
     */
    private static final float SHAKE_FADE = 0.25f;
    /** Розмах у пікселях. Пів пікселя: це нерв, а не рух. */
    private static final float SHAKE_AMPLITUDE = 0.5f;

    /**
     * Частоти трьох складових.
     *
     * <p>Три, а не одна, і в неспівмірних відношеннях: одна синусоїда дає
     * впізнаване рівне коливання, дві складаються в биття з чутним періодом, і
     * лише три без спільного дільника не повторюються на око взагалі.
     */
    private static final float[] SHAKE_FREQ = { 23.5f, 41.3f, 67.9f };

    /**
     * Приріст фази на кожен id — золотий кут у радіанах.
     *
     * <p>Ірраціональне число тут не для краси: будь-яке раціональне дало б
     * юнітам, чиї id відрізняються на період, однакову фазу, і сусіди в
     * шерензі тремтіли б синхронно.
     */
    private static final float SHAKE_PHASE_STEP = 2.399963f;

    private float shakeTimer;
    /** Власний годинник анімації. Йде за часом КАДРУ, до симуляції не належить. */
    private float shakeClock;

    /** Смикнути юніта: викликається на кожен удар — і по тому, хто б'є, і по цілі. */
    public void kickCombatShake() { shakeTimer = SHAKE_DURATION; }

    /** Просунути тремтіння за часом кадру. */
    public void updateCombatShake(float delta) {
        if (shakeTimer <= 0f) return;
        shakeTimer  = Math.max(0f, shakeTimer - delta);
        shakeClock += delta;
    }

    /** Поточний зсув спрайта по X у пікселях. */
    public float shakeOffsetX() { return shakeOffset(0f); }

    /** ...і по Y. Інша базова фаза — інакше юніт їздив би по діагоналі. */
    public float shakeOffsetY() { return shakeOffset(1.913f); }

    /**
     * Сума трьох неспівмірних синусоїд, поділена на їхню кількість.
     *
     * <p>Не {@code random()}: зсув читається в рендері по кілька разів за кадр
     * (спрайт, а колись і оверлеї), і випадкове число давало б юніту різні
     * позиції в межах одного кадру — тобто не тремтіння, а розрив картинки.
     * Функція від часу повертає те саме значення скільки її не клич.
     */
    private float shakeOffset(float axisPhase) {
        if (shakeTimer <= 0f) return 0f;

        float phase = id * SHAKE_PHASE_STEP + axisPhase;
        float sum   = 0f;
        for (int i = 0; i < SHAKE_FREQ.length; i++) {
            // Фаза кожної складової своя і теж залежить від id — інакше три
            // синусоїди стартували б разом і перший пік був би однаковий у всіх.
            sum += MathUtils.sin(shakeClock * SHAKE_FREQ[i] + phase * (i + 1));
        }

        float fade = Math.min(1f, shakeTimer / SHAKE_FADE);
        return sum / SHAKE_FREQ.length * SHAKE_AMPLITUDE * fade;
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
        pathOffX  = 0;
        pathOffY  = 0;
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

        out.writeLong(facing);
        out.writeLong(finalX);
        out.writeLong(finalY);
        out.writeInt(pathIndex);
        out.writeLong(pushVelX);
        out.writeLong(pushVelY);
        out.writeInt(pushTicks);
        out.writeLong(pathOffX);
        out.writeLong(pathOffY);
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

        facing    = in.readLong();
        finalX    = in.readLong();
        finalY    = in.readLong();
        pathIndex = in.readInt();
        pushVelX  = in.readLong();
        pushVelY  = in.readLong();
        pushTicks = in.readInt();
        pathOffX  = in.readLong();
        pathOffY  = in.readLong();
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

    // ── Поштовх ближнього бою ─────────────────────────────────────────────

    /**
     * Залишок поштовху: зсув за тік і скільки тіків його ще застосовувати.
     *
     * <p>Стан симуляції — входить у знімок. Розтягування по тіках і є вся суть:
     * перша версія зсувала ціль одним стрибком у момент удару, і юніт раз на
     * 0.7 с підскакував на пів корпусу. Тепер той самий зсув розкладений на
     * увесь проміжок до наступного удару, тож ціль рівно повзе, поки її б'ють.
     */
    private long pushVelX, pushVelY;
    private int  pushTicks;

    /**
     * Отримати поштовх: {@code force} одиниць у напрямку {@code (dirX, dirY)},
     * розкладених на {@code ticks} тіків.
     *
     * <p>Новий удар ЗАМІНЮЄ залишок попереднього, а не додається до нього:
     * інакше двоє вершників на одній цілі розганяли б її вдвічі, троє — втричі,
     * і юніт відлітав би тим далі, чим більше на нього налізло.
     *
     * @param dirX,dirY одиничний напрямок (Q47.16)
     */
    public void applyKnockback(long dirX, long dirY, long force, int ticks) {
        if (ticks <= 0 || force <= 0) return;
        long perTick = Fixed.divInt(force, ticks);
        pushVelX  = Fixed.mul(dirX, perTick);
        pushVelY  = Fixed.mul(dirY, perTick);
        pushTicks = ticks;
    }

    /**
     * Просунути поштовх на один тік. Кличе {@code UnitManager} одразу після
     * {@link #tick} — межі карти знає саме він.
     */
    public void advanceKnockback(long mapW, long mapH) {
        if (pushTicks <= 0) return;
        pushTicks--;

        long half = hitRadiusFixed();
        if (mapW > half * 2 && mapH > half * 2) {
            x = Fixed.clamp(x + pushVelX, half, mapW - half);
            y = Fixed.clamp(y + pushVelY, half, mapH - half);
        }
        if (pushTicks == 0) { pushVelX = 0; pushVelY = 0; }
    }

    /**
     * Сила поштовху при влучанні (Q47.16); 0 — юніт нікого не зрушує.
     *
     * <p>Тут, а не {@code instanceof Cavalry} у бою: штовхати вміє не «клас
     * кінноти», а удар з розгону, і наступний такий юніт має отримати це,
     * перевизначивши одне число, а не додавши ще одну гілку в {@code tryAttack}.
     */
    public long knockbackForce() { return 0; }

    /**
     * Чи супроводжується удар пострілом — трасером і звуком.
     *
     * <p>Ближній бій не стріляє: у кінноти немає ні ствола, ні пострілу, і
     * трасер від неї до цілі, яку вона щойно збила конем, читався б як промах
     * рендера.
     */
    public boolean usesRangedFx() { return true; }

    /** Розмір у пікселях — єдине, що можна брати в рендер. */
    public float getSizePx() { return Fixed.toFloat(sizeFixed()); }

    /**
     * Розмір спрайта в пікселях.
     *
     * <p>Окремо від {@link #sizeFixed()} навмисно: той бере участь у симуляції
     * (обрізання цілі по краю карти), і збільшити картинку через нього означало
     * б посунути правила гри заради вигляду. За замовчуванням збігається з
     * розміром юніта — розходяться лише ті, чий спрайт малює більше, ніж
     * займає сама машина.
     */
    public float renderSizePx() { return getSizePx(); }

    /** Радіус влучання в пікселях — для UI-пікінгу (клік/рамка). */
    public float getHitRadiusPx() { return Fixed.toFloat(hitRadiusFixed()); }

    /**
     * Зсув HP-бару по X у пікселях (додатне — правіше). Чисто косметика:
     * у частини спрайтів фігура зміщена від центру, і бар «відлипає».
     */
    public float hpBarOffsetX() { return 0f; }

    public abstract String getTexturePath();
}
