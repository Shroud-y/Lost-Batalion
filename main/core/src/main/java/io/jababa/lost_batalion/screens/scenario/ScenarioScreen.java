package io.jababa.lost_batalion.screens.scenario;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Scaling;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.screens.BaseScreen;
import io.jababa.lost_batalion.screens.MainMenuScreen;
import io.jababa.lost_batalion.ui.UIFactory;

public class ScenarioScreen extends BaseScreen {

    private static final float CARD_WIDTH = 220f;
    private static final float CARD_HEIGHT = 280f;
    private static final float CARD_PAD = 16f;
    private static final float CARD_IMG_H = 140f;
    private static final float CARD_BTN_H = 34f;
    private static final float TOP_BAR_H = 48f;

    private final Array<Texture> ownedTextures = new Array<>();

    public ScenarioScreen(LostBatalion game) {
        super(game);
    }

    private Array<ScenarioCard> buildScenarios() {
        Array<ScenarioCard> list = new Array<>();
        list.add(new ScenarioCard(
            "zhovti_vody",
            "Zhovti Vody",
            "Coming soon...",
            "scenarios/Zhovty_Vodu.png",
            "scenarios/Zhovty_Vodu_mask.png",        // маска лісу
            "scenarios/Zhovty_Vodu_terrain_mask.png" // маска топографії
        ));
        return list;
    }


    @Override
    protected void buildUI() {
        disposeOwnedTextures();

        TextButton btnBack = new TextButton("< Back", UIFactory.createSmallButtonStyle());
        btnBack.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.setScreen(new MainMenuScreen(game));
            }
        });

        Label title = new Label("Select Scenario", UIFactory.createScreenTitleStyle());

        Table topBar = new Table();
        topBar.pad(0, 8, 0, 8);

        topBar.add(btnBack).width(90f).height(TOP_BAR_H).left();
        topBar.add(title).expandX().center();
        topBar.add().width(90f);

        Table grid = new Table();
        grid.top().left().pad(CARD_PAD);

        for (ScenarioCard card : buildScenarios()) {
            grid.add(buildCardActor(card))
                .size(CARD_WIDTH, CARD_HEIGHT)
                .pad(CARD_PAD);
        }

        grid.add().expandX();

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setScrollingDisabled(false, true);
        scroll.setFadeScrollBars(false);
        scroll.setFlickScroll(true);
        scroll.setOverscroll(false, false);

        Table root = new Table();
        root.setFillParent(true);
        root.top();

        root.add(topBar).expandX().fillX().height(TOP_BAR_H).padTop(8f).row();
        root.add(createSeparator()).expandX().fillX().height(1f).padBottom(4f).row();
        root.add(scroll).expand().fill().pad(0, 4, 4, 4);

        stage.addActor(root);
    }

    private Actor buildCardActor(ScenarioCard card) {
        Table cardTable = new Table();
        cardTable.setBackground(UIFactory.createColorDrawable(
            new Color(0.13f, 0.13f, 0.20f, 1f)));
        cardTable.top();

        Texture previewTex = loadPreview(card);
        Image preview = new Image(new TextureRegionDrawable(previewTex));
        preview.setScaling(Scaling.fill);

        Label nameLabel = new Label(card.title, UIFactory.createCardTitleStyle());
        nameLabel.setEllipsis(true);

        Label descLabel = new Label(card.description, UIFactory.createCardDescStyle());
        descLabel.setWrap(true);

        TextButton btnSelect = new TextButton("Select", UIFactory.createSmallButtonStyle());
        btnSelect.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                game.setScreen(new io.jababa.lost_batalion.screens.game.GameScreen(game, card));
            }
        });

        cardTable.add(preview).expandX().fillX().height(CARD_IMG_H).row();
        cardTable.add(nameLabel).expandX().fillX().pad(6f, 8f, 2f, 8f).row();
        cardTable.add(descLabel).expandX().fillX().pad(0f, 8f, 0f, 8f).expandY().top().row();
        cardTable.add(btnSelect).expandX().fillX().height(CARD_BTN_H).pad(4f, 8f, 8f, 8f).row();

        return cardTable;
    }

    private Image createSeparator() {
        Image line = new Image(UIFactory.createColorDrawable(
            new Color(UIFactory.COLOR_ACCENT.r, UIFactory.COLOR_ACCENT.g,
                UIFactory.COLOR_ACCENT.b, 0.4f)));
        return line;
    }

    private Texture loadPreview(ScenarioCard card) {
        if (card.texturePath != null && Gdx.files.internal(card.texturePath).exists()) {
            Texture tex = new Texture(Gdx.files.internal(card.texturePath));
            ownedTextures.add(tex);
            return tex;
        }
        return buildPlaceholderTexture();
    }

    private Texture buildPlaceholderTexture() {
        int w = 512, h = 256;
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        pm.setColor(0.18f, 0.16f, 0.22f, 1f);
        pm.fill();
        pm.setColor(UIFactory.COLOR_ACCENT);
        pm.drawRectangle(0, 0, w, h);
        pm.drawRectangle(1, 1, w - 2, h - 2);
        pm.setColor(0.35f, 0.30f, 0.18f, 1f);
        pm.drawLine(10, 10, w - 10, h - 10);
        pm.drawLine(w - 10, 10, 10, h - 10);
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
