package io.jababa.lost_batalion.screens.multiplayer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.messages.LobbyState;
import io.jababa.lost_batalion.net.messages.PlayerSlot;
import io.jababa.lost_batalion.net.messages.StartMatch;
import io.jababa.lost_batalion.screens.BaseScreen;
import io.jababa.lost_batalion.screens.game.GameScreen;
import io.jababa.lost_batalion.screens.scenario.ScenarioCatalog;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.ScreenHeader;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Кімната очікування: склад гравців, готовність, старт.
 *
 * <p>Екран нічого не вирішує сам — він показує {@link LobbyState}, який
 * прислав хост. Навіть у хоста власний список береться звідти ж, а не з
 * локальних змінних: інакше хост і гість малювали б лоббі за різними даними,
 * і розбіжність вилізла б рівно тоді, коли її найважче ловити.
 */
public class LobbyScreen extends BaseScreen {

    private static final float ROW_H = 48f;

    private final LobbySession session;

    private LobbyState state;
    private String  status = "";
    private boolean statusIsError;
    private boolean localReady;
    /** Після розриву кнопки не мають сенсу — лишається тільки вихід. */
    private boolean sessionDead;

    private Table  playerListTable;
    private Label  statusLabel;
    private Label  mapLabel;
    private PlateButton actionBtn;

    private Label.LabelStyle hintStyle;
    private Label.LabelStyle errorStyle;
    private Label.LabelStyle bodyStyle;
    private Label.LabelStyle accentStyle;

    private final LobbySession.Listener listener = new LobbySession.Adapter() {
        @Override public void onLobbyState(LobbyState newState) {
            state = newState;
            PlayerSlot own = newState == null ? null : newState.findSlot(session.getLocalPlayerId());
            if (own != null) localReady = own.ready;
            rebuildPlayerRows();
            refreshControls();
        }

        @Override public void onMatchStarting(StartMatch start) {
            setStatus("Матч стартує…", false);
            launchMatch(start);
        }

        @Override public void onError(String message) {
            setStatus(message, true);
        }

        @Override public void onDisconnected(String reason) {
            sessionDead = true;
            setStatus(reason, true);
            refreshControls();
        }
    };

    public LobbyScreen(LostBatalion game, LobbySession session) {
        super(game);
        this.session = session;
        this.state   = session.getState();
    }

    @Override
    protected void buildUI() {
        hintStyle   = UIFactory.createHintStyle();
        errorStyle  = UIFactory.createErrorStyle();
        bodyStyle   = UIFactory.createBodyStyle();
        accentStyle = UIFactory.createAccentStyle();

        playerListTable = new Table();
        playerListTable.top();

        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(16f, 18f, 16f, 18f);
        panel.top();
        panel.add(new Label("ГРАВЦІ", UIFactory.createAccentStyle())).left().row();
        panel.add(new Image(UIFactory.createRuleDrawable()))
             .height(1f).growX().padTop(12f).padBottom(10f).row();
        panel.add(playerListTable).growX().top().row();

        statusLabel = new Label(status, statusIsError ? errorStyle : hintStyle);

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        root.pad(UIFactory.HEADER_TOP, UIFactory.MARGIN,
                 UIFactory.FOOTER_PAD, UIFactory.MARGIN);

        String title = state != null && state.lobbyName != null ? state.lobbyName : "ЛОББІ";
        root.add(new ScreenHeader(title, () -> {
            session.leave();
            game.setScreen(new MultiplayerScreen(game));
        })).growX().row();

        root.add(buildInfoRow()).growX().padTop(16f).row();
        root.add(panel).growX().padTop(14f).row();
        root.add(statusLabel).left().padTop(12f).row();
        root.add(buildActions()).left().expandY().bottom().row();

        stage.addActor(root);

        rebuildPlayerRows();
        refreshControls();
    }

    // ── Секції ────────────────────────────────────────────────────────────

    private Table buildInfoRow() {
        String scenarioId = state != null ? state.scenarioId : null;
        mapLabel = new Label("КАРТА: " + ScenarioCatalog.byId(scenarioId).title, hintStyle);

        String role = session.isHost() ? "ТИ ХОСТ" : "ТИ ГІСТЬ";

        Table row = new Table();
        row.add(mapLabel).left();
        row.add(new Label("·", hintStyle)).left().padLeft(12f).padRight(12f);
        row.add(new Label(role, hintStyle)).left();

        // Ключ кімнати — тільки хосту й тільки в мережах, які його мають.
        // Гостю він ні до чого: гість ключ ВВОДИТЬ на екрані пошуку, а тут уже
        // знайшов кімнату. Акцентом, бо це єдине на екрані, що треба
        // продиктувати вголос.
        String key = io.jababa.lost_batalion.net.api.MultiplayerServices.current().hostedRoomKey();
        if (session.isHost() && key != null && !key.isEmpty()) {
            row.add(new Label("·", hintStyle)).left().padLeft(12f).padRight(12f);
            row.add(new Label("КЛЮЧ КІМНАТИ", hintStyle)).left().padRight(10f);
            row.add(new Label(key, accentStyle)).left();
        }

        row.add().expandX();
        return row;
    }

