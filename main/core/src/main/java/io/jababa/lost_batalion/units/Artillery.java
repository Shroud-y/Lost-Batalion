package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;

/**
 * Артилерійський юніт.
 *
 * - Автоматично обстрілює ворогів у радіусі STRIKE_RANGE, яких БАЧИТЬ САМА
 *   (AoE-снаряд). Чужа розвідка їй не допомагає: сторонової видимості замало.
 * - Якщо гравець виділив артилерію і клікнув ПКМ по ворогу — б'є саме по ньому
 *   (manualTarget), поки той живий; якщо ціль поза радіусом або її не видно,
 *   гармата сама йде на позицію з пошуком шляху й відкриває вогонь звідти.
 *   Скидається наказ лише смертю цілі або наказом руху/зупинки.
 * - Менший розмір спрайту та хітбоксу (32px).
 *
 * <p>Усі таймери — в тіках. Секунди тут були найгіршим місцем для float: час
 * прицілювання визначає МОМЕНТ пострілу, а момент пострілу визначає, куди
 * поїде снаряд і хто під ним опиниться.
 */
public class Artillery extends Unit {

    /** Розмір у світових одиницях (Q47.16) і в пікселях — для рендеру. */
    public static final long  ART_SIZE_FIXED = Fixed.fromInt(16);
    public static final float ART_SIZE       = 32f;
    /**
     * Хітбокс менший за спрайт: за замовчуванням це size/2 = 8, але гармата
     * на спрайті займає лише середину — стріляти й клікати по порожніх кутах
     * не мало б працювати.
     */
    private static final long ART_HIT_RADIUS = Fixed.fromInt(7);
    /**
     * Розмір спрайта. Більший за {@link #ART_SIZE_FIXED}: гармата з обслугою
     * має читатись як важка техніка, а не як трохи ширша піхота. Хітбокс при
     * цьому лишається старий — виросла картинка, а не машина.
     */
    private static final float ART_RENDER_SIZE = 18f;
    /** Спрайт гармати зміщений вліво від центру — бар зсуваємо правіше. */
    private static final float ART_HP_BAR_OFFSET_X = 2f;

    private static final long ART_SPEED   = Fixed.fromInt(17);
    /**
     * Було 180; подвоєно разом з усіма (див. {@code Infantry.INF_HP}).
     *
     * <p>Публічне, бо від нього рахується межа здоров'я батареї: у дуо вона
     * рівно вдвічі, у тріо втричі більша. Саме множення від базового, а не
     * сума максимумів учасників, — щоб число не залежало від того, ЯКИМИ
     * гарматами батарею зібрали.
     */
    public static final long ART_HP      = Fixed.fromInt(360);
    private static final long ART_DEFENSE = Fixed.fromInt(5);

    // ── Параметри пострілу ────────────────────────────────────────────────
    /** Максимальна дальність обстрілу (auto + manual), Q47.16. */
    public static final long STRIKE_RANGE         = Fixed.fromInt(220);
    /** Час прицілювання до пострілу — 3 с при 40 Гц. */
    public static final int  STRIKE_AIM_TICKS     = 3 * TickRate.TICKS_PER_SECOND;
    /**
     * Радіус вибуху (AoE), Q47.16.
     *
     * <p>Мусить бути помітно БІЛЬШИМ за мінімальну відстань між юнітами, інакше
     * AoE-зброя за побудовою не дістає більш ніж одну ціль. Розштовхування
     * тримає сусідів на {@code hitRadius + hitRadius} = 16 одиниць
     * ({@code UnitSeparation}), і колись тут стояло 15 — тобто вибух гарантовано
     * не діставав нікого, крім того, в кого влучив. При 45 накривається
     * приблизно три ряди строю, і гармата робить те, заради чого існує.
     */
    public static final long STRIKE_SPLASH_RADIUS = Fixed.fromInt(45);
    /** Базовий урон у центрі вибуху, Q47.16. Пряме влучання знімає піхоту (100 hp). */
    public static final long STRIKE_DAMAGE        = Fixed.fromInt(120);
    /** Максимальний розкид снаряду від точки прицілу, Q47.16. */
    public static final long STRIKE_SPREAD        = Fixed.fromInt(18);
    /** Час перезарядки між пострілами — 8 с при 40 Гц. */
    public static final int  STRIKE_RELOAD_TICKS  = 8 * TickRate.TICKS_PER_SECOND;

    // ── Розворот ──────────────────────────────────────────────────────────
    /**
     * Повний оберт за 1.5 с. Гармата не їде і не стріляє, поки не стане
     * лицем у потрібний бік, тож це фактично затримка перед кожною дією:
     * розворот на 180° коштує 0.75 с.
     */
    public static final int  ART_TURN_TICKS_FULL   = 4 * TickRate.TICKS_PER_SECOND / 2;
    private static final long ART_TURN_RATE_PER_TICK = Fixed.divInt(Fixed.PI2, ART_TURN_TICKS_FULL);
    /** Спрайт намальований стволом угору, тобто вже повернутий на +90°. */
    private static final float ART_SPRITE_FACING_OFFSET = -90f;

