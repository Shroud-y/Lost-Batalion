package io.jababa.lost_batalion.screens.multiplayer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.DiscoveredLobby;
import io.jababa.lost_batalion.net.api.LobbyDirectory;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.api.MultiplayerServices;
import io.jababa.lost_batalion.screens.BaseScreen;
import io.jababa.lost_batalion.screens.MainMenuScreen;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.ScreenHeader;
import io.jababa.lost_batalion.ui.UIFactory;

import java.util.List;

/**
 * Головний екран мультиплеєра: нік, список знайдених лоббі, пряме підключення.
 *
 * <p>Нік обов'язковий — без нього кнопки переходу неактивні. Не тому, що так
 * гарніше, а тому, що нік їде в мережу як ідентифікатор гравця у всіх
 * повідомленнях (хто десинхронізувався, хто відвалився), і порожній рядок
 * зробив би ці повідомлення нечитабельними.
 *
 * <p>Стан екрана (нік, введена адреса, повідомлення) живе в полях, а не у
 * віджетах: {@link BaseScreen} перебудовує сцену на кожну зміну розміру вікна,
 * і все, що лежить лише у віджеті, при цьому зникло б.
 */
public class MultiplayerScreen extends BaseScreen {

    private static final float FIELD_WIDTH = 300f;
    private static final float FIELD_H     = 40f;
    private static final float ACTION_W    = 210f;
    private static final float ACTION_H    = 42f;
    private static final float ROW_H       = 46f;

    private final LobbyDirectory directory = MultiplayerServices.createDirectory();

    // Стан, що переживає перебудову сцени
    private String nick;
    private String directAddress = "";
    private String message       = "";
    private boolean messageIsError;

    // Віджети поточної сцени
    private Table  lobbyListTable;
    private Button createBtn;
    private Button directJoinBtn;
    private Label  messageLabel;
    private Label  scanLabel;

    /**
     * Стилі створюються раз на побудову сцени і перевикористовуються.
     * UIFactory на кожен виклик генерує новий FreeType-шрифт із текстурою —
     * робити це на кожне натискання клавіші в полі ніка не можна. З тієї ж
     * причини стиль кнопок рядків спільний: список перебудовується щоразу, коли
     * в мережі змінився склад лоббі.
     */
    private Label.LabelStyle hintStyle;
    private Label.LabelStyle errorStyle;
    private Label.LabelStyle bodyStyle;
    private Button.ButtonStyle actionStyle;

    /** Підпис поточного списку — щоб не перебудовувати рядки щокадру. */
    private String renderedListSignature = "";

    public MultiplayerScreen(LostBatalion game) {
        super(game);
        this.nick = LostBatalion.Settings.getNick();
    }

    @Override
    public void show() {
        super.show();
        directory.start();
    }

    @Override
    public void hide() {
        directory.stop();
        super.hide();
    }

    @Override
    public void dispose() {
        directory.stop();
        super.dispose();
    }

    @Override
    protected void buildUI() {
        renderedListSignature = "";
        hintStyle   = UIFactory.createHintStyle();
        errorStyle  = UIFactory.createErrorStyle();
        bodyStyle   = UIFactory.createBodyStyle();
        actionStyle = UIFactory.createActionStyle();

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        root.pad(UIFactory.HEADER_TOP, UIFactory.MARGIN,
                 UIFactory.FOOTER_PAD, UIFactory.MARGIN);

        root.add(new ScreenHeader("МУЛЬТИПЛЕЄР",
                                  () -> game.setScreen(new MainMenuScreen(game))))
            .growX().row();

        root.add(buildNickRow()).growX().padTop(22f).row();

        messageLabel = new Label(message, messageIsError ? errorStyle : hintStyle);
        root.add(messageLabel).left().padTop(8f).row();

        root.add(buildLobbyPanel()).grow().padTop(14f).row();
        root.add(buildBottomBar()).growX().padTop(14f).row();

        stage.addActor(root);

        rebuildLobbyRows();
        refreshEnabledState();
    }

