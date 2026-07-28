package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.screens.menu.MenuBackdrop;
import io.jababa.lost_batalion.screens.menu.MenuPlate;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Головне меню.
 *
 * <p>Колонка стоїть ліворуч, а не по центру: карта на тлі — половина того, що
 * меню повідомляє про гру, і центрований стовпчик кнопок затуляв би її саме
 * посередині, де вона найцікавіша.
 */
public class MainMenuScreen extends BaseScreen {

    private static final String MAP_PATH = "scenarios/Zhovty_Vodu.png";

    private static final float COLUMN_X     = 72f;
    private static final float PLATE_WIDTH  = 330f;
    private static final float PLATE_HEIGHT = 50f;
    private static final float PLATE_GAP    = 10f;

    private static final float FADE_IN = 0.55f;

    /** Колір назви пункту під курсором. */
    private static final Color ITEM_HOVER = new Color(0.96f, 0.93f, 0.84f, 1f);

    /**
     * Тло живе довше за сцену: {@code BaseScreen} перебудовує сцену на кожній
     * зміні розміру вікна, і перечитувати з диска карту 1440×1440 щоразу, коли
     * користувач тягне край вікна, — це помітні ривки на рівному місці.
     */
    private MenuBackdrop backdrop;

    /**
     * Один стиль на всі плитки. Кожен виклик фабрики створює три нові текстури,
     * а плитки відрізняються лише написом — тримати чотири однакові комплекти
     * означало б чотири зайві прив'язки текстур у кадрі.
     */
    private Button.ButtonStyle plateStyle;

    public MainMenuScreen(LostBatalion game) {
        super(game);
    }

    @Override
    protected void buildUI() {
        if (backdrop == null) backdrop = new MenuBackdrop(MAP_PATH);
        plateStyle = UIFactory.createMenuPlateStyle();
        stage.addActor(backdrop);   // розмір тло тримає саме, див. MenuBackdrop.act

        Table root = new Table();
        root.setFillParent(true);
        root.left();

        Table column = new Table();
        column.add(buildTitleBlock()).left().padBottom(34f).row();

        column.add(plate("КАМПАНІЯ", () ->
            game.setScreen(new io.jababa.lost_batalion.screens.scenario.ScenarioScreen(game))))
            .size(PLATE_WIDTH, PLATE_HEIGHT).padBottom(PLATE_GAP).row();

        column.add(plate("МУЛЬТИПЛЕЄР", () ->
            game.setScreen(new io.jababa.lost_batalion.screens.multiplayer.MultiplayerScreen(game))))
            .size(PLATE_WIDTH, PLATE_HEIGHT).padBottom(PLATE_GAP).row();

        column.add(plate("НАЛАШТУВАННЯ", () ->
            game.setScreen(new SettingsScreen(game))))
            .size(PLATE_WIDTH, PLATE_HEIGHT).padBottom(PLATE_GAP).row();

        column.add(plate("ВИЙТИ", Gdx.app::exit))
            .size(PLATE_WIDTH, PLATE_HEIGHT).row();

        root.add(column).expand().left().padLeft(COLUMN_X);
        stage.addActor(root);

        stage.addActor(buildFooter());

        // Поява: різкий показ готового меню виглядає як смикання, надто коли
        // тло вже почало рухатись.
        stage.getRoot().getColor().a = 0f;
        stage.getRoot().addAction(Actions.fadeIn(FADE_IN));
    }

    // ── Складові ──────────────────────────────────────────────────────────

    private Table buildTitleBlock() {
        Label lost   = new Label("LOST",      UIFactory.createMenuWordStyle(UIFactory.COLOR_TEXT));
        Label batalt = new Label("BATTALION", UIFactory.createMenuWordStyle(UIFactory.COLOR_ACCENT));

        Table words = new Table();
        words.add(lost).left().row();
        words.add(batalt).left().padTop(-6f).row();   // рядки заголовка стоять щільно
        // Лінійка тягнеться по найширшому рядку — це «BATTALION».
        words.add(rule()).left().height(1f).fillX().padTop(14f).row();

        // Вертикальна засічка ліворуч — те саме, чим у меню позначений вибраний
        // пункт. Заголовок і список так читаються як одна колонка.
        Image mark = new Image(UIFactory.createColorDrawable(UIFactory.COLOR_ACCENT));

        Table block = new Table();
        block.add(mark).width(4f).growY().padRight(18f);
        block.add(words).left().top();
        return block;
    }

    private Image rule() {
        return new Image(UIFactory.createColorDrawable(UIFactory.COLOR_MENU_RULE));
    }

    /**
     * Плитка меню. Підсвітка живе в {@link MenuPlate} — вона плавна, тому мусить
     * тримати власний стан і не може бути звичайним {@code Button}.
     *
     * @param action що зробити по натисканню
     */
    private Button plate(String title, Runnable action) {
        MenuPlate button = new MenuPlate(plateStyle, title,
                                         UIFactory.createMenuItemStyle(),
                                         UIFactory.menuItemRestColor(), ITEM_HOVER);
        button.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) { action.run(); }
        });
        return button;
    }

    private Table buildFooter() {
        Table footer = new Table();
        footer.setFillParent(true);
        footer.bottom();

        footer.add(new Label("v" + LostBatalion.VERSION, UIFactory.createMenuNoteStyle()))
              .left().expandX().padLeft(COLUMN_X);
        footer.add(new Label("Жовті Води, 1648", UIFactory.createMenuNoteStyle()))
              .right().padRight(COLUMN_X);
        footer.padBottom(28f);
        return footer;
    }

    @Override
    public void dispose() {
        super.dispose();
        if (backdrop != null) {
            backdrop.dispose();
            backdrop = null;
        }
    }
}