    /** Швидкість снаряду в світових одиницях за секунду — використовує і сим, і рендер. */
    public static final float SHELL_SPEED_PX = 700f;

    /** Таймер перезарядки в тіках. Керується CombatManager. */
    public int reloadTicks = 0;
    /** Таймер прицілювання по поточній цілі, в тіках. Керується CombatManager. */
    public int aimTicks = 0;
    /**
     * Ручна ціль (виставляється ПКМ по ворогу поки артилерія виділена).
     * Поки жива і в радіусі — має пріоритет над авто-ціллю.
     */
    public Unit manualTarget = null;

    /**
     * Ціль, по якій гармата наводиться ЗАРАЗ — ручна чи знайдена самостійно.
     *
     * <p>Стан, а не тимчасова змінна, і саме в цьому суть. Раніше авто-ціль
     * обиралась щотіку заново — найближчий видимий ворог. Коли перед гарматою
     * стоїть НАТОВП, «найближчий» міняється щокроку: сусіди перетасовуються на
     * частки одиниці, ціль перестрибує між ними, гармату щоразу доводиться
     * доводити, а {@code turnToward} на час доводки скидає {@link #aimTicks}.
     * Три секунди прицілу не набирались НІКОЛИ — гармата в натовпі не стріляла
     * взагалі, тоді як по одинокій цілі працювала нормально.
     *
     * <p>Тепер ціль тримається, поки вона жива, видима і в дальності. Це ж поле
     * відповідає на друге питання — «чи це та сама ціль, у яку я цілився
     * минулого тіку»: {@code aimTicks} скидається на будь-якій ЗМІНІ, інакше
     * гармата доводила б чужий приціл і стріляла по тому, в кого не цілилась.
     */
    public Unit aimTarget = null;

    // ── Батарея (об'єднання гармат) ───────────────────────────────────────
    //
    // Кілька гармат зводяться в ОДИН юніт, а не в групу з посиланнями. Це
    // рішення тут головне, і воно про симуляцію: юніт — це те, що має id,
    // позицію, hp і рядок у знімку. «Група, якою керують як одним» означала б
    // другу сутність із власним життєвим циклом у чотирьох місцях (виділення,
    // накази, знімок, checksum) і питання «хто помер, коли вбили одного з
    // трьох». Натомість зайві стволи ПОГЛИНАЮТЬСЯ: їхнє hp додається до
    // ведучого, самі вони вибувають, і далі все працює так, ніби гармата
    // завжди була одна — просто міцніша й голосніша.

    /** Скільки стволів у цій батареї: 1 — звичайна гармата, 3 — межа. */
    public int stack = 1;

    /** Більше трьох не зводиться. Дуо + дуо теж упреться сюди. */
    public static final int MAX_STACK = 3;

    /**
     * Id гармати, до якої ця ЙДЕ на об'єднання; {@code -1} — нікуди.
     *
     * <p>Id, а не посилання: наказ переживає ресинк, а посилання довелось би
     * розв'язувати другим проходом, як {@link #manualTarget}. Ведуча гармата
     * несе тут {@code -1} — вона стоїть і чекає.
     */
    public int mergeWithId = -1;

    /**
     * Тік, раніше за який не перебудовувати маршрут до ведучої.
     *
     * <p>Маршрут доводиться перебудовувати, бо ведуча могла зрушити після
     * наказу: та, що йшла до неї, дійде до порожнього місця й стане. Але
     * робити це щотіку не можна — недосяжна ведуча (інший берег річки)
     * означала б пошук шляху 40 разів на секунду. Тому раз на пів секунди.
     */
    public int mergeRepathTick = 0;

    /** Пауза між спробами перекласти маршрут до ведучої, у тіках. */
    public static final int MERGE_REPATH_PERIOD = TickRate.TICKS_PER_SECOND / 2;

    /**
     * Чи цю гармату поглинула інша.
     *
     * <p>Поглинута лишається в списку з {@code alive == false} (id мусять
     * лишатись валідними), і без цієї ознаки {@code VictoryTracker} порахував
     * би її ВТРАТОЮ: він рахує саме мертвих скануванням списку. Об'єднання —
     * не втрата, і підсумок матчу не має цього плутати.
     */
    public boolean absorbed = false;

    @Override public boolean absorbed() { return absorbed; }