    // ── Секції ────────────────────────────────────────────────────────────

    private Table buildNickRow() {
        final TextField nickField = new TextField(nick, UIFactory.createTextFieldStyle());
        nickField.setMessageText("введи нік");
        nickField.setMaxLength(NetConfig.MAX_NICK_LENGTH);
        nickField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                nick = nickField.getText();
                LostBatalion.Settings.setNick(nick);
                refreshEnabledState();
            }
        });

        Table row = new Table();
        row.add(new Label("НІК", UIFactory.createHintStyle())).padRight(14f);
        row.add(nickField).width(FIELD_WIDTH).height(FIELD_H).left();
        row.add().expandX();
        return row;
    }

    /** Список лоббі в панелі: заголовок, лінійка, прокрутка. */
    private Table buildLobbyPanel() {
        PlateButton refresh = PlateButton.action(actionStyle, "ОНОВИТИ");
        refresh.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                directory.refresh();
                setMessage("Сканую локальну мережу…", false);
            }
        });

        scanLabel = new Label("", hintStyle);

        Table header = new Table();
        header.add(new Label("ДОСТУПНІ ЛОББІ", UIFactory.createAccentStyle())).left();
        header.add(scanLabel).left().padLeft(14f);
        header.add().expandX();
        header.add(refresh).width(130f).height(34f).right();

        lobbyListTable = new Table();
        lobbyListTable.top();

        ScrollPane scroll = new ScrollPane(lobbyListTable);
        scroll.setScrollingDisabled(true, false);
        scroll.setFadeScrollBars(false);
        scroll.setOverscroll(false, false);

        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(16f, 18f, 16f, 18f);
        panel.add(header).growX().row();
        panel.add(new Image(UIFactory.createRuleDrawable()))
             .height(1f).growX().padTop(12f).padBottom(10f).row();
        panel.add(scroll).grow().row();
        return panel;
    }

    private Table buildBottomBar() {
        final TextField addressField = new TextField(directAddress, UIFactory.createTextFieldStyle());
        addressField.setMessageText("192.168.0.5");
        addressField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                directAddress = addressField.getText();
                refreshEnabledState();
            }
        });

        directJoinBtn = PlateButton.action(actionStyle, "ПІДКЛЮЧИТИСЬ");
        directJoinBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (directJoinBtn.isDisabled()) return;
                openLobby(MultiplayerServices.joinByAddress(directAddress.trim(), trimmedNick()));
            }
        });

        createBtn = PlateButton.action("СТВОРИТИ ЛОББІ");
        createBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (createBtn.isDisabled()) return;
                game.setScreen(new CreateLobbyScreen(game, trimmedNick()));
            }
        });

        Table direct = new Table();
        direct.add(new Label("ПРЯМЕ ПІДКЛЮЧЕННЯ", hintStyle)).padRight(12f);
        direct.add(addressField).width(190f).height(38f);
        direct.add(directJoinBtn).width(160f).height(38f).padLeft(8f);

        Table bar = new Table();
        bar.add(direct).left();
        bar.add().expandX();
        bar.add(createBtn).size(ACTION_W, ACTION_H).right();
        return bar;
    }

    // ── Список лоббі ──────────────────────────────────────────────────────

    @Override
    public void render(float delta) {
        super.render(delta);
        if (stage == null) return;   // екран уже звільнено

        if (scanLabel != null) {
            scanLabel.setText(directory.isScanning() ? "сканую…" : "");
        }

        String signature = buildSignature(directory.getLobbies());
        if (!signature.equals(renderedListSignature)) {
            renderedListSignature = signature;
            rebuildLobbyRows();
        }
    }

    /**
     * Рядки перебудовуються лише коли склад списку справді змінився: інакше
     * кожен кадр створював би десятки актор-об'єктів, а разом з ними — нові
     * шрифти й текстури через UIFactory.
     */
    private String buildSignature(List<DiscoveredLobby> lobbies) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lobbies.size(); i++) {
            DiscoveredLobby l = lobbies.get(i);
            sb.append(l.address).append('|')
              .append(l.info.lobbyName).append('|')
              .append(l.info.playerCount).append('/')
              .append(l.info.maxPlayers).append('|')
              .append(l.info.status).append(';');
        }
        return sb.toString();
    }

    private void rebuildLobbyRows() {
        if (lobbyListTable == null) return;
        lobbyListTable.clear();

        List<DiscoveredLobby> lobbies = directory.getLobbies();

        if (lobbies.isEmpty()) {
            String hint = MultiplayerServices.isNetworkingAvailable()
                ? "Лоббі не знайдено. Хост має бути в тій самій локальній мережі —\nінакше підключайся напряму за IP."
                : "Мережевий шар ще не підключено (етап 3).\nСтворити лоббі можна вже зараз — воно відкриється локально.";
            Label empty = new Label(hint, hintStyle);
            empty.setAlignment(com.badlogic.gdx.utils.Align.center);
            lobbyListTable.add(empty).expandX().center().padTop(28f).row();
            return;
        }

        for (int i = 0; i < lobbies.size(); i++) {
            lobbyListTable.add(buildLobbyRow(lobbies.get(i)))
                .growX().height(ROW_H).padBottom(6f).row();
        }
    }

    private Table buildLobbyRow(final DiscoveredLobby lobby) {
        String reason = lobby.unjoinableReason(NetConfig.PROTOCOL_VERSION);
        final boolean joinable = reason == null;

        Table row = new Table();
        row.setBackground(UIFactory.createRowBackground(false));
        row.pad(4f, 12f, 4f, 10f);

        row.add(new Label(lobby.info.lobbyName, bodyStyle)).left().width(200f);
        row.add(new Label(lobby.info.hostNick, hintStyle)).left().width(140f);
        row.add(new Label(lobby.info.playerCount + "/" + lobby.info.maxPlayers, bodyStyle))
           .center().width(60f);
        row.add(new Label(joinable ? lobby.address : reason, joinable ? hintStyle : errorStyle))
           .left().expandX();

        final PlateButton join = PlateButton.action(actionStyle, "ПРИЄДНАТИСЬ");
        join.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (join.isDisabled()) return;
                openLobby(MultiplayerServices.join(lobby, trimmedNick()));
            }
        });
        setDisabled(join, !joinable || !hasValidNick());
        row.add(join).width(150f).height(34f).right();

        return row;
    }

    // ── Допоміжне ─────────────────────────────────────────────────────────

    private void openLobby(LobbySession session) {
        game.setScreen(new LobbyScreen(game, session));
    }

    private String  trimmedNick()   { return nick == null ? "" : nick.trim(); }
    private boolean hasValidNick()  { return !trimmedNick().isEmpty(); }

    private void refreshEnabledState() {
        boolean nickOk = hasValidNick();
        setDisabled(createBtn, !nickOk);
        setDisabled(directJoinBtn, !nickOk || directAddress.trim().isEmpty());

        if (!nickOk) {
            setMessage("Введи нік, щоб створити лоббі або приєднатись.", true);
        } else if (message.startsWith("Введи нік")) {
            setMessage("", false);
        }
        // Кнопки «Приєднатись» у рядках залежать від того ж ніка.
        renderedListSignature = "";
    }

    private void setMessage(String text, boolean error) {
        message = text;
        messageIsError = error;
        if (messageLabel != null) {
            messageLabel.setText(text);
            messageLabel.setStyle(error ? errorStyle : hintStyle);
        }
    }

    /** Вимкнена кнопка ще й блідне — сам по собі setDisabled нічого не малює інакше. */
    private static void setDisabled(Button button, boolean disabled) {
        if (button == null) return;
        button.setDisabled(disabled);
        button.getColor().a = disabled ? 0.45f : 1f;
    }
}
