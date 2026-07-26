package io.jababa.lost_batalion.screens.multiplayer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.MultiplayerServices;
import io.jababa.lost_batalion.screens.BaseScreen;
import io.jababa.lost_batalion.screens.scenario.ScenarioCard;
import io.jababa.lost_batalion.screens.scenario.ScenarioCatalog;
import io.jababa.lost_batalion.ui.UIFactory;

/** Параметри нового лоббі: назва, карта, кількість гравців. */
public class CreateLobbyScreen extends BaseScreen {

    private static final float LABEL_W = 160f;
    private static final float FIELD_W = 300f;
    private static final float ROW_H   = 44f;

    private final String nick;

    private String lobbyName;
    private String scenarioId;
    private int    maxPlayers = NetConfig.MAX_PLAYERS;
    private String error = "";

    private Label      errorLabel;
    private Label      scenarioLabel;
    private TextButton createBtn;

    public CreateLobbyScreen(LostBatalion game, String nick) {
        super(game);
        this.nick       = nick;
        this.lobbyName  = defaultLobbyName(nick);
        this.scenarioId = ScenarioCatalog.all().first().id;
    }

    private static String defaultLobbyName(String nick) {
        String base = "Лоббі " + nick;
        return base.length() > NetConfig.MAX_LOBBY_NAME_LENGTH
            ? base.substring(0, NetConfig.MAX_LOBBY_NAME_LENGTH)
            : base;
    }

    @Override
    protected void buildUI() {
        Table root = new Table();
        root.setFillParent(true);
        root.top().pad(16f);

        root.add(buildTopBar()).expandX().fillX().padBottom(24f).colspan(2).row();

        Table form = new Table();
        form.add(new Label("Назва лоббі", UIFactory.createBodyStyle())).width(LABEL_W).left();
        form.add(buildNameField()).width(FIELD_W).height(ROW_H).left().row();

        form.add(new Label("Карта", UIFactory.createBodyStyle()))
            .width(LABEL_W).left().padTop(12f);
        form.add(buildScenarioRow()).width(FIELD_W).height(ROW_H).left().padTop(12f).row();

        form.add(new Label("Гравців", UIFactory.createBodyStyle()))
            .width(LABEL_W).left().padTop(12f);
        form.add(buildPlayersRow()).width(FIELD_W).left().padTop(12f).row();

        root.add(form).expandX().center().colspan(2).row();

        errorLabel = new Label(error, UIFactory.createErrorStyle());
        root.add(errorLabel).expandX().center().padTop(14f).colspan(2).row();

        root.add(buildActions()).expand().bottom().colspan(2);

        stage.addActor(root);
        refreshEnabledState();
    }

    private Table buildTopBar() {
        TextButton back = new TextButton("< Назад", UIFactory.createSmallButtonStyle());
        back.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                game.setScreen(new MultiplayerScreen(game));
            }
        });

        Table bar = new Table();
        bar.add(back).width(110f).height(38f).left();
        bar.add(new Label("СТВОРИТИ ЛОББІ", UIFactory.createScreenTitleStyle())).expandX().center();
        bar.add().width(110f);
        return bar;
    }

    private TextField buildNameField() {
        TextField field = new TextField(lobbyName, UIFactory.createTextFieldStyle());
        field.setMessageText("назва лоббі");
        field.setMaxLength(NetConfig.MAX_LOBBY_NAME_LENGTH);
        field.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                lobbyName = field.getText();
                refreshEnabledState();
            }
        });
        return field;
    }

    private Table buildScenarioRow() {
        ScenarioCard current = ScenarioCatalog.byId(scenarioId);
        scenarioLabel = new Label(current.title, UIFactory.createAccentStyle());

        TextButton cycle = new TextButton("→", UIFactory.createSmallButtonStyle());
        cycle.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                ScenarioCard next = ScenarioCatalog.next(scenarioId);
                scenarioId = next.id;
                scenarioLabel.setText(next.title);
            }
        });
        // Поки карта одна, перемикати нема що — але кнопка лишається на місці,
        // щоб при додаванні другої карти нічого не переверстувати.
        boolean single = ScenarioCatalog.all().size <= 1;
        cycle.setDisabled(single);
        cycle.getColor().a = single ? 0.45f : 1f;

        Table row = new Table();
        row.add(scenarioLabel).left().expandX();
        row.add(cycle).width(44f).height(ROW_H).right();
        return row;
    }

    private Table buildPlayersRow() {
        Table row = new Table();
        row.add(new Label(maxPlayers + " (1 на 1)", UIFactory.createBodyStyle())).left();
        row.add(new Label("  режим на більше гравців ще не підтримується симуляцією",
                          UIFactory.createHintStyle())).left().expandX();
        return row;
    }

    private Table buildActions() {
        createBtn = new TextButton("Створити", UIFactory.createMenuButtonStyle());
        createBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (createBtn.isDisabled()) return;
                game.setScreen(new LobbyScreen(game,
                    MultiplayerServices.host(lobbyName.trim(), nick, scenarioId, maxPlayers)));
            }
        });

        Table actions = new Table();
        actions.add(createBtn).size(240f, 50f).padBottom(24f);
        return actions;
    }

    private void refreshEnabledState() {
        boolean ok = lobbyName != null && !lobbyName.trim().isEmpty();
        if (createBtn != null) {
            createBtn.setDisabled(!ok);
            createBtn.getColor().a = ok ? 1f : 0.45f;
        }
        error = ok ? "" : "Назва лоббі не може бути порожньою.";
        if (errorLabel != null) errorLabel.setText(error);
    }
}
