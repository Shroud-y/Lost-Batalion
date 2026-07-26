package io.jababa.lost_batalion.screens.multiplayer;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
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

    private static final float FIELD_WIDTH  = 320f;
    private static final float BUTTON_W     = 220f;
    private static final float BUTTON_H     = 44f;
    private static final float ROW_H        = 46f;

    private final LobbyDirectory directory = MultiplayerServices.createDirectory();

    // Стан, що переживає перебудову сцени
    private String nick;
    private String directAddress = "";
    private String message       = "";
    private boolean messageIsError;

    // Віджети поточної сцени
    private Table      lobbyListTable;
    private TextButton createBtn;
    private TextButton directJoinBtn;
    private Label      messageLabel;
    private Label      scanLabel;

    /**
     * Стилі створюються раз на побудову сцени і перевикористовуються.
     * UIFactory на кожен виклик генерує новий FreeType-шрифт із текстурою —
     * робити це на кожне натискання клавіші в полі ніка не можна.
     */
    private Label.LabelStyle hintStyle;
    private Label.LabelStyle errorStyle;

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
        hintStyle  = UIFactory.createHintStyle();
        errorStyle = UIFactory.createErrorStyle();

        Table root = new Table();
        root.setFillParent(true);
        root.top().pad(12f);

        root.add(buildTopBar()).expandX().fillX().padBottom(6f).row();
        root.add(buildNickRow()).expandX().fillX().padBottom(4f).row();

        messageLabel = new Label(message, messageIsError ? errorStyle : hintStyle);
        root.add(messageLabel).expandX().left().padLeft(6f).padBottom(8f).row();

        root.add(buildLobbyListHeader()).expandX().fillX().padBottom(4f).row();

        lobbyListTable = new Table();
        lobbyListTable.top();
        ScrollPane scroll = new ScrollPane(lobbyListTable);
        scroll.setScrollingDisabled(true, false);
        scroll.setFadeScrollBars(false);
        scroll.setOverscroll(false, false);
        root.add(scroll).expand().fill().padBottom(8f).row();

        root.add(buildBottomBar()).expandX().fillX();

        stage.addActor(root);

        rebuildLobbyRows();
        refreshEnabledState();
    }

    // ── Секції ────────────────────────────────────────────────────────────

    private Table buildTopBar() {
        TextButton back = new TextButton("< Назад", UIFactory.createSmallButtonStyle());
        back.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                game.setScreen(new MainMenuScreen(game));
            }
        });

        Label title = new Label("МУЛЬТИПЛЕЄР", UIFactory.createScreenTitleStyle());

        Table bar = new Table();
        bar.add(back).width(110f).height(38f).left();
        bar.add(title).expandX().center();
        bar.add().width(110f);
        return bar;
    }

    private Table buildNickRow() {
        TextField nickField = new TextField(nick, UIFactory.createTextFieldStyle());
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
        row.add(new Label("Нік:", UIFactory.createBodyStyle())).padRight(10f);
        row.add(nickField).width(FIELD_WIDTH).height(BUTTON_H).left();
        row.add().expandX();
        return row;
    }

    private Table buildLobbyListHeader() {
        TextButton refresh = new TextButton("Оновити", UIFactory.createSmallButtonStyle());
        refresh.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                directory.refresh();
                setMessage("Сканую локальну мережу…", false);
            }
        });

        scanLabel = new Label("", UIFactory.createHintStyle());

        Table header = new Table();
        header.add(new Label("Доступні лоббі", UIFactory.createBodyStyle())).left().padLeft(6f);
        header.add(scanLabel).left().padLeft(12f);
        header.add().expandX();
        header.add(refresh).width(130f).height(36f).right();
        return header;
    }

    private Table buildBottomBar() {
        TextField addressField = new TextField(directAddress, UIFactory.createTextFieldStyle());
        addressField.setMessageText("192.168.0.5");
        addressField.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                directAddress = addressField.getText();
                refreshEnabledState();
            }
        });

        directJoinBtn = new TextButton("Підключитись", UIFactory.createSmallButtonStyle());
        directJoinBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (directJoinBtn.isDisabled()) return;
                openLobby(MultiplayerServices.joinByAddress(directAddress.trim(), trimmedNick()));
            }
        });

        createBtn = new TextButton("Створити лоббі", UIFactory.createMenuButtonStyle());
        createBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (createBtn.isDisabled()) return;
                game.setScreen(new CreateLobbyScreen(game, trimmedNick()));
            }
        });

        Table direct = new Table();
        direct.add(new Label("Пряме підключення:", UIFactory.createHintStyle())).padRight(8f);
        direct.add(addressField).width(200f).height(38f);
        direct.add(directJoinBtn).width(150f).height(38f).padLeft(8f);

        Table bar = new Table();
        bar.add(direct).left();
        bar.add().expandX();
        bar.add(createBtn).size(BUTTON_W, BUTTON_H).right();
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
            Label empty = new Label(hint, UIFactory.createHintStyle());
            empty.setAlignment(com.badlogic.gdx.utils.Align.center);
            lobbyListTable.add(empty).expandX().center().padTop(24f).row();
            return;
        }

        for (int i = 0; i < lobbies.size(); i++) {
            lobbyListTable.add(buildLobbyRow(lobbies.get(i)))
                .expandX().fillX().height(ROW_H).padBottom(6f).row();
        }
    }

    private Table buildLobbyRow(final DiscoveredLobby lobby) {
        String reason = lobby.unjoinableReason(NetConfig.PROTOCOL_VERSION);
        boolean joinable = reason == null;

        Table row = new Table();
        row.setBackground(UIFactory.createRowBackground(false));
        row.pad(4f, 10f, 4f, 10f);

        row.add(new Label(lobby.info.lobbyName, UIFactory.createBodyStyle())).left().width(200f);
        row.add(new Label(lobby.info.hostNick, UIFactory.createHintStyle())).left().width(140f);
        row.add(new Label(lobby.info.playerCount + "/" + lobby.info.maxPlayers,
                          UIFactory.createBodyStyle())).center().width(60f);
        row.add(new Label(joinable ? lobby.address : reason,
                          joinable ? UIFactory.createHintStyle() : UIFactory.createErrorStyle()))
           .left().expandX();

        TextButton join = new TextButton("Приєднатись", UIFactory.createSmallButtonStyle());
        join.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (join.isDisabled()) return;
                openLobby(MultiplayerServices.join(lobby, trimmedNick()));
            }
        });
        setDisabled(join, !joinable || !hasValidNick());
        row.add(join).width(140f).height(34f).right();

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
    private static void setDisabled(TextButton button, boolean disabled) {
        if (button == null) return;
        button.setDisabled(disabled);
        button.getColor().a = disabled ? 0.45f : 1f;
    }
}