    /**
     * Скільки стволів стріляє залпом — рівно стільки, скільки їх на спрайті.
     *
     * <p>Батарея НЕ множить урон одного снаряда: вона випускає окремий снаряд
     * з кожного ствола, кожен зі своїм розкидом. Різниця не косметична —
     * три окремі вибухи накривають ширшу смугу, ніж один потрійний, і саме так
     * батарея й мусить працювати проти строю.
     */
    public int barrels() { return stack; }

    /**
     * Зсув ствола {@code i} ВБІК від центру фігури (Q47.16, додатне — праворуч
     * від напрямку пострілу).
     *
     * <p>Числа зняті зі спрайтів: у дуо (19 пікселів) стволи стоять на 4.5 і
     * 14.5, у тріо (29) — на 5, 14.5 і 24. Тобто крок між ними ≈ 9.5, а весь
     * ряд симетричний відносно центру. Це підбір на око, як і просили: щоб
     * спалах вилітав зі ствола, а не з порожнечі поруч.
     */
    public long barrelOffset(int i) {
        if (stack <= 1) return 0;
        // Ряд симетричний: (i - (n-1)/2) кроків від центру.
        long steps = Fixed.fromFloat(i - (stack - 1) / 2f);
        return Fixed.mul(steps, BARREL_SPACING);
    }

    /** Відстань між сусідніми стволами на спрайті. */
    private static final long BARREL_SPACING = Fixed.fromFloat(9.5f);

    /**
     * Мораль обслуги — 60 проти сотні в піхоти.
     *
     * <p>Біля гармати теж люди, і це найгірше місце в бою: відбитись нема чим
     * ({@code damage == 0}), утекти нема на чому (17 одиниць проти 40 у
     * кінноти). Прорив до батареї має її розганяти, а не мовчки з'їдати —
     * і саме тому тривога {@code protectGuns} у бота коштує двох бійців.
     */
    private static final long ART_MORALE = Fixed.fromInt(60);

    /**
     * Обстріл лякає вдвічі.
     *
     * <p>Множник живе на гарматі, а не на снаряді, бо страшна саме зброя:
     * 120 урону по площі — це не постріл, це подія. Разом із
     * {@link Unit#MORALE_PER_HP} пряме влучання забирає піхотинцю близько
     * половини моралі, не вбивши його.
     */
    public static final long ART_MORALE_SHOCK = Fixed.fromInt(2);

    public Artillery(Team team, long rawX, long rawY) {
        super(team);
        maxHp               = ART_HP;
        hp                  = ART_HP;
        maxMorale           = ART_MORALE;
        morale              = ART_MORALE;
        speedPerTick        = Fixed.divInt(ART_SPEED, TickRate.TICKS_PER_SECOND);
        damage              = 0;
        attackRange         = 0;
        // Артилерія ніколи не стріляє через звичайну атаку — кулдаун недосяжний.
        attackCooldownTicks = Integer.MAX_VALUE;
        defense             = ART_DEFENSE;
        sightRange          = Fixed.fromInt(500);
        setPosition(rawX, rawY);
    }

    @Override
    public void tick(long terrainSpeedMultiplier) {
        super.tick(terrainSpeedMultiplier);
        if (reloadTicks > 0) reloadTicks--;
    }

    /**
     * Id ручної цілі, прочитаний зі знімка і ще не перетворений на посилання.
     * Юніти створюються по черзі, тож у момент читання ціль може ще не існувати —
     * розв'язується другим проходом у {@code SimulationSnapshot}.
     */
    public int pendingManualTargetId = -1;
    /** Те саме для {@link #aimTarget}: посилання розв'язується другим проходом. */
    public int pendingAimTargetId = -1;

    @Override
    public void writeSnapshot(java.io.DataOutputStream out) throws java.io.IOException {
        super.writeSnapshot(out);
        out.writeInt(reloadTicks);
        out.writeInt(aimTicks);
        out.writeInt(manualTarget == null ? -1 : manualTarget.id);
        out.writeInt(aimTarget    == null ? -1 : aimTarget.id);
        out.writeInt(stack);
        out.writeInt(mergeWithId);
        out.writeInt(mergeRepathTick);
        out.writeBoolean(absorbed);
    }

    @Override
    public void readSnapshot(java.io.DataInputStream in) throws java.io.IOException {
        super.readSnapshot(in);
        reloadTicks = in.readInt();
        aimTicks    = in.readInt();
        manualTarget = null;
        pendingManualTargetId = in.readInt();
        aimTarget = null;
        pendingAimTargetId = in.readInt();
        stack       = in.readInt();
        mergeWithId     = in.readInt();
        mergeRepathTick = in.readInt();
        absorbed        = in.readBoolean();
    }

