package io.jababa.lost_batalion.ai;

/**
 * Рівень супротивника.
 *
 * <h3>Чим НЕ робиться складність</h3>
 * Не бонусами до золота, урону чи огляду. Бот, який бачить крізь туман або
 * стріляє сильніше, ламає рівно ту механіку, на якій тримається гра, і
 * перемога над ним нічого не означає. Тут усі рівні грають за тими самими
 * правилами, що й людина.
 *
 * <h3>Чим робиться</h3>
 * Тим самим, чим відрізняються гравці: швидкістю рішень і тим, чи вміє гравець
 * речі, яких новачок не вміє. Головне з них — НЕ ГОДУВАТИ супротивника
 * поодинці ({@link #massThreshold}); саме це відрізняє того, хто грає, від
 * того, хто натискає кнопки.
 *
 * <h3>Чому таблиця, а не три класи</h3>
 * Перша версія розводила рівні чотирма числами, з яких NORMAL і HARD
 * відрізнялись РІВНО одним ({@code attackOdds}), а найголовніше —
 * {@code thinkPeriodTicks} не читалось ніде. На екрані це давало три однакові
 * рівні. Тому тут кожен стовпчик мусить справді відрізнятись хоч у двох рівнях,
 * і кожен мусить мати читача.
 */
public enum Difficulty {

    /**
     * Новачок. Годує суперника поодинці ({@code massThreshold = 1}), думає
     * рідко, тримає гроші без діла й не боронить того, що взяв.
     */
    EASY("ЛЕГКИЙ",
         12, 0.35f, 1.60f, false, false,
          1,    1, false, false, false, false),

    /** Рівний суперник: збирає групу, боронить свої точки, тримає висоти. */
    NORMAL("ЗВИЧАЙНИЙ",
            4, 0.00f, 1.05f, true, true,
            3,    2, true, false, false, false),

    /**
     * Тисне. Збирає найбільший кулак, б'є найслабшого, відводить поранених.
     *
     * <p>Розділення сил тут ВИМКНЕНЕ — див. {@link #splitsForces}.
     */
    HARD("ВАЖКИЙ",
          2, 0.00f, 1.00f, true, true,
          5,    2, true, true, true, false);

    public final String title;

    /** Як часто бот переглядає обстановку. Читає {@link BotPlayer}. */
    public final int thinkPeriodTicks;
    /** Яку частку скарбниці бот НЕ витрачає. */
    public final float goldReserve;
    /** За якої переваги в головах бот іде вперед. */
    public final float attackOdds;
    public final boolean usesArtillery;
    public final boolean seeksHighGround;

    /**
     * Скільки бійців треба зібрати, перш ніж іти на ціль.
     *
     * <p>Найважливіше число тут. При одній точці прибуток дає одного піхотинця
     * на ~50 секунд, і бот, який відправляє кожного одразу на ціль, приходить
     * туди по одному проти цілого загону. Одиниця означає саме цю поведінку —
     * і лишена вона тільки легкому, як його головна вада.
     */
    public final int massThreshold;

    /** Скільки лишати на вже захопленій точці. */
    public final int garrison;

    /** Чи реагувати на те, що твою точку зривають. */
    public final boolean defendsPoints;
    /** Чи бити найслабшого замість найближчого. */
    public final boolean focusesWeakest;

    /**
     * Чи відводити поранених із-під вогню.
     *
     * <p>Виміряно нейтральним за результатом (3 перемоги з 4 і з ним, і без
     * нього), лишене увімкненим для важкого рівня заради ВИГЛЯДУ: гравець має
     * бачити, що суперник поводиться інакше, а не лише програвати йому.
     */
    public final boolean retreatsWounded;

    /**
     * Чи відправляти окремий загін (усю кінноту) на другу ціль.
     *
     * <p><b>Вимкнене скрізь, і це результат вимірювання, а не недороблення.</b>
     * Ізольований прогін бот-проти-бота: з увімкненим розділенням важкий рівень
     * програв УСІ ЧОТИРИ матчі, з вимкненим — виграв три з чотирьох, за решти
     * однакових налаштувань. Причина видна з правил гри: кіннота це 26 урону
     * проти 15 у піхоти, тобто головна ударна сила, і забрати її з основного
     * кулака означає програти головний бій заради порожньої точки.
     *
     * <p>Механізм лишено в коді навмисно: він стане вигідним, коли бот навчиться
     * підсилювати другий напрямок, а не лише відривати від першого. Але вмикати
     * його «бо розумно виглядає» не можна — це вже перевірено.
     */
    public final boolean splitsForces;

    Difficulty(String title,
               int thinkPeriodTicks, float goldReserve, float attackOdds,
               boolean usesArtillery, boolean seeksHighGround,
               int massThreshold, int garrison,
               boolean defendsPoints, boolean focusesWeakest,
               boolean retreatsWounded, boolean splitsForces) {
        this.title            = title;
        this.thinkPeriodTicks = thinkPeriodTicks;
        this.goldReserve      = goldReserve;
        this.attackOdds       = attackOdds;
        this.usesArtillery    = usesArtillery;
        this.seeksHighGround  = seeksHighGround;
        this.massThreshold    = massThreshold;
        this.garrison         = garrison;
        this.defendsPoints    = defendsPoints;
        this.focusesWeakest   = focusesWeakest;
        this.retreatsWounded  = retreatsWounded;
        this.splitsForces     = splitsForces;
    }

    public static Difficulty byName(String name) {
        if (name == null) return NORMAL;
        for (Difficulty d : values()) if (d.name().equalsIgnoreCase(name)) return d;
        return NORMAL;
    }
}
