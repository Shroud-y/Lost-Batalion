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

    /** Кнопки конкретних юнітів — щоб щокадру гасити недоступні. */
    private final Array<PlateButton> unitButtons = new Array<>();
    private final Array<UnitType>    unitTypes   = new Array<>();

    private boolean menuOpen;
    /** Розгорнутий розділ; {@code null} — жоден. */
    private UnitType.Category openCategory;

    private int gold;
    /** Останній показаний прибуток — щоб перебудова меню не стирала напис. */
    private int income;

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
        root.add(menu).left().width(MENU_WIDTH).padTop(6f).row();

        stage.addActor(root);
        rebuildMenu();
    }

    /** Оновити числа. Викликати щокадру — золото змінюється в симуляції. */
    public void update(int gold, int incomePerPeriod) {
        this.gold   = gold;
        this.income = incomePerPeriod;
        goldLabel.setText(gold + " золота");
        incomeLabel.setText("+" + incomePerPeriod + " кожні 5 с");

        for (int i = 0; i < unitButtons.size; i++) {
            boolean affordable = gold >= unitTypes.get(i).cost;
            PlateButton b = unitButtons.get(i);
            b.setDisabled(!affordable);
            b.setMuted(!affordable);
        }
    }

    /** Закрити меню — після того, як тип обрано і почалась висадка. */
    public void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        openCategory = null;
        rebuildMenu();
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
            menu.add(head).left().width(MENU_WIDTH).height(ROW_HEIGHT).row();

            if (openCategory == category) addUnitsOf(category);

            // Лінійка МІЖ розділами — після останнього вона висіла б ні до чого.
            if (c < categories.length - 1) {
                menu.add(new Image(rule)).left().width(MENU_WIDTH).height(RULE_HEIGHT)
                    .padTop(3f).padBottom(3f).row();
            }
        }
        update(gold, income);
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
            menu.add(item).left().width(MENU_WIDTH).height(ROW_HEIGHT).padLeft(14f).row();

            unitButtons.add(item);
            unitTypes.add(type);
        }
    }
}
