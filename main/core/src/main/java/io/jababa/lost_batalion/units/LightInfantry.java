package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;

/**
 * Легка піхота — застрільники.
 *
 * <p>Той самий мушкет, що й у {@link Infantry}: та сама крива влучності
 * ({@link #hitChanceAt}), той самий урон, той самий кулдаун. Різниця в тому,
 * ЗВІДКИ вона стріляє: дальність {@link #LINF_RANGE} 120 проти 90, тобто вона
 * відкриває вогонь там, де лінійна піхота ще йде. Платить за це здоров'ям
 * (140 проти 200) — у зустрічному бою на своїй дистанції вона виграє, у
 * сутичці на 30 одиницях гине першою.
 *
 * <p>Помітно її гірше: {@code stealthRating} 0.35 проти 0.20. Разом із лісом
 * (×1.8 у цілі, ×4.0 на шляху променя) це юніт, який у гущавині зникає з очей
 * зовсім — те, заради чого застрільники й існують.
 *
 * <h3>Хітбокс — прямокутний</h3>
 * Єдиний юніт із НЕквадратним спрайтом (29×11 пікселів), і хітбокс повторює
 * саме його: {@link #LINF_HALF_W}×{@link #LINF_HALF_H} = 18×7 світових
 * одиниць у тій самій пропорції. Кругла модель тут бреше в обидва боки —
 * радіусом по довгій осі юніт ловив би кулі порожнім місцем над собою й під
 * собою, по короткій — стояв би, налізши на сусіда половиною фігури.
 *
 * <p>{@link #hitRadiusFixed()} при цьому лишається КОЛОМ по короткій осі: воно
 * годує ближній бій ({@code CombatManager} рахує контакт як суму радіусів) і
 * поштовх, а там прямокутник нічого не додає — зате коротка вісь означає, що
 * до застрільника треба справді дійти, а не дотягтись.
 */
public class LightInfantry extends Unit {

    /** Проти 200 у лінійної: сім пострілів замість десяти. */
    private static final long LINF_HP      = Fixed.fromInt(140);

    /**
     * Одиниць за секунду. Швидша за лінійну (20), але навмисно НЕ швидша за
     * межу {@code Unit.outrunsLine()} (30): застрільник іде з ротою, просто
     * перший займає позицію.
     */
    private static final long LINF_SPEED   = Fixed.fromInt(25);

    /** Рівно піхотний — «така сама стрільба». */
    private static final long LINF_DAMAGE  = Fixed.fromInt(15);
    /** Легша за лінійну: без строю й без важкого спорядження. */
    private static final long LINF_DEFENSE = Fixed.fromInt(2);

    /**
     * Дальність пострілу — 120 проти 90 у лінійної піхоти.
     *
     * <p>Це ЄДИНА перевага, за якою її беруть, тож вона й найдорожча в балансі:
     * {@code CombatManager.standoff} спиняє стрільця на 0.85 дальності, тобто
     * легка піхота стоїть на 102 там, де лінійна стоїть на 76.5, і в лобовому
     * розміні перша черга лишається за нею.
     */
    private static final long LINF_RANGE   = Fixed.fromInt(120);

    /** 1.2 с при 40 Гц — той самий темп, що в лінійної піхоти. */
    private static final int  LINF_COOLDOWN_TICKS = 48;

    /** Мораль нижча за лінійну сотню: застрільник не тримає стрій, він тікає. */
    private static final long LINF_MORALE  = Fixed.fromInt(80);

    // ── Габарит ───────────────────────────────────────────────────────────

    /**
     * Півширина й піввисота хітбокса (Q47.16) — 18×7 світових одиниць.
     *
     * <p>Пропорція взята зі спрайта: 29/11 = 2.64, тут 18/7 = 2.57. Округлено
     * до половини одиниці навмисно — це числа симуляції, і читати їх у логах
     * має бути можливо без калькулятора.
     */
    private static final long LINF_HALF_W = Fixed.fromFloat(9f);
    private static final long LINF_HALF_H = Fixed.fromFloat(3.5f);

