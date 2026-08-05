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
    private static final long ART_HP      = Fixed.fromInt(180);
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

    public Artillery(Team team, long rawX, long rawY) {
        super(team);
        maxHp               = ART_HP;
        hp                  = ART_HP;
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
    @Override public float  renderSizePx()   { return ART_RENDER_SIZE; }
    @Override public long   hitRadiusFixed() { return ART_HIT_RADIUS; }
    @Override public float  hpBarOffsetX()   { return ART_HP_BAR_OFFSET_X; }
    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/artillery_player.png"
            : "units/artillery_enemy.png";
    }
}
