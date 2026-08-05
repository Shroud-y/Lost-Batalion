package io.jababa.lost_batalion.screens.scenario;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Scaling;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ai.Difficulty;
import io.jababa.lost_batalion.screens.BaseScreen;
import io.jababa.lost_batalion.screens.MainMenuScreen;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.ScreenHeader;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Вибір сценарію.
 *
 * <p>Картки стоять зліва направо від того самого поля, що й колонка головного
 * меню — перехід між екранами не має зсувати вміст (DESIGN §4).
 */
public class ScenarioScreen extends BaseScreen {

    private static final float CARD_WIDTH  = 300f;
    private static final float CARD_HEIGHT = 336f;
    private static final float CARD_GAP    = 20f;
    /** Висота прев'ю карти в картці. */
    private static final float PREVIEW_H   = 168f;
    private static final float SELECT_H    = 38f;

    /**
     * Прев'ю притемнене: у повну яскравість воно перебиває і заголовок картки,
     * і саме тло екрана — а це список, а не галерея.
     */
    private static final Color PREVIEW_TINT = new Color(0.74f, 0.74f, 0.74f, 1f);

    private final Array<Texture> ownedTextures = new Array<>();

    public ScenarioScreen(LostBatalion game) {
        super(game);
    }

    @Override
    protected void buildUI() {
        disposeOwnedTextures();

        Table grid = new Table();
        grid.top().left();
        for (ScenarioCard card : ScenarioCatalog.all()) {
            grid.add(buildCard(card)).size(CARD_WIDTH, CARD_HEIGHT).padRight(CARD_GAP).top();
        }
        grid.add().expandX();   // притискає картки вліво

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setScrollingDisabled(false, true);
        scroll.setFadeScrollBars(false);
        scroll.setOverscroll(false, false);

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        root.pad(UIFactory.HEADER_TOP, UIFactory.MARGIN,
                 UIFactory.FOOTER_PAD, UIFactory.MARGIN);

        root.add(new ScreenHeader("СЦЕНАРІЇ",
                                  () -> game.setScreen(new MainMenuScreen(game))))
            .growX().row();
        root.add(buildOpponentRow()).left().padTop(18f).row();
        root.add(scroll).grow().padTop(20f).row();

        stage.addActor(root);
    }

    // ── Супротивник ───────────────────────────────────────────────────────

    /**
     * Вибір рівня бота.
     *
     * <p>Один перемикач на екран, а не по одному в кожній картці: рівень — це
     * властивість матчу, а не сценарію, і повторений у трьох картках він читався
     * б як три різні налаштування.
     *
     * <p>Кнопка ЦИКЛІЧНА, без стрілок: у {@code main.ttf} немає ні {@code ←},
     * ні {@code →} (DESIGN §3), а гільмети тут означали б два різні напрямки
     * там, де дія одна. Тому напрямок не позначається взагалі — клік просто
     * веде до наступного рівня.
     */
    private Actor buildOpponentRow() {
        final PlateButton cycle = PlateButton.action(opponentLabel());
        cycle.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                Difficulty[] all = Difficulty.values();
                Difficulty current = Difficulty.byName(LostBatalion.Settings.getBotDifficulty());
                Difficulty next = all[(current.ordinal() + 1) % all.length];
                LostBatalion.Settings.setBotDifficulty(next.name());
                cycle.setText(opponentLabel());
            }
        });

        Table row = new Table();
        row.add(cycle).size(300f, 40f);
        return row;
    }

    private static String opponentLabel() {
        return "СУПРОТИВНИК: " + Difficulty.byName(LostBatalion.Settings.getBotDifficulty()).title;
    }

    // ── Картка ────────────────────────────────────────────────────────────

    private Actor buildCard(final ScenarioCard card) {
        Image preview = new Image(new TextureRegionDrawable(loadPreview(card)));
        preview.setScaling(Scaling.fill);
        preview.setColor(PREVIEW_TINT);

        // Scaling.fill свідомо вилазить за межі комірки — без обрізання прев'ю
        // накрило б рамку картки. Container уміє те, чого не вміє Table.
        Container<Image> frame = new Container<>(preview);
        frame.setClip(true);
        frame.fill();

        Label title = new Label(card.title.toUpperCase(), UIFactory.createCardTitleStyle());
        title.setEllipsis(true);

        Label desc = new Label(card.description, UIFactory.createCardDescStyle());
        desc.setWrap(true);

        PlateButton select = PlateButton.action("ОБРАТИ");
        select.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.setScreen(new io.jababa.lost_batalion.screens.game.GameScreen(game, card));
            }
        });

        Table cardTable = new Table();
        cardTable.setBackground(UIFactory.createPanelBackground());
        cardTable.top();

        // Прев'ю впритул до рамки, решта — з полем: картинка є краєм картки,
        // а текст усередині неї.
        cardTable.add(frame).growX().height(PREVIEW_H).pad(1f, 1f, 0f, 1f).row();
        cardTable.add(title).left().growX().pad(14f, 16f, 6f, 16f).row();
        cardTable.add(desc).left().growX().pad(0f, 16f, 0f, 16f).growY().top().row();
        cardTable.add(select).growX().height(SELECT_H).pad(12f, 16f, 16f, 16f).row();
        return cardTable;
    }

    // ── Прев'ю ────────────────────────────────────────────────────────────

    private Texture loadPreview(ScenarioCard card) {
        if (card.texturePath != null && Gdx.files.internal(card.texturePath).exists()) {
            Texture tex = new Texture(Gdx.files.internal(card.texturePath));
            tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            ownedTextures.add(tex);
            return tex;
        }
        return buildPlaceholderTexture();
    }

    /** Заглушка, коли карти немає: перекреслений прямокутник у кольорах теми. */
    private Texture buildPlaceholderTexture() {
        int w = 512, h = 256;
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        pm.setColor(0.047f, 0.059f, 0.075f, 1f);
        pm.fill();
        pm.setColor(UIFactory.COLOR_MENU_RULE);
        pm.drawRectangle(0, 0, w, h);
        pm.drawLine(0, 0, w, h);
        pm.drawLine(w, 0, 0, h);

        Texture tex = new Texture(pm);
        pm.dispose();
        ownedTextures.add(tex);
        return tex;
    }

    private void disposeOwnedTextures() {
        for (Texture tex : ownedTextures) tex.dispose();
        ownedTextures.clear();
    }

    @Override
    public void dispose() {
        disposeOwnedTextures();
        super.dispose();
    }
}