    /** Ширина спрайта = ширина хітбокса: у цього юніта картинка і є фігура. */
    public static final float LINF_WIDTH  = 18f;
    public static final float LINF_HEIGHT = 7f;

    /**
     * «Розмір» юніта — довга вісь.
     *
     * <p>Цим числом користується обрізання цілі по краю карти, і взяти там
     * коротку вісь означало б дозволити половині фігури висіти за межею.
     */
    private static final long LINF_SIZE_FIXED = Fixed.fromInt(18);

    public LightInfantry(Team team, int owner, long rawX, long rawY) {
        super(team, owner);
        this.maxHp               = LINF_HP;
        this.hp                  = LINF_HP;
        this.maxMorale           = LINF_MORALE;
        this.morale              = LINF_MORALE;
        this.speedPerTick        = Fixed.divInt(LINF_SPEED, TickRate.TICKS_PER_SECOND);
        this.damage              = LINF_DAMAGE;
        this.defense             = LINF_DEFENSE;
        this.attackRange         = LINF_RANGE;
        this.attackCooldownTicks = LINF_COOLDOWN_TICKS;
        // Розсипний стрій і легке спорядження: ховається помітно краще за лінійну.
        this.stealthRating       = Fixed.fromFloat(0.35f);
        setPosition(rawX, rawY);
    }

    // ── Влучність ─────────────────────────────────────────────────────────

    /** Впритул мушкет не мажe — те саме число, що в лінійної піхоти. */
    private static final long LINF_POINT_BLANK    = Fixed.fromInt(30);

    /**
     * Шанс влучити на межі дальності — 0.30 проти 0.35 у лінійної.
     *
     * <p>Трохи нижче навмисно: інакше довша дальність була б чистим додатком
     * без ціни, і лінійна піхота не мала б жодної причини існувати. Постріл
     * на 120 лишається пострілом «щоб не давати підійти», а не розміном.
     */
    private static final long LINF_MIN_HIT_CHANCE = Fixed.fromFloat(0.30f);

    /**
     * Та сама лінійна крива, що в {@link Infantry#hitChanceAt}, тільки
     * розтягнута на довшу дальність.
     *
     * <p>Не успадкована від піхоти, бо клас від неї не успадковується: спільним
     * предком тут довелось би зробити «стрільця з розкидом», а це три поля
     * заради двох класів. Коли таких типів стане більше — виносити.
     */
    @Override
    public long hitChanceAt(long dist) {
        if (dist <= LINF_POINT_BLANK) return Fixed.ONE;
        if (dist >= LINF_RANGE)       return LINF_MIN_HIT_CHANCE;

        long span = LINF_RANGE - LINF_POINT_BLANK;
        long over = dist - LINF_POINT_BLANK;
        long drop = Fixed.mul(Fixed.div(over, span), Fixed.ONE - LINF_MIN_HIT_CHANCE);
        return Fixed.ONE - drop;
    }

    // ── Габарит і вигляд ──────────────────────────────────────────────────

    @Override public long sizeFixed()       { return LINF_SIZE_FIXED; }

    /** Коло для бою й поштовху — по КОРОТКІЙ осі (див. коментар класу). */
    @Override public long hitRadiusFixed()  { return LINF_HALF_H; }

    @Override public long halfWidthFixed()  { return LINF_HALF_W; }
    @Override public long halfHeightFixed() { return LINF_HALF_H; }

    @Override public float renderWidthPx()  { return LINF_WIDTH; }
    @Override public float renderHeightPx() { return LINF_HEIGHT; }

    @Override public String getTexturePath() {
        return team == Team.PLAYER
            ? "units/light_infantry_player.png"
            : "units/light_infantry_enemy.png";
    }

    @Override public UnitType type() { return UnitType.LIGHT_INFANTRY; }
}
