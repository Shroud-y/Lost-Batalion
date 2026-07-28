package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Головне меню — еталон, з якого знята решта інтерфейсу (DESIGN.md).
 *
 * <p>Колонка стоїть ліворуч, а не по центру: карта на тлі — половина того, що
 * меню повідомляє про гру, і центрований стовпчик кнопок затуляв би її саме
 * посередині, де вона найцікавіша.
 */
public class MainMenuScreen extends BaseScreen {

    private static final float COLUMN_X     = 72f;
    private static final float PLATE_WIDTH  = 330f;
    private static final float PLATE_HEIGHT = 50f;
    private static final float PLATE_GAP    = 10f;

    /** Поява довша за решту екранів: це перше, що бачить гравець. */
    private static final float FADE_IN = 0.55f;

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
    protected float fadeInDuration() {
        return FADE_IN;
    }

    @Override
    protected void buildUI() {
        plateStyle = UIFactory.createMenuPlateStyle();

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
    }

    // ── Складові ──────────────────────────────────────────────────────────

    private Table buildTitleBlock() {
        Label lost   = new Label("LOST",      UIFactory.createMenuWordStyle(UIFactory.COLOR_TEXT));
        Label batalt = new Label("BATTALION", UIFactory.createMenuWordStyle(UIFactory.COLOR_ACCENT));

        Table words = new Table();
        words.add(lost).left().row();
        words.add(batalt).left().padTop(-6f).row();   // рядки заголовка стоять щільно
        // Лінійка тягнеться по найширшому рядку — це «BATTALION».
        words.add(new Image(UIFactory.createRuleDrawable()))
             .left().height(1f).fillX().padTop(14f).row();

        // Вертикальна засічка ліворуч — те саме, чим у меню позначений пункт
        // під курсором. Заголовок і список так читаються як одна колонка.
        Image mark = new Image(UIFactory.createColorDrawable(UIFactory.COLOR_ACCENT));

        Table block = new Table();
        block.add(mark).width(4f).growY().padRight(18f);
        block.add(words).left().top();
        return block;
    }

    /**
     * Плитка меню. Підсвітка живе в {@link PlateButton} — вона плавна, тому
     * мусить тримати власний стан і не може бути звичайним {@code Button}.
     */
    private Button plate(String title, final Runnable action) {
        PlateButton button = PlateButton.plate(plateStyle, title);
        button.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) { action.run(); }
        });
        return button;
    }

    private Table buildFooter() {
        Table footer = new Table();
        footer.setFillParent(true);
        footer.bottom();

        footer.add(new Label("v" + LostBatalion.VERSION, UIFactory.createHintStyle()))
              .left().expandX().padLeft(COLUMN_X);
        footer.add(new Label("Жовті Води, 1648", UIFactory.createHintStyle()))
              .right().padRight(COLUMN_X);
        footer.padBottom(28f);
        return footer;
    }
}
