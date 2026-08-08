package io.jababa.lost_batalion.screens.multiplayer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.MultiplayerServices;
import io.jababa.lost_batalion.screens.BaseScreen;
import io.jababa.lost_batalion.screens.scenario.ScenarioCard;
import io.jababa.lost_batalion.screens.scenario.ScenarioCatalog;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.ScreenHeader;
import io.jababa.lost_batalion.ui.UIFactory;

/** Параметри нового лоббі: назва, карта, кількість гравців. */
public class CreateLobbyScreen extends BaseScreen {

    private static final float LABEL_W = 150f;
    private static final float FIELD_W = 330f;
    private static final float ROW_H   = 42f;

    private final String nick;

    private String lobbyName;
    private String scenarioId;
    private int    maxPlayers = NetConfig.MAX_PLAYERS;
    private String error = "";

    private Label  errorLabel;
    private Label  scenarioLabel;
    private Button createBtn;

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
        Table form = new Table();
        form.setBackground(UIFactory.createPanelBackground());
        form.pad(22f, 24f, 22f, 24f);

        // Третя колонка порожня і забирає весь надлишок ширини: панель тягнеться
        // на весь екран, а поля лишаються читабельної ширини при лівому краї.
        // Без неї Table розтягнув би саме поле вводу на пів екрана.
        form.add(caption("Назва лоббі")).width(LABEL_W).left();
        form.add(buildNameField()).width(FIELD_W).height(ROW_H).left();
        form.add().expandX().row();

        form.add(caption("Карта")).width(LABEL_W).left().padTop(14f);
        form.add(buildScenarioRow()).width(FIELD_W).height(ROW_H).left().padTop(14f);
        form.add().expandX().row();

        form.add(caption("Гравців")).width(LABEL_W).left().padTop(14f);
        form.add(buildPlayersRow()).left().padTop(14f).colspan(2).growX().row();

        errorLabel = new Label(error, UIFactory.createErrorStyle());

        createBtn = PlateButton.action("СТВОРИТИ");
        createBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (createBtn.isDisabled()) return;
                game.setScreen(new LobbyScreen(game,
                    MultiplayerServices.host(lobbyName.trim(), nick, scenarioId, maxPlayers)));
            }
        });

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        root.pad(UIFactory.HEADER_TOP, UIFactory.MARGIN,
                 UIFactory.FOOTER_PAD, UIFactory.MARGIN);

        root.add(new ScreenHeader("СТВОРИТИ ЛОББІ",
                                  () -> game.setScreen(new MultiplayerScreen(game))))
            .growX().row();
        root.add(form).growX().padTop(26f).row();
        root.add(errorLabel).left().padTop(10f).row();
        // Кнопка внизу, а не одразу під формою: дія, що закриває екран, має
        // стояти на постійному місці, а форма ще виросте.
        root.add(createBtn).size(240f, 46f).left().expandY().bottom().row();

        stage.addActor(root);
        refreshEnabledState();
    }

    // ── Поля форми ────────────────────────────────────────────────────────

    private Label caption(String text) {
        return new Label(text, UIFactory.createBodyStyle());
    }

    private TextField buildNameField() {
        final TextField field = new TextField(lobbyName, UIFactory.createTextFieldStyle());
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

        // «»", а НЕ "→": стрілок у main.ttf немає, і кнопка малювалась порожнім
        // квадратом (DESIGN §3).
        final PlateButton cycle = PlateButton.action("»");
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
        row.add(new Label(maxPlayers + " (" + NetConfig.TEAM_SIZE + " на " + NetConfig.TEAM_SIZE + ")",
                          UIFactory.createBodyStyle())).left();
        row.add(new Label("зайві місця хост закриває в лоббі",
                          UIFactory.createHintStyle())).left().expandX().padLeft(12f);
        return row;
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
