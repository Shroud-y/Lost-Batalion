package io.jababa.lost_batalion.units;

import io.jababa.lost_batalion.Team;

/**
 * Каталог військ, які гравець може замовити за золото.
 *
 * <p>Досі типи юнітів існували тільки як класи, і цього вистачало: армії
 * розставляла симуляція на старті. Замовлення війська — це вже дані, які їдуть
 * по мережі, і посилатись на клас там не можна. Тому тут є одне число
 * ({@link #ordinal()}), яким тип позначається в команді, і одне місце, де
 * записано, скільки він коштує.
 *
 * <p>Порядок констант — частина протоколу: команда несе саме ordinal, тож
 * переставити рядки місцями означає, що клієнт зі старим порядком купить
 * гармату замість піхоти. Нові типи додавати В КІНЕЦЬ.
 */
public enum UnitType {

    INFANTRY     (Category.INF, "ПІХОТА",       50, 0.60f),
    ARTILLERY    (Category.ART, "ГАРМАТА",     150, 0.15f),
    CAVALRY      (Category.CAV, "КІННОТА",      80, 0.25f),
    /**
     * Важка кіннота — дорожча за легку рівно тому, що не гине об стрій.
     * Частка навмисно менша: це таран для одного напрямку, а не основа армії
     * (частки нормуються, тож сума понад одиницю тут не помилка).
     *
     * <p>Назва коротка навмисно: рядок меню замовлення має 110 логічних
     * одиниць під підпис із ціною ({@code CommandPanel.MENU_WIDTH} мінус смуга
     * лічильника й відступ), і «ВАЖКА КІННОТА   130» лізе на квадратики
     * лічильника. Розділ над рядком і так називається «Кіннота».
     */
    HEAVY_CAVALRY(Category.CAV, "ВАЖКА",         130, 0.15f),
    /**
     * Легка піхота — дальність замість живучості. Частка невелика з тієї ж
     * причини, що й у важкої кінноти: це доповнення до лінії, а не лінія.
     */
    LIGHT_INFANTRY(Category.INF, "ЛЕГКА",         70, 0.15f);

    /** Розділ у меню замовлення. */
    public enum Category {
        INF("INF", "Піхота"),
        ART("ART", "Артилерія"),
        CAV("CAV", "Кіннота");

        /** Коротка назва розділу — саме її бачить гравець у меню. */
        public final String code;
        /** Повна назва — для підказок. */
        public final String title;

        Category(String code, String title) {
            this.code  = code;
            this.title = title;
        }
    }

    private static final UnitType[] VALUES = values();

    public final Category category;
    public final String   title;
    public final int      cost;

    /**
     * Яку частку армії бот хоче бачити цим типом.
     *
     * <p>Тут, а не константами в боті: частки треба ПЕРЕЛІЧИТИ всі разом, і
     * доки вони жили в {@code TacticalBrain}, кожен новий тип означав нову
     * константу плюс правку арифметики недобору. Тепер новий рядок цього enum
     * — уся потрібна зміна: частки дозволених типів нормуються самі.
     */
    public final float    share;

    UnitType(Category category, String title, int cost, float share) {
        this.category = category;
        this.title    = title;
        this.cost     = cost;
        this.share    = share;
    }

    /** Тип за номером із команди. Невідомий номер → {@code null}, а не виняток. */
    public static UnitType byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    /**
     * Створити юніта цього типу. Єдине місце, де тип перетворюється на клас.
     *
     * @param owner номер гравця, який ним командуватиме; сторона задається
     *              окремо, бо за одну сторону грає кілька гравців
     */
    public Unit create(Team team, int owner, long rawX, long rawY) {
        switch (this) {
            case ARTILLERY:     return new Artillery(team, owner, rawX, rawY);
            case CAVALRY:       return new Cavalry(team, owner, rawX, rawY);
            case HEAVY_CAVALRY: return new HeavyCavalry(team, owner, rawX, rawY);
            case LIGHT_INFANTRY:return new LightInfantry(team, owner, rawX, rawY);
            default:            return new Infantry(team, owner, rawX, rawY);
        }
    }

    /**
     * Картинка типу для сторони — потрібна привиду під курсором, який
     * малюється ДО того, як юніт існує.
     */
    public String texturePath(Team team) {
        switch (this) {
            case ARTILLERY: return team == Team.PLAYER ? "units/artillery_player.png"
                                                       : "units/artillery_enemy.png";
            case CAVALRY:   return team == Team.PLAYER ? "units/light_cavalry_player.png"
                                                       : "units/light_cavalry_enemy.png";
            case HEAVY_CAVALRY:
                            return team == Team.PLAYER ? "units/heavy_cavalry_player.png"
                                                       : "units/heavy_cavalry_enemy.png";
            case LIGHT_INFANTRY:
                            return team == Team.PLAYER ? "units/light_infantry_player.png"
                                                       : "units/light_infantry_enemy.png";
            default:        return team == Team.PLAYER ? "units/infantry_player.png"
                                                       : "units/infantry_enemy.png";
        }
    }

    /** Розмір спрайта в пікселях — теж для привида. */
    public float sizePx() {
        switch (this) {
            case ARTILLERY:     return Artillery.ART_SIZE;
            case CAVALRY:       return Cavalry.CAV_SIZE;
            case HEAVY_CAVALRY: return HeavyCavalry.HCAV_SIZE;
            case LIGHT_INFANTRY:return LightInfantry.LINF_WIDTH;
            default:            return Infantry.INF_SIZE;
        }
    }

    /**
     * Висота спрайта в пікселях. Типово збігається з {@link #sizePx()} — усі
     * картинки, крім легкої піхоти, квадратні; привид мусить показувати ту саму
     * фігуру, що й юніт, інакше замовлене й висаджене виглядають по-різному.
     */
    public float heightPx() {
        switch (this) {
            case LIGHT_INFANTRY: return LightInfantry.LINF_HEIGHT;
            default:             return sizePx();
        }
    }
}
