package io.jababa.lost_batalion.units;

import com.badlogic.gdx.math.MathUtils;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;

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

    /**
     * Сторона: за неї рахуються бій, видимість, точки й перемога.
     *
     * <p>Не власник. За одну сторону грає до п'яти гравців, і союзник — це
     * {@code team == моя}, але {@code owner != мій}.
     */
    public final Team team;

    /**
     * Хто цим юнітом КОМАНДУЄ. Номер гравця з ростера матчу.
     *
     * <p>Окремо від {@link #team} саме тому, що союзник бачить юніта своїм за
     * стороною, але наказу йому віддати не може. Уся перевірка стоїть в одному
     * місці — {@code UnitManager.collectOwned}, через яке проходять усі накази.
     */
    public final int owner;

    // ── Характеристики (Q47.16) ───────────────────────────────────────────

    public long maxHp;
    public long hp;

    /**
     * Мораль — та сама шкала, що й здоров'я, і навмисно тим самим типом.
     *
     * <p>Друге здоров'я, яке витрачає не свинець, а страх: юніт із цілим hp і
     * нулем моралі виходить з бою так само надійно, як убитий, тільки живим.
     *
     * <p>Стан симуляції: входить у checksum ({@code C_MORALE}) і в знімок.
     * Рахує його {@code morale.MoraleSystem} — тут лише поля й правила зміни.
     */
    public long maxMorale;
    public long morale;

    /**
     * Скільки тіків поспіль юніт не втрачав моралі.
     *
     * <p>Саме «не втрачав», а не «не стріляв»: спокій — це коли по тобі ніхто
     * не працює. Стрілець, що безкарно розстрілює втікачів, відновлюється, і
     * це правильно.
     */
    public int calmTicks;

    /**
     * Скільки тіків ще триває втеча; {@code 0} — юніт при собі.
     *
     * <p>Один лічильник замість пари «прапорець + таймер»: зламаність і час до
     * повернення — це те саме число, і два поля могли б розійтись.
     */
    public int routTicks;

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

    /**
     * Наскільки юніт зараз не туди дивиться, у радіанах (Q47.16, завжди &ge; 0).
     *
     * <p>Потрібно тому, що {@link #isFacingPoint} відповідає «так/ні» з дуже
     * тісним допуском, а є питання третього роду: чи це СПРАВЖНІЙ розворот, чи
     * лише супровід цілі, що йде. Для артилерії різниця вирішальна — розворот
     * скидає приціл, супровід не має.
     */
    public long facingErrorTo(long tx, long ty) {
        if (!hasFacing()) return 0;
        long dx = tx - x, dy = ty - y;
        if (dx == 0 && dy == 0) return 0;
        return Fixed.abs(Fixed.angleDiff(Fixed.atan2(dy, dx), facing));
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

    protected Unit(Team team, int owner) {
        this.team  = team;
        this.owner = owner;
    }

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

    /**
     * Скільки точок маршруту ще попереду, разом із поточною ціллю.
     *
     * <p>Для показу маршруту виділеного юніта. Сам {@link #getPath()} для
     * цього не годиться: у ньому лежать СИРІ вузли, а юніт іде до вузла плюс
     * зсув смуги ({@link #pathOffX}) — тобто намальована по сирих числах
     * лінія проходила б повз те місце, куди юніт справді прийде.
     */
    public int remainingRoutePoints() {
        if (path == null) return 0;
        int total = path.length / 2;
        return Math.max(0, total - pathIndex);
    }

    /** @param i 0 — поточна ціль, далі за маршрутом */
    public float routeX(int i) {
        return Fixed.toFloat(path[(pathIndex + i) * 2] + pathOffX);
    }

    public float routeY(int i) {
        return Fixed.toFloat(path[(pathIndex + i) * 2 + 1] + pathOffY);
    }

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
    /** Частка розмаху поточного тремтіння — від зброї того, хто вдарив. */
    private float shakeScale = 1f;
    /** Власний годинник анімації. Йде за часом КАДРУ, до симуляції не належить. */
    private float shakeClock;

    /**
     * Частка розмаху, з якою тремтять учасники удару цього юніта; 0 — не
     * тремтить ніхто.
     *
     * <p>Належить тому, хто Б'Є: тремтіння — це відлуння удару, і його силу
     * задає зброя, а не той, кому дісталось.
     *
     * <p>За замовчуванням нуль, і це не заглушка. Тремтить лише сутичка
     * впритул: вершник висить на цілі й довбає її, і рух тут — частина того,
     * що відбувається. Перестрілка на 40 одиниць — це двоє, що стоять і
     * стріляють; дрижання спрайтів у ній читалось би як брак рендера.
     */
    public float combatShakeScale() { return 0f; }

    /** Смикнути юніта: викликається на кожен удар — і по тому, хто б'є, і по цілі. */
    public void kickCombatShake(float scale) {
        if (scale <= 0f) return;
        shakeTimer = SHAKE_DURATION;
        shakeScale = scale;
    }

    /**
     * Обірвати тремтіння негайно.
     *
     * <p>Потрібне рівно на смерть цілі: таймер живе довше за кулдаун, щоб бій
     * тремтів безперервно, і без цього переможець ще півтори секунди дрижав би
     * над порожнім місцем.
     */
    public void clearCombatShake() { shakeTimer = 0f; }

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
        return sum / SHAKE_FREQ.length * SHAKE_AMPLITUDE * shakeScale * fade;
    }

    /** Позиція у світових одиницях для UI-запитів (клік, підказка). */
    public float worldX() { return Fixed.toFloat(x); }
    public float worldY() { return Fixed.toFloat(y); }

    public boolean isMoving() { return moving; }

    // ── Урон ──────────────────────────────────────────────────────────────

    /** Проста атака без урахування місцевості. */
    public boolean takeDamage(long rawDamage) {
        return takeDamage(rawDamage, Fixed.ONE);
    }

    /**
     * @param moraleShock у скільки разів цей удар страшніший за звичайний
     *                    ({@link #moraleShock()} того, хто б'є)
     */
    public boolean takeDamage(long rawDamage, long moraleShock) {
        long effective = Fixed.max(0, rawDamage - defense);
        applyHit(effective, moraleShock);
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
        return takeDamageWithTerrain(rawDamage, defenseMultiplier, Fixed.ONE);
    }

    public boolean takeDamageWithTerrain(long rawDamage, long defenseMultiplier, long moraleShock) {
        long afterArmor = Fixed.max(0, rawDamage - defense);
        // defenseMultiplier > 1 → ділимо (захист більший)
        // defenseMultiplier < 1 → ціль вразлива, урон більший
        long effective = Fixed.div(afterArmor, defenseMultiplier);
        applyHit(effective, moraleShock);
        return !alive;
    }

    /**
     * Спільний хвіст обох атак: здоров'я, мораль, смерть.
     *
     * <p>Один метод, а не два однакові хвости, саме тому, що місць загибелі в
     * грі й так три, і четверте, про яке забули, тут з'явилось би першим.
     *
     * <p>Мораль коштує рівно те, що ДІЙШЛО крізь броню й місцевість, а не
     * заявлений damage: одна цифра урону — одна причина боятись. Тому
     * позиція на висоті й у лісі бережe не лише життя, а й нерви, і другого
     * набору множників заводити не треба.
     */
    private void applyHit(long effective, long moraleShock) {
        // Втікача рубають. Це не приправа до механіки, а те, що робить її
        // чесною: без цього злам був БЕЗКОШТОВНОЮ евакуацією, і вигідною саме
        // тому, хто програє — накритий загін не гинув, а розбігався, потім
        // повертався. Тобто маса переставала конвертуватись у вбитих, і
        // зосередження сил — те, на чому стоїть гра, — знецінювалось.
        // Виміряно: NORMAL проти EASY 6/12 без цього і 10/12 з ним.
        if (isBroken()) effective = Fixed.mul(effective, ROUT_VULNERABILITY);
        hp -= effective;
        if (hp <= 0) { hp = 0; alive = false; }
        loseMorale(Fixed.mul(Fixed.mul(effective, MORALE_PER_HP), moraleShock));
    }

    // ── Мораль ────────────────────────────────────────────────────────────

    /**
     * Скільки моралі коштує одиниця отриманого урону.
     *
     * <p>Було 0.25; поділено на 1.5 разом з рештою джерел втрати
     * (див. {@code MoraleSystem}) — мораль мусить згоряти повільніше за бій,
     * а бій став удвічі довшим від подвоєння здоров'я.
     */
    public static final long MORALE_PER_HP = Fixed.fromFloat(0.1667f);

    /**
     * У скільки разів більше урону отримує зламаний.
     *
     * <p>Спина, що біжить, — найлегша ціль у бою, і в грі це мусить бути так
     * само. Див. {@link #applyHit} про те, що ламалось без цього.
     */
    public static final long ROUT_VULNERABILITY = Fixed.fromFloat(1.6f);

    /**
     * Забрати моралі. Єдина точка, де мораль падає, — саме тому тут же
     * скидається лічильник спокою: втрата і є ознака того, що юніт у бою.
     */
    public void loseMorale(long amount) {
        if (amount <= 0 || !alive) return;
        morale = Fixed.max(0, morale - amount);
        calmTicks = 0;
    }

    /** Чи юніт зламаний: не слухає наказів, не б'ється, тікає. */
    public boolean isBroken() { return routTicks > 0; }

    /** Частка моралі 0..1 — тільки для бару. */
    public float moraleRatio() {
        return maxMorale <= 0 ? 1f : (float) ((double) morale / (double) maxMorale);
    }

    /**
     * У скільки разів удар цього юніта страшніший за звичайний постріл.
     *
     * <p>Тут, а не гілкою по типу зброї в бою: лякає не «клас юніта», а те, ЯК
     * б'ють. Наступний юніт, чий удар має ламати стрій, дістане це,
     * перевизначивши одне число, — рівно як {@link #knockbackForce()}.
     */
    public long moraleShock() { return Fixed.ONE; }

    /**
     * Радіус, у якому юніт підтримує своїх (Q47.16); {@code 0} — не підтримує.
     *
     * <p>Порожній хук навмисно: офіцери й музиканти будуть, і коли будуть, вони
     * мусять коштувати клас плюс два числа, а не правку в системі моралі.
     * {@code MoraleSystem} читає ці два методи вже зараз.
     */
    public long moraleAuraRadius() { return 0; }

    /** Скільки моралі за секунду додає аура своїм у радіусі. */
    public long moraleAuraBonus() { return 0; }

    /**
     * Шанс влучити з відстані {@code dist} (Q47.16), у частках {@link Fixed#ONE}.
     *
     * <p>За замовчуванням {@link Fixed#ONE} — б'є завжди. Це не заглушка: у
     * ближньому бою промахів не буває, бо кіннота дістає лише того, кого
     * фактично торкнулась, і кидок там означав би, що вершник проїхав крізь
     * ціль. Розкид — властивість пострілу, а не удару.
     *
     * <p>Хто повертає рівно {@code ONE}, той НЕ смикає RNG у бою (див.
     * {@code CombatManager.tryAttack}). Гілка залежить від класу юніта, тобто
     * однакова на всіх клієнтах, — інакше кількість смикань розійшлася б і за
     * нею весь матч.
     */
    public long hitChanceAt(long dist) { return Fixed.ONE; }

    /**
     * Витратити кулдаун без пострілу по цілі.
     *
     * <p>Потрібне рівно на промах: постріл СТАВСЯ — куля полетіла, порох
     * витрачено, — просто нікуди не влучив. Без цього юніт, що промазав,
     * стріляв би знову наступного ж тіку, і мала влучність перетворилась би на
     * велику скорострільність.
     */
    public void consumeAttackCooldown() { attackTimerTicks = attackCooldownTicks; }

    /** Базова атака (без місцевості). */
    public boolean attack(Unit target) {
        if (attackTimerTicks > 0) return false;
        target.takeDamage(damage, moraleShock());
        attackTimerTicks = attackCooldownTicks;
        return true;
    }

    /** Атака з урахуванням місцевості. Викликається з CombatManager. */
    public boolean attackWithTerrain(Unit target, long defenseMultiplier) {
        if (attackTimerTicks > 0) return false;
        target.takeDamageWithTerrain(damage, defenseMultiplier, moraleShock());
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
        out.writeLong(morale);
        out.writeInt(calmTicks);
        out.writeInt(routTicks);
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
        // maxMorale не їде: це характеристика з конструктора, як maxHp.
        morale    = in.readLong();
        calmTicks = in.readInt();
        routTicks = in.readInt();
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

    /**
     * Чи юніт зник не від смерті, а тому, що його поглинув інший.
     *
     * <p>Зараз так вибувають лише гармати, зведені в батарею
     * ({@code Artillery.absorbed}). Питання стоїть на {@code Unit}, бо його
     * ставить {@code VictoryTracker}, рахуючи ВТРАТИ: мертвий і поглинутий
     * виглядають однаково ({@code alive == false}), а означають протилежне.
     */
    public boolean absorbed() { return false; }

    /**
     * Півширина хітбокса (Q47.16).
     *
     * <p>Типово хітбокс КВАДРАТНИЙ і збігається з {@link #hitRadiusFixed()} —
     * саме так живуть усі юніти, чий спрайт квадратний. Перевизначають цю пару
     * лише ті, чия фігура витягнута (легка піхота: спрайт 29×11), і тоді
     * {@link #hitRadiusFixed()} лишається КОЛОМ для бою й поштовху, а прямокутник
     * працює там, де питання геометричне: розштовхування, виділення, обводка.
     *
     * <p>Дві окремі осі, а не «розмір плюс пропорція»: пропорцію довелось би
     * розгортати в кожному місці заново, і рано чи пізно хтось помножив би не
     * ту вісь.
     */
    public long halfWidthFixed()  { return hitRadiusFixed(); }

    /** Піввисота хітбокса (Q47.16). Див. {@link #halfWidthFixed()}. */
    public long halfHeightFixed() { return hitRadiusFixed(); }

    /**
     * Чи хітбокс НЕ квадратний.
     *
     * <p>Питається там, де прямокутник вимагає іншої математики, ніж коло, —
     * зараз це одне місце ({@code UnitSeparation}). Гілка тут відповідає на
     * питання про ГЕОМЕТРІЮ, а не про тип юніта, і однакова на всіх клієнтах.
     */
    public final boolean rectangular() { return halfWidthFixed() != halfHeightFixed(); }

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

        long halfW = halfWidthFixed(), halfH = halfHeightFixed();
        if (mapW > halfW * 2 && mapH > halfH * 2) {
            x = Fixed.clamp(x + pushVelX, halfW, mapW - halfW);
            y = Fixed.clamp(y + pushVelY, halfH, mapH - halfH);
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
     * Чи юніт верхи.
     *
     * <p>Єдине, на що це впливає, — його НЕ зрушує той, чий поштовх бере лише
     * піших ({@link #shovesMounted()}). Тому й ознака, а не {@code instanceof}:
     * питання не «якого він класу», а «чи є під ним кінь, який тримає удар».
     */
    public boolean mounted() { return false; }

    /**
     * Чи поштовх цього юніта бере верхових.
     *
     * <p>Типово так — стріляють і б'ють однаково по всіх. Важка кіннота каже
     * «ні»: вона зносить стрій піхоти, але чужого вершника лише зупиняє.
     * Пара з {@link #mounted()} тримає це правило двома числами замість гілки
     * по типах у {@code CombatManager}.
     */
    public boolean shovesMounted() { return true; }

    /**
     * Чи супроводжується удар пострілом — трасером і звуком.
     *
     * <p>Ближній бій не стріляє: у кінноти немає ні ствола, ні пострілу, і
     * трасер від неї до цілі, яку вона щойно збила конем, читався б як промах
     * рендера.
     */
    public boolean usesRangedFx() { return true; }

    /**
     * Чи юніт воює В СТРОЮ: іде разом із загоном, рахується в його масу,
     * тримає й боронить точку.
     *
     * <p>Типово — чи він узагалі має чим бити. Гармата має {@code damage == 0}
     * і стріляє окремою процедурою, тож у строю з неї користі немає; будь-який
     * майбутній юніт підтримки дістане ту саму поведінку задарма.
     *
     * <p><b>Тут, а не {@code instanceof Artillery} у боті.</b> Цим питанням
     * перевірялись два десятки місць — розподіл по напрямках, поріг маси, марш,
     * оборона точки, охорона гармат, — і кожен новий тип означав би двадцять
     * нових гілок. Той самий принцип, що в {@link #knockbackForce}.
     */
    public boolean holdsLine() { return damage > 0; }

    /**
     * Чи юніт помітно швидший за строй: такий відривається на марші й встигає
     * туди, куди піхота не встигає.
     *
     * <p>Теж за ЧИСЛОМ, а не за класом. Межа стоїть між піхотою (20/с) і
     * кіннотою (40/с), тож новий швидкий юніт стає «швидким» сам собою, а
     * повільний — ні, і бота правити не треба.
     */
    public boolean outrunsLine() { return speedPerTick > FAST_SPEED_PER_TICK; }

    /** Межа «швидкого»: 30 одиниць/с. */
    private static final long FAST_SPEED_PER_TICK =
        Fixed.divInt(Fixed.fromInt(30), TickRate.TICKS_PER_SECOND);

    /**
     * Якому пункту каталогу відповідає юніт.
     *
     * <p>Абстрактний навмисно: новий тип мусить назватись, і забути про це не
     * дасть компілятор. Це єдиний рядок, який новий юніт зобов'язаний написати
     * заради бота — решту той виводить із чисел.
     */
    public abstract UnitType type();

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

    /**
     * Ширина й висота спрайта в пікселях.
     *
     * <p>Типово квадрат {@link #renderSizePx()} — рівно те, що було до появи
     * невквадратних спрайтів. Розводяться лише в юнітів із витягнутою
     * картинкою; для них ці два числа МУСЯТЬ повторювати пропорцію файлу,
     * інакше фігуру видно розтягнутою.
     */
    public float renderWidthPx()  { return renderSizePx(); }

    /** Див. {@link #renderWidthPx()}. */
    public float renderHeightPx() { return renderSizePx(); }

    /** Радіус влучання в пікселях — для UI-пікінгу (клік/рамка). */
    public float getHitRadiusPx() { return Fixed.toFloat(hitRadiusFixed()); }

    /** Півширина хітбокса в пікселях — пікінг, обводка, бари. */
    public float getHalfWidthPx()  { return Fixed.toFloat(halfWidthFixed()); }

    /** Піввисота хітбокса в пікселях. Див. {@link #getHalfWidthPx()}. */
    public float getHalfHeightPx() { return Fixed.toFloat(halfHeightFixed()); }

    /**
     * Зсув HP-бару по X у пікселях (додатне — правіше). Чисто косметика:
     * у частини спрайтів фігура зміщена від центру, і бар «відлипає».
     */
    public float hpBarOffsetX() { return 0f; }

    public abstract String getTexturePath();
}
