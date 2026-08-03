package io.jababa.lost_batalion.screens.ui;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.Drawable;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.units.UnitType;

/**
 * Лівий верхній кут матчу: золото, прибуток і меню замовлення військ.
 *
 * <h3>Чому scene2d, а не власний рендер</h3>
 * Панель виділення й мінікарта малюються вручну, бо вони не кнопки: там сітка
 * портретів і карта, які в scene2d довелося б підробляти. Тут же звичайний
 * список з наведенням і кліком — рівно те, для чого існують {@link PlateButton}
 * і {@code UIFactory}. Переписувати їх ще раз означало б завести другий набір
 * правил вигляду, чого DESIGN §8 прямо забороняє.
 *
 * <h3>Вигляд</h3>
 * Меню розгортається вниз БЕЗ тла: під ним поле бою, на яке гравець дивиться,
 * обираючи місце висадки. Розділи відділені однопіксельною лінійкою — тією
 * самою, що під заголовками екранів. Пункт, на який не вистачає золота, не
 * зникає, а гасне: зникнення читалось би як помилка.
 */
public class CommandPanel {

    /** Ширина колонки меню. Вужче за плитку меню — це HUD, а не екран. */
    private static final float MENU_WIDTH = 176f;
    private static final float ROW_HEIGHT = 24f;
    private static final float RULE_HEIGHT = 1f;

    /** Смуга праворуч від пункту, де рахуються квадратики замовленого. */
    private static final float COUNT_ZONE  = 52f;
    /** Відступ смуги від пункту: квадратики стоять окремо, а не при самому тексті. */
    private static final float COUNT_PAD   = 22f;
    private static final float COUNT_SIZE  = 6f;
    private static final float COUNT_GAP   = 3f;
    /**
     * Скільки квадратиків малюється поштучно. Далі — квадратик і число: десяток
     * однакових крапок усе одно ніхто не рахує оком, а смуга поїхала б за край.
     */
    private static final int   COUNT_MAX_DOTS = 5;

    public interface Listener {
        /** Гравець обрав тип для висадки — далі веде привид під курсором. */
        void onSpawnSelected(UnitType type);
    }

    private final Listener listener;

    private final Label incomeLabel;
    private final Label goldLabel;
    private final PlateButton armyBtn;
    private final Table menu;

    /** Спільні заготовки: кожен виклик фабрики народжує нові текстури (DESIGN §6). */
    private final Button.ButtonStyle ghostStyle = UIFactory.createGhostStyle();
    private final Drawable rule = UIFactory.createRuleDrawable();

    /** Заливка квадратика-лічильника. Одна на всі рядки (DESIGN §6). */
    private final Drawable countMark = UIFactory.createColorDrawable(UIFactory.COLOR_ACCENT);

    /** Кнопки конкретних юнітів — щоб щокадру гасити недоступні. */
    private final Array<PlateButton> unitButtons = new Array<>();
    private final Array<UnitType>    unitTypes   = new Array<>();
    /** Смуга лічильника кожного рядка — перебудовується без перебудови меню. */
    private final Array<Table>       unitCounts  = new Array<>();

    /**
     * Що зараз висаджується і скільки штук замовлено кліками.
     *
     * <p>Панель тримає це не заради себе: без нього вона не знає, СКІЛЬКИ
     * золота вже обіцяно, і показувала б наступний клік доступним, хоча на
     * нього вже не вистачає.
     */
    private UnitType placingType;
    private int      placingCount;

    private boolean menuOpen;
    /** Розгорнутий розділ; {@code null} — жоден. */
    private UnitType.Category openCategory;

    private int gold;
    /** Останній показаний прибуток — щоб перебудова меню не стирала напис. */
    private int income;

    // Рахунок матчу тут НЕ живе: він угорі по центру екрана (GameScreen), бо це
    // стан партії, а не постачання. Ця панель — про золото й замовлення.