    // ── Віддача (чистий візуал) ──────────────────────────────────────────
    //
    // У знімок і в checksum НЕ входить: гармата відкочується лише на екрані,
    // її позиція в симуляції під час пострілу не змінюється. Інакше довелось
    // би або гнати відкат по мережі, або отримати десинхрон на рівному місці.

    /** Скільки секунд триває весь відкат із поверненням. */
    private static final float RECOIL_DURATION = 0.55f;
    /** На скільки пікселів гармату відкидає назад на піку. */
    private static final float RECOIL_DISTANCE = 6f;
    /** Частка відкату, за яку гармату кидає назад; решта — повільне повернення. */
    private static final float RECOIL_KICK_FRAC = 0.16f;
    /** Від центру до зрізу ствола — звідти вилітає спалах. */
    public static final float MUZZLE_OFFSET = 9f;
    /** Те саме для симуляції: снаряд вилітає саме зі зрізу, а не з центру. */
    public static final long  MUZZLE_OFFSET_FIXED = Fixed.fromFloat(MUZZLE_OFFSET);

    private float recoilTimer = 0f;

    /** Запустити відкат. Викликається в момент пострілу. */
    public void kickRecoil() { recoilTimer = RECOIL_DURATION; }

    /** Просунути відкат за часом КАДРУ (це візуал, не тік). */
    public void updateRecoil(float delta) {
        if (recoilTimer > 0f) recoilTimer = Math.max(0f, recoilTimer - delta);
    }

    /** Зсув назад уздовж ствола в пікселях: різкий кидок, плавне повернення. */
    public float recoilOffsetPx() {
        if (recoilTimer <= 0f) return 0f;
        float t = 1f - recoilTimer / RECOIL_DURATION;   // 0 → 1
        float k = t < RECOIL_KICK_FRAC
            ? t / RECOIL_KICK_FRAC
            : 1f - (t - RECOIL_KICK_FRAC) / (1f - RECOIL_KICK_FRAC);
        return RECOIL_DISTANCE * k * k;
    }

    public boolean isReady()     { return reloadTicks <= 0; }
    public void    startReload() { reloadTicks = STRIKE_RELOAD_TICKS; }

    /** Прогрес прицілювання 0..1 — тільки для індикатора. */
    public float aimProgress() {
        return Math.min(1f, aimTicks / (float) STRIKE_AIM_TICKS);
    }

    @Override public long   turnRatePerTick()      { return ART_TURN_RATE_PER_TICK; }
    @Override public float  spriteFacingOffsetDeg() { return ART_SPRITE_FACING_OFFSET; }

    @Override public long   sizeFixed()      { return ART_SIZE_FIXED; }

    // ── Габарит батареї ───────────────────────────────────────────────────
    //
    // У дуо і тріо власні спрайти (19×17 і 29×18), і фігура на них ШИРША за
    // високу — ряд гармат, а не одна. Тому і картинка, і хітбокс беруться
    // просто з розмірів файлу: батарея займає рівно те місце, яке видно.
    //
    // Хітбокс тут ПРЯМОКУТНИЙ і не повертається разом зі спрайтом. Це свідоме
    // спрощення, те саме, що в легкої піхоти: обертати рамку означало б
    // перевести розштовхування й пікінг на OBB заради одного юніта, а різниця
    // видима лише на межі, коли батарея стоїть боком.

    private static final float DUO_W  = 19f, DUO_H  = 17f;
    private static final float TRIO_W = 29f, TRIO_H = 18f;

    @Override public float renderSizePx()   { return renderWidthPx(); }

    @Override public float renderWidthPx() {
        return stack >= 3 ? TRIO_W : stack == 2 ? DUO_W : ART_RENDER_SIZE;
    }

    @Override public float renderHeightPx() {
        return stack >= 3 ? TRIO_H : stack == 2 ? DUO_H : ART_RENDER_SIZE;
    }

    @Override public long halfWidthFixed() {
        return stack >= 3 ? Fixed.fromFloat(TRIO_W / 2f)
             : stack == 2 ? Fixed.fromFloat(DUO_W  / 2f)
                          : ART_HIT_RADIUS;
    }

    @Override public long halfHeightFixed() {
        return stack >= 3 ? Fixed.fromFloat(TRIO_H / 2f)
             : stack == 2 ? Fixed.fromFloat(DUO_H  / 2f)
                          : ART_HIT_RADIUS;
    }
    @Override public long   hitRadiusFixed() { return ART_HIT_RADIUS; }
    @Override public float  hpBarOffsetX()   { return ART_HP_BAR_OFFSET_X; }
    @Override public String getTexturePath() {
        String kind = stack >= 3 ? "artillery_trio" : stack == 2 ? "artillery_duo" : "artillery";
        return "units/" + kind + (team == Team.PLAYER ? "_player.png" : "_enemy.png");
    }

    @Override public UnitType type() { return UnitType.ARTILLERY; }
}