    private Table buildActions() {
        actionBtn = PlateButton.action(session.isHost() ? "СТАРТ" : "ГОТОВИЙ");
        actionBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (actionBtn.isDisabled()) return;
                if (session.isHost()) {
                    session.startMatch();
                } else {
                    localReady = !localReady;
                    session.setReady(localReady);
                    refreshControls();
                }
            }
        });

        Table actions = new Table();
        actions.add(actionBtn).size(240f, 46f);
        return actions;
    }

    // ── Список гравців ────────────────────────────────────────────────────

    private void rebuildPlayerRows() {
        if (playerListTable == null) return;
        playerListTable.clear();

        if (state == null) {
            playerListTable.add(new Label("Підключення…", hintStyle)).padTop(16f).row();
            return;
        }

        for (int i = 0; i < state.slots.size(); i++) {
            playerListTable.add(buildPlayerRow(state.slots.get(i)))
                .growX().height(ROW_H).padBottom(6f).row();
        }

        int free = state.maxPlayers - state.slots.size();
        for (int i = 0; i < free; i++) {
            Table empty = new Table();
            empty.setBackground(UIFactory.createRowBackground(false));
            empty.add(new Label("вільний слот", hintStyle)).left().padLeft(12f).expandX();
            playerListTable.add(empty).growX().height(ROW_H).padBottom(6f).row();
        }
    }

    private Table buildPlayerRow(PlayerSlot slot) {
        boolean isLocal = slot.playerId == session.getLocalPlayerId();

        Table row = new Table();
        row.setBackground(UIFactory.createRowBackground(isLocal));
        row.pad(4f, 12f, 4f, 12f);

        row.add(new Label("#" + slot.playerId, hintStyle)).left().width(40f);
        row.add(new Label(slot.nick, isLocal ? accentStyle : bodyStyle)).left().width(220f);
        row.add(new Label(slot.host ? "хост" : "", hintStyle)).left().width(70f);

        String stateText;
        if (!slot.connected)   stateText = "звʼязок втрачено";
        else if (slot.host)    stateText = "готовий";
        else if (slot.ready)   stateText = "готовий";
        else                   stateText = "не готовий";

        row.add(new Label(stateText, slot.connected && slot.ready ? accentStyle : hintStyle))
           .right().expandX();
        return row;
    }

    // ── Стан кнопок ───────────────────────────────────────────────────────

    private void refreshControls() {
        if (mapLabel != null && state != null) {
            mapLabel.setText("КАРТА: " + ScenarioCatalog.byId(state.scenarioId).title);
        }
        if (actionBtn == null) return;

        boolean enabled;
        if (sessionDead) {
            enabled = false;
        } else if (session.isHost()) {
            // Старт лише коли в лоббі є з ким грати і всі підтвердили готовність.
            enabled = state != null && state.allReady();
        } else {
            enabled = session.isConnected();
            actionBtn.setText(localReady ? "НЕ ГОТОВИЙ" : "ГОТОВИЙ");
        }

        actionBtn.setDisabled(!enabled);
        actionBtn.getColor().a = enabled ? 1f : 0.45f;

        if (!sessionDead && session.isHost() && state != null && !state.allReady()) {
            setStatus(state.slots.size() < 2
                ? "Чекаємо на другого гравця."
                : "Чекаємо, поки всі підтвердять готовність.", false);
        }
    }

    private void setStatus(String text, boolean error) {
        status = text;
        statusIsError = error;
        if (statusLabel != null) {
            statusLabel.setText(text);
            statusLabel.setStyle(error ? errorStyle : hintStyle);
        }
    }

    // ── Життєвий цикл ─────────────────────────────────────────────────────

    @Override
    public void render(float delta) {
        // Мережеві події приходять у чужому потоці; тут вони перетворюються на
        // зміни віджетів — у потоці рендеру, єдиному, де це дозволено.
        session.pump(listener);

        // Серед цих подій є StartMatch: він перемикає екран, а перехід одразу
        // звільняє цей. Малювати вже нічого.
        if (stage == null) return;

        super.render(delta);
    }

    private void launchMatch(StartMatch start) {
        // Параметри матчу мусять збігатися побітово: різний tick rate або input
        // delay означає, що збірки різні, і матч розсинхронізується не одразу,
        // а через хвилину — коли причину вже не видно.
        if (start.tickRate != NetConfig.TICK_RATE
            || start.inputDelayTicks != NetConfig.INPUT_DELAY_TICKS) {
            setStatus("Несумісні параметри матчу — у хоста інша версія гри.", true);
            return;
        }
        NetConfig.setChecksumIntervalTicks(start.checksumIntervalTicks);

        MatchTransport transport = session.openMatch(start);
        if (transport == null) {
            setStatus("Не вдалось відкрити канал матчу.", true);
            return;
        }

        // Seed і склад гравців приїхали від хоста — тепер симуляція справді їх
        // використовує: однаковий RNG і однакова прив'язка армій до playerId.
        game.setScreen(new GameScreen(game, ScenarioCatalog.byId(start.scenarioId),
                                      start.rngSeed, transport));
    }
}