    public CommandPanel(Stage stage, Listener listener) {
        this.listener = listener;

        incomeLabel = new Label("", UIFactory.createHintStyle());
        goldLabel   = new Label("", UIFactory.createGoldStyle());

        armyBtn = PlateButton.action("ВІЙСЬКА");
        armyBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                menuOpen = !menuOpen;
                if (!menuOpen) openCategory = null;
                rebuildMenu();
            }
        });

        menu = new Table();
        menu.top().left();

        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(12f);

        // Прибуток НАД лічильником: спершу «скільки капає», потім «скільки є».
        root.add(incomeLabel).left().row();
        root.add(goldLabel).left().padTop(2f).row();
        root.add(armyBtn).left().size(112f, 32f).padTop(8f).row();
        // Колонка ширша за сам пункт рівно на смугу лічильника — інакше Table
        // стиснув би квадратики назад під текст.
        root.add(menu).left().width(MENU_WIDTH + COUNT_ZONE + COUNT_PAD).padTop(6f).row();

        stage.addActor(root);
        rebuildMenu();
    }

    /** Оновити числа. Викликати щокадру — золото змінюється в симуляції. */
    public void update(int gold, int incomePerPeriod) {
        this.gold   = gold;
        this.income = incomePerPeriod;
        refresh();
    }

    /**
     * Перемалювати з уже збережених чисел.
     *
     * <p>Окремо від {@link #update} заради внутрішніх викликів: висадка міняє
     * доступність кнопок, не отримуючи при цьому нового стану симуляції, і
     * передавати їй числа лише щоб вернути їх назад — марно.
     */
    private void refresh() {
        goldLabel.setText(gold + " золота");
        incomeLabel.setText("+" + income + " кожні 5 с");

        // Уже замовлене золото рахується витраченим: інакше гравець накликав би
        // п'ять рот за ціною однієї, а симуляція мовчки виконала б лише першу.
        int reserved = placingType == null ? 0 : placingType.cost * placingCount;

        for (int i = 0; i < unitButtons.size; i++) {
            boolean affordable = gold - reserved >= unitTypes.get(i).cost;
            PlateButton b = unitButtons.get(i);
            b.setDisabled(!affordable);
            b.setMuted(!affordable);
        }
    }

    /**
     * Що і скільки зараз висаджується. Кличе {@code GameScreen} на кожну зміну.
     *
     * <p>Меню при цьому НЕ згортається: замовлення кількох штук — це кілька
     * кліків по тому самому пункту, і панель, яка зникає після першого, робить
     * їх неможливими.
     */
    public void setPlacing(UnitType type, int count) {
        placingType  = type;
        placingCount = type == null ? 0 : count;
        refreshCounts();
        refresh();
    }

    /** Закрити меню. Лишається для явних дій — висадка його не чіпає. */
    public void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        openCategory = null;
        rebuildMenu();
    }

    /**
     * Перемалювати квадратики.
     *
     * <p>Окремо від {@link #rebuildMenu()} навмисно: замовлення набирається
     * кліками по ОДНІЙ кнопці, а перебудова меню знищила б її прямо під
     * курсором — підсвітка почалась би з нуля, і другий клік читався б як
     * промах по новому пункту.
     */
    private void refreshCounts() {
        for (int i = 0; i < unitCounts.size; i++) {
            Table strip = unitCounts.get(i);
            strip.clear();
            if (unitTypes.get(i) != placingType || placingCount <= 0) continue;

            int dots = Math.min(placingCount, COUNT_MAX_DOTS);
            for (int d = 0; d < dots; d++) {
                strip.add(new Image(countMark)).size(COUNT_SIZE).padRight(COUNT_GAP);
            }
            if (placingCount > COUNT_MAX_DOTS) {
                strip.add(new Label("×" + placingCount, UIFactory.createHintStyle())).left();
            }
        }
    }

    // ── Побудова ──────────────────────────────────────────────────────────

    /**
     * Перебудувати список під поточний стан розгортання.
     *
     * <p>Перебудова, а не показ/приховування готових рядків: {@code Table} і так
     * розкладає все наново при зміні видимості комірки, а тримати заготовлені
     * актори для всіх розділів означало б стежити за їхньою синхронністю з
     * каталогом військ.
     */
    private void rebuildMenu() {
        menu.clear();
        unitButtons.clear();
        unitTypes.clear();
        unitCounts.clear();
        if (!menuOpen) return;

        UnitType.Category[] categories = UnitType.Category.values();
        for (int c = 0; c < categories.length; c++) {
            final UnitType.Category category = categories[c];

            PlateButton head = PlateButton.plate(ghostStyle, category.code);
            head.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent e, Actor a) {
                    openCategory = openCategory == category ? null : category;
                    rebuildMenu();
                }
            });
            menu.add(head).left().width(MENU_WIDTH).height(ROW_HEIGHT)
                .colspan(2).row();

            if (openCategory == category) addUnitsOf(category);

            // Лінійка МІЖ розділами — після останнього вона висіла б ні до чого.
            if (c < categories.length - 1) {
                menu.add(new Image(rule)).left().width(MENU_WIDTH).height(RULE_HEIGHT)
                    .colspan(2).padTop(3f).padBottom(3f).row();
            }
        }
        refresh();
    }

    private void addUnitsOf(UnitType.Category category) {
        UnitType[] all = UnitType.values();
        for (int i = 0; i < all.length; i++) {
            final UnitType type = all[i];
            if (type.category != category) continue;

            PlateButton item = PlateButton.plate(ghostStyle, type.title + "   " + type.cost);
            item.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent e, Actor a) {
                    if (item.isDisabled()) return;
                    listener.onSpawnSelected(type);
                }
            });
            // Квадратики стоять ПОРУЧ із пунктом, у власній комірці, а не
            // всередині кнопки: підпис у ній зсувається при наведенні, і
            // лічильник їздив би разом з ним.
            Table counts = new Table();
            counts.left();

            menu.add(item).left().width(MENU_WIDTH - COUNT_ZONE).height(ROW_HEIGHT)
                .padLeft(14f);
            menu.add(counts).left().width(COUNT_ZONE).height(ROW_HEIGHT)
                .padLeft(COUNT_PAD).row();

            unitButtons.add(item);
            unitTypes.add(type);
            unitCounts.add(counts);
        }
        refreshCounts();
    }
}
