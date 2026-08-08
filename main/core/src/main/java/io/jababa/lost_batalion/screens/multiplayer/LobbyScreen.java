package io.jababa.lost_batalion.screens.multiplayer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Button;
import com.badlogic.gdx.scenes.scene2d.ui.Container;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Scaling;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ai.Difficulty;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.api.AvatarSource;
import io.jababa.lost_batalion.net.api.LobbySession;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.api.MultiplayerServices;
import io.jababa.lost_batalion.net.messages.ChatMessage;
import io.jababa.lost_batalion.net.messages.LobbyState;
import io.jababa.lost_batalion.net.messages.PlayerSlot;
import io.jababa.lost_batalion.net.messages.StartMatch;
import io.jababa.lost_batalion.screens.BaseScreen;
import io.jababa.lost_batalion.screens.game.GameScreen;
import io.jababa.lost_batalion.screens.scenario.ScenarioCard;
import io.jababa.lost_batalion.screens.scenario.ScenarioCatalog;
import io.jababa.lost_batalion.sim.PlayerRoster;
import io.jababa.lost_batalion.ui.AvatarBox;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.ScreenHeader;
import io.jababa.lost_batalion.ui.UIFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Кімната очікування: дві команди, список очікування, чат, старт.
 *
 * <p>Екран нічого не вирішує сам — він показує {@link LobbyState}, який
 * прислав хост, і надсилає ПРОХАННЯ. Навіть у хоста власний список береться
 * звідти ж, а не з локальних змінних: інакше хост і гість малювали б лоббі за
 * різними даними, і розбіжність вилізла б рівно тоді, коли її найважче ловити.
 *
 * <h3>Розкладка (DESIGN §1, §4)</h3>
 * Три колонки: карта ліворуч, склад по центру, чат і список очікування праворуч.
 * Бічні колонки мають СТАЛУ ширину, а центральна росте — {@code ExtendViewport}
 * при широкому вікні додає ширину світу, і зайве місце мусить діставатись саме
 * складу, бо це головне, на що тут дивляться.
 *
 * <p>Склад стоїть по центру, а не ліворуч, попри правило «колонка ліворуч»: це
 * правило про меню-стовпчик над картою, а тут карта — вміст екрана, і ліве поле
 * зайняте нею. Порядок читання лишається той самий: спершу де граємо, потім хто
 * з ким, потім розмова.
 *
 * <h3>Чому рядок місця — це рядок, а не плитка</h3>
 * Плитка ({@code PlateButton.plate}) — пункт, який ВИБИРАЮТЬ; у місця ж дій
 * кілька і вони різні для хоста й гостя. Тому місце — рядок зі своїм тлом
 * ({@code createRowBackground}), а дії на ньому — компактні кнопки.
 *
 * <h3>Стилі створюються ОДИН раз</h3>
 * Рядки перебудовуються на кожне мережеве повідомлення, а кожна фабрика
 * {@code UIFactory} пече новий атлас FreeType і нові текстури (DESIGN §6). До
 * цієї переробки перебудова двох десятків рядків народжувала стільки ж атласів,
 * і жили вони до наступної зміни розміру вікна. Тепер усі спільні стилі —
 * поля екрана, створені в {@link #buildUI()}.
 */
public class LobbyScreen extends BaseScreen {

    // ── Розкладка (одиниці світу сцени, 900×580) ──────────────────────────

    /**
     * Ліва колонка: картка карти. Вужча за праву — там лише прев'ю й назва.
     *
     * <p>Числа бічних колонок і стиснені кнопки в рядках місць — це один
     * розрахунок, а не окремі смаки. Світ сцени при 16:9 має ширину близько
     * 1030 одиниць, з них 144 йде на поля; усе інше мусить вміститись у 886.
     * Table не вміє стиснути вміст нижче його мінімуму — він просто вилазить за
     * край екрана, і саме це сталось у першому підході (кнопки зі звичайними
     * полями давали мінімум близько 1140).
     */
    private static final float LEFT_W   = 156f;
    /** Права колонка: чат і список очікування. */
    private static final float RIGHT_W  = 204f;
    /** Проміжок між колонками. */
    private static final float COL_GAP  = 10f;

    private static final float SEAT_H     = 32f;
    private static final float SEAT_GAP   = 4f;
    /** Квадрат під аватарку. Поки порожній — аватарки це наступний етап. */
    private static final float AVATAR     = 22f;
    private static final float BTN_H      = 24f;
    /** Ширина добірника рівня. Мірялась по найдовшому: «ЗВИЧАЙНИЙ». */
    private static final float LEVEL_W    = 104f;
    private static final float PREVIEW_H  = 84f;
    /** Висота вікна чату. Решту місця в колонці забирає список очікування. */
    private static final float CHAT_H     = 168f;

    private static final int CHAT_KEEP = 40;

    /** Прев'ю карти притемнене: воно тло під назвою, а не ілюстрація. */
    private static final Color PREVIEW_TINT = new Color(0.62f, 0.62f, 0.62f, 1f);

    private final LobbySession session;
    private final List<String> chatLines = new ArrayList<>();

    /**
     * Аватарки. Береться РАЗ і на весь час екрана: джерело живе разом із
     * бекендом і переживає і цю кімнату, і матч.
     */
    private final AvatarSource avatars = MultiplayerServices.current().avatars();

    /**
     * Яку версію набору аватарок ми вже намалювали.
     *
     * <p>Аватарка приїжджає зі Steam за кілька кадрів ПІСЛЯ того, як гравець
     * з'явився в кімнаті, і власного приводу перемалювати рядки в екрана немає:
     * стан лоббі при цьому не міняється. Без цієї звірки картинка не з'явилась
     * би, доки хтось не зробить у кімнаті ще щось.
     */
    private int avatarRevision = -1;

    private LobbyState state;
    private String  status = "";
    private boolean statusIsError;
    private boolean localReady;
    /** Після розриву кнопки не мають сенсу — лишається тільки вихід. */
    private boolean sessionDead;
    /** Знімальний автостарт спрацьовує рівно раз — див. {@code lb.autoStart}. */
    private boolean autoStarted;
    /** Так само раз — див. {@code lb.autoSeat}. */
    private boolean autoSeated;

    private Table teamsTable;
    private Table waitTable;
    private Table chatTable;
    private TextField chatField;
    private Label statusLabel;
    private Label mapLabel;
    private Label readyLabel;
    private PlateButton actionBtn;
    private PlateButton mapBtn;
    private ScreenHeader header;

    /** Прев'ю карти живе стільки ж, скільки сцена, і звільняється разом із нею. */
    private final Array<Texture> ownedTextures = new Array<>();

    // ── Спільні стилі (див. javadoc класу) ────────────────────────────────

    private Label.LabelStyle hintStyle;
    private Label.LabelStyle errorStyle;
    private Label.LabelStyle bodyStyle;
    private Label.LabelStyle accentStyle;
    private Label.LabelStyle titleStyle;
    private Label.LabelStyle[] teamStyles;
    private Button.ButtonStyle    actionStyle;
    private Label.LabelStyle      actionLabelStyle;
    private SelectBox.SelectBoxStyle levelStyle;

    private final LobbySession.Listener listener = new LobbySession.Adapter() {
        @Override public void onLobbyState(LobbyState newState) {
            state = newState;
            PlayerSlot own = newState == null ? null : newState.findSlot(session.getLocalPlayerId());
            if (own != null) localReady = own.ready;
            rebuildRows();
            refreshControls();
        }

        @Override public void onChat(ChatMessage message) {
            if (message == null || message.text == null) return;
            chatLines.add((message.nick == null ? "?" : message.nick) + ": " + message.text);
            while (chatLines.size() > CHAT_KEEP) chatLines.remove(0);
            rebuildChat();
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
        // Сцена перебудовується на кожну зміну розміру вікна, і разом із нею
        // перестворюється все, що робить фабрика. Прев'ю карти — наше власне,
        // фабрика про нього не знає, тож звільняємо самі.
        disposeOwnedTextures();

        hintStyle   = UIFactory.createHintStyle();
        errorStyle  = UIFactory.createErrorStyle();
        bodyStyle   = UIFactory.createBodyStyle();
        accentStyle = UIFactory.createAccentStyle();
        titleStyle  = UIFactory.createAccentStyle();
        teamStyles  = new Label.LabelStyle[] {
            UIFactory.createScoreStyle(UIFactory.COLOR_TEAM_SELF),
            UIFactory.createScoreStyle(UIFactory.COLOR_TEAM_FOE)
        };
        actionStyle      = UIFactory.createActionStyle();
        actionLabelStyle = UIFactory.createActionLabelStyle();
        levelStyle       = UIFactory.createSelectBoxStyle();

        teamsTable = new Table(); teamsTable.top();
        waitTable  = new Table(); waitTable.top();
        chatTable  = new Table(); chatTable.bottom().left();

        statusLabel = new Label(status, statusIsError ? errorStyle : hintStyle);

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        root.pad(UIFactory.HEADER_TOP, UIFactory.MARGIN,
                 UIFactory.FOOTER_PAD, UIFactory.MARGIN);

        header = new ScreenHeader(lobbyTitle(), this::leave);
        root.add(header).growX().row();

        // Бічні колонки — стала ширина, центральна росте: зайву ширину світу
        // має діставати склад, а не картка карти чи чат.
        Table columns = new Table();
        columns.add(buildLeftColumn()).width(LEFT_W).top();
        columns.add(buildTeams()).growX().padLeft(COL_GAP).padRight(COL_GAP).top();
        columns.add(buildRightColumn()).width(RIGHT_W).top();
        root.add(columns).grow().padTop(16f).row();

        root.add(buildFooter()).growX().padTop(12f).row();

        stage.addActor(root);

        rebuildRows();
        rebuildChat();
        refreshControls();
    }

    /** Панель із заголовком і лінійкою — та сама форма, що в решті екранів. */
    private Table panel(String title, Actor content) {
        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(10f, 12f, 12f, 12f);
        panel.top();
        panel.add(new Label(title, titleStyle)).left().row();
        panel.add(new Image(UIFactory.createRuleDrawable()))
             .height(1f).growX().padTop(7f).padBottom(8f).row();
        panel.add(content).grow().top().row();
        return panel;
    }

    // ── Ліва колонка: карта й довідка про кімнату ─────────────────────────

    private Table buildLeftColumn() {
        ScenarioCard card = ScenarioCatalog.byId(state != null ? state.scenarioId : null);

        Image preview = new Image(new TextureRegionDrawable(loadPreview(card)));
        preview.setScaling(Scaling.fill);
        preview.setColor(PREVIEW_TINT);

        // Контейнер обрізає прев'ю по своїй рамці: карта квадратна, а місце під
        // неї — смуга, і без обрізання вона вилізла б за панель.
        Container<Image> frame = new Container<>(preview);
        frame.fill();
        frame.clip();

        mapLabel = new Label(card.title.toUpperCase(), accentStyle);

        Table body = new Table();
        body.add(frame).growX().height(PREVIEW_H).row();
        body.add(mapLabel).left().padTop(8f).row();

        // Карта одна — міняти нічого. Кнопка не зникає, а гасне (DESIGN §7):
        // зниклий рядок читається як помилка інтерфейсу.
        if (session.isHost()) {
            mapBtn = smallButton("ЗМІНИТИ", () -> {
                if (state == null) return;
                session.setScenario(ScenarioCatalog.next(state.scenarioId).id);
            });
            boolean single = ScenarioCatalog.all().size <= 1;
            mapBtn.setDisabled(single);
            mapBtn.setMuted(single);
            body.add(mapBtn).left().height(BTN_H).padTop(10f).row();
        }

        body.add(buildRoomInfo()).left().growX().padTop(14f).row();
        body.add().expandY().row();

        return panel("КАРТА", body);
    }

    /**
     * Роль і ключ кімнати. Ключ — тільки хосту й тільки там, де він є: гість
     * його ВВОДИТЬ на екрані пошуку, а тут кімнату вже знайшов.
     */
    private Table buildRoomInfo() {
        Table info = new Table();
        info.add(new Label(session.isHost() ? "ТИ ХОСТ" : "ТИ ГІСТЬ", hintStyle)).left().row();

        String key = MultiplayerServices.current().hostedRoomKey();
        if (session.isHost() && key != null && !key.isEmpty()) {
            info.add(new Label("КЛЮЧ КІМНАТИ", hintStyle)).left().padTop(10f).row();
            // Акцентом, бо це єдине на екрані, що доводиться диктувати вголос.
            info.add(new Label(key, accentStyle)).left().padTop(2f).row();
        }
        return info;
    }

    private Texture loadPreview(ScenarioCard card) {
        if (card.texturePath != null && Gdx.files.internal(card.texturePath).exists()) {
            Texture tex = new Texture(Gdx.files.internal(card.texturePath));
            // Зменшення — Linear: Nearest викидає рядки, і лісосмуги на прев'ю
            // розсипаються в шум (DESIGN §6).
            tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            ownedTextures.add(tex);
            return tex;
        }
        Pixmap pm = new Pixmap(4, 4, Pixmap.Format.RGBA8888);
        pm.setColor(0.10f, 0.12f, 0.14f, 1f);
        pm.fill();
        Texture tex = new Texture(pm);
        pm.dispose();
        ownedTextures.add(tex);
        return tex;
    }

    // ── Центр: склад ──────────────────────────────────────────────────────

    private Table buildTeams() {
        return panel("СКЛАД", teamsTable);
    }

    private void rebuildRows() {
        if (teamsTable == null) return;
        teamsTable.clear();
        waitTable.clear();

        if (state == null) {
            teamsTable.add(new Label("Підключення…", hintStyle)).padTop(16f).row();
            return;
        }

        for (int team = 0; team < NetConfig.TEAM_COUNT; team++) {
            teamsTable.add(teamHeader(team)).growX()
                      .padLeft(team == 0 ? 0f : COL_GAP);
        }
        teamsTable.row();

        for (int seat = 0; seat < NetConfig.TEAM_SIZE; seat++) {
            for (int team = 0; team < NetConfig.TEAM_COUNT; team++) {
                teamsTable.add(buildSeatRow(team, seat)).growX().uniformX()
                          .height(SEAT_H).padTop(SEAT_GAP)
                          .padLeft(team == 0 ? 0f : COL_GAP);
            }
            teamsTable.row();
        }
        teamsTable.add().colspan(NetConfig.TEAM_COUNT).expandY().row();

        rebuildWaitlist();
    }

    /**
     * Шапка колонки команди: кольорова мітка й назва.
     *
     * <p>Колір сторони тут — ДАНІ гри, а не тема (DESIGN §8), тому золоте
     * правило акценту на нього не поширюється. Але й заливкою він не стає:
     * колір несе мітка в кілька пікселів, а назва лише повторює його тоном.
     */
    private Table teamHeader(int team) {
        Table header = new Table();
        Image chip = new Image(UIFactory.createColorDrawable(
            team == 0 ? UIFactory.COLOR_TEAM_SELF : UIFactory.COLOR_TEAM_FOE));
        header.add(chip).size(4f, 14f).left().padRight(8f);
        header.add(new Label(team == 0 ? "СИНІ" : "ЧЕРВОНІ", teamStyles[team])).left();
        header.add(new Label(state.seatedCount(team) + "/" + NetConfig.TEAM_SIZE, hintStyle))
              .right().expandX();
        return header;
    }

    /**
     * Один рядок місця. Три стани — зайняте, закрите, вільне — і в кожному свій
     * набір дій; хостові дії малюються тільки хосту, бо гість однаково отримає
     * від хоста мовчазну відмову.
     */
    private Table buildSeatRow(int team, int seat) {
        PlayerSlot occupant = state.occupant(team, seat);
        boolean    closed   = state.isClosed(team, seat);
        boolean    mine     = occupant != null && occupant.playerId == session.getLocalPlayerId();

        Table row = new Table();

        if (occupant != null) {
            row.setBackground(UIFactory.createRowBackground(mine));
            row.pad(2f, 6f, 2f, 6f);
            fillOccupied(row, occupant, mine);

        } else if (closed) {
            row.setBackground(UIFactory.createRowBackground(false));
            row.pad(2f, 6f, 2f, 6f);
            Label label = new Label("ЗАКРИТО", hintStyle);
            label.setColor(UIFactory.itemMutedColor());
            row.add(label).left().padLeft(4f).expandX();
            if (session.isHost()) {
                row.add(compactButton("ВІДКР.", () -> session.setSlotClosed(team, seat, false)))
                   .height(BTN_H);
            }

        } else {
            // Вільне місце — САМО кнопка, на всю ширину рядка. Окрема кнопка
            // «СІСТИ» поруч із написом «вільно» була третьою в ряду й давала
            // рівно ту зайву ширину, через яку розкладка не вміщалась; до того ж
            // ціль розміром із рядок природніша за ціль розміром зі слово.
            PlateButton take = PlateButton.compact(actionStyle, actionLabelStyle, "ВІЛЬНО");
            take.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent e, Actor a) {
                    if (!sessionDead) session.setTeam(team, seat);
                }
            });
            row.add(take).grow();
            if (session.isHost()) {
                row.add(compactButton("БОТ",
                        () -> session.addBot(team, seat, Difficulty.NORMAL.name())))
                   .height(SEAT_H).padLeft(4f);
                row.add(compactButton("ЗАКР.", () -> session.setSlotClosed(team, seat, true)))
                   .height(SEAT_H).padLeft(4f);
            }
        }
        return row;
    }

    /**
     * Картка учасника: аватарка, нік і те, що про нього треба знати.
     *
     * <p>Більше нічого — так вирішено при постановці задачі. Рівні, звання й
     * значки з прикладу тут нема чим наповнити, а порожні поля виглядають
     * гірше за їх відсутність.
     */
    private void fillOccupied(Table row, PlayerSlot slot, boolean mine) {
        row.add(avatarBox(slot)).size(AVATAR).left().padRight(7f);

        // Нік бота хост складає як «БОТ · РІВЕНЬ», але рівень тут показує вже
        // сам добірник — разом виходило «БОТ · ВАЖКИЙ [ВАЖКИЙ]». Рівень
        // лишається в одному місці, і це те, яким його МІНЯЮТЬ.
        String nick = slot.bot ? "БОТ" : slot.nick;
        row.add(new Label(nick, mine ? accentStyle : bodyStyle)).left().expandX().padRight(8f);

        if (slot.bot) {
            if (session.isHost()) {
                row.add(levelBox(slot)).width(LEVEL_W).height(BTN_H).right();
                row.add(compactButton("ЗНЯТИ", () -> session.removeBot(slot.playerId)))
                   .height(BTN_H).padLeft(4f);
            } else {
                row.add(new Label(shortLevel(slot.botDifficulty), hintStyle)).right().padRight(4f);
            }
            return;
        }

        // Хост позначений СЛОВОМ на місці готовності, а не зіркою біляніка:
        // ★ (U+2605) у main.ttf немає — на екрані з неї виходить порожнеча
        // (DESIGN §3). Та й «готовий» у хоста нічого не повідомляє: він готовий
        // за побудовою, готовність для нього — це натиснути «Старт».
        row.add(new Label(slot.host ? "хост" : (slot.ready ? "готовий" : "чекає"), hintStyle))
           .right().padRight(6f);

        if (mine) {
            row.add(compactButton("ВСТАТИ", () -> session.setTeam(PlayerSlot.TEAM_NONE, -1)))
               .height(BTN_H);
        } else if (session.isHost() && !slot.host) {
            row.add(compactButton("ВИГНАТИ", () -> session.kick(slot.playerId))).height(BTN_H);
        }
    }

    /**
     * Квадратик учасника: аватарка зі Steam, а поки її немає — літера ніка.
     *
     * <p>Джерело питається в МЕРЕЖІ, а не вибирається за назвою бекенда: у
     * локальній мережі аватарок немає й узятись їм нізвідки, і саме тому
     * {@link io.jababa.lost_batalion.net.api.AvatarSource#NONE} — повноцінне
     * джерело, а не {@code null}.
     */
    private Actor avatarBox(PlayerSlot slot) {
        return new AvatarBox(avatars.avatarFor(slot.steamId), slot.nick, bodyStyle, slot.bot);
    }

    /**
     * Випадайка рівня бота.
     *
     * <p>Вибір ставиться ДО того, як вішається слухач: інакше кожна перебудова
     * рядків (а вона трапляється на кожне повідомлення від хоста) сама б
     * «обирала» рівень і слала його в мережу.
     */
    private SelectBox<String> levelBox(final PlayerSlot slot) {
        final Difficulty[] all = Difficulty.values();
        Array<String> titles = new Array<>();
        for (Difficulty d : all) titles.add(d.title);

        SelectBox<String> box = new SelectBox<>(levelStyle);
        box.setItems(titles);

        Difficulty current = Difficulty.byName(slot.botDifficulty);
        box.setSelectedIndex(current == null ? 0 : current.ordinal());

        box.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (sessionDead) return;
                int index = box.getSelectedIndex();
                if (index < 0 || index >= all.length) return;
                if (all[index].name().equals(slot.botDifficulty)) return;
                session.setBotDifficulty(slot.playerId, all[index].name());
            }
        });
        return box;
    }

    // ── Права колонка: чат і список очікування ────────────────────────────

    private Table buildRightColumn() {
        Table column = new Table();
        column.add(panel("ЧАТ", buildChatBlock())).growX().height(CHAT_H).top().row();
        column.add(panel("ОЧІКУЮТЬ", waitTable)).grow().padTop(COL_GAP).top().row();
        return column;
    }

    private Table buildChatBlock() {
        chatField = new TextField("", UIFactory.createTextFieldStyle());
        chatField.setMessageText("написати всім…");
        chatField.setMaxLength(NetConfig.MAX_CHAT_LENGTH);

        // Enter надсилає: чат, у якому доводиться цілитись мишею в кнопку,
        // під час складання команд не використовують узагалі.
        chatField.setTextFieldListener((field, c) -> {
            if (c == '\n' || c == '\r') sendChat();
        });

        Table block = new Table();
        block.add(chatTable).grow().top().row();
        block.add(chatField).growX().height(26f).padTop(8f).row();
        return block;
    }

    private void sendChat() {
        if (chatField == null || sessionDead) return;
        String text = chatField.getText();
        if (text == null || text.trim().isEmpty()) return;
        session.sendChat(text);
        chatField.setText("");
        stage.setKeyboardFocus(chatField);
    }

    private void rebuildWaitlist() {
        if (waitTable == null || state == null) return;
        waitTable.clear();

        List<PlayerSlot> waiting = state.waitlist();
        if (waiting.isEmpty()) {
            waitTable.add(new Label("нікого", hintStyle)).left().row();
        }
        for (int i = 0; i < waiting.size(); i++) {
            final PlayerSlot slot = waiting.get(i);
            boolean mine = slot.playerId == session.getLocalPlayerId();

            Table row = new Table();
            row.setBackground(UIFactory.createRowBackground(mine));
            row.pad(2f, 6f, 2f, 6f);
            row.add(avatarBox(slot)).size(AVATAR).left().padRight(7f);
            row.add(new Label(slot.nick, mine ? accentStyle : bodyStyle)).left().expandX();
            if (session.isHost() && !slot.host) {
                row.add(compactButton("ВИГНАТИ", () -> session.kick(slot.playerId))).height(BTN_H);
            }
            waitTable.add(row).growX().height(SEAT_H).padBottom(SEAT_GAP).row();
        }
        waitTable.add().expandY().row();
    }

    private void rebuildChat() {
        if (chatTable == null) return;
        chatTable.clear();
        // Скільки рядків влізе — рахує сама таблиця: показуємо весь запас, а
        // зайве обрізає панель. Фіксоване число рядків розійшлось би з висотою
        // панелі на будь-якому іншому вікні.
        int from = Math.max(0, chatLines.size() - CHAT_KEEP);
        for (int i = from; i < chatLines.size(); i++) {
            Label line = new Label(chatLines.get(i), hintStyle);
            line.setWrap(true);
            chatTable.add(line).left().growX().row();
        }
    }

    // ── Підвал: стан і головна дія ────────────────────────────────────────

    private Table buildFooter() {
        actionBtn = PlateButton.action(actionStyle, actionLabelStyle,
                                       session.isHost() ? "СТАРТ" : "ГОТОВИЙ");
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

        readyLabel = new Label("", hintStyle);

        Table footer = new Table();
        footer.add(statusLabel).left().expandX();
        footer.add(readyLabel).right().padRight(14f);
        footer.add(actionBtn).size(200f, 40f).right();
        return footer;
    }

    // ── Стан кнопок ───────────────────────────────────────────────────────

    /** Назва кімнати; поки стан не приїхав — заглушка. */
    private String lobbyTitle() {
        return state != null && state.lobbyName != null && !state.lobbyName.isEmpty()
             ? state.lobbyName.toUpperCase() : "ЛОББІ";
    }

    /**
     * Гість сідає на задане місце й підтверджує готовність — {@code lb.autoSeat}
     * у форматі {@code "команда:місце"}.
     *
     * <p>Ті самі два публічні виклики, які робить людина: клік по вільному рядку
     * і кнопка «Готовий». Без цього наскрізну перевірку на двох ЖИВИХ клієнтах
     * не поставити взагалі — гість приходить у список очікування, і сісти за
     * нього нікому, а хост без заселених обох сторін не стартує.
     */
    private void tryAutoSeat() {
        if (autoSeated || sessionDead || state == null || session.isHost()) return;

        String want = System.getProperty("lb.autoSeat");
        if (want == null) return;

        String[] parts = want.split(":");
        if (parts.length < 2) return;
        try {
            autoSeated = true;
            session.setTeam(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            session.setReady(true);
            localReady = true;
        } catch (NumberFormatException e) {
            setStatus("lb.autoSeat: очікується «команда:місце»", true);
        }
    }

    /**
     * Чи час тиснути «Старт» самому — {@code lb.autoStart}.
     *
     * <p>Те саме натискання, що робить людина, і рівно тоді ж, коли кнопка стає
     * доступною. Значення {@code true} означає «щойно можна»; ЧИСЛО означає
     * «щойно можна І в складі стільки-то учасників».
     *
     * <p>Число тут не примха: варто посадити в лоббі бота-суперника, як умови
     * старту виконуються НЕГАЙНО, і хост зривається в матч раніше, ніж живий
     * гість устигне під'єднатись. Так знімальний прогін на трьох тихо
     * перетворювався на матч на двох.
     */
    private boolean autoStartWanted() {
        String want = System.getProperty("lb.autoStart");
        if (want == null || want.isEmpty()) return false;
        if (Boolean.parseBoolean(want)) return true;
        try {
            return state != null && state.seatedCount() >= Integer.parseInt(want.trim());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void refreshControls() {
        tryAutoSeat();

        // Назва кімнати доходить до гостя ПІЗНІШЕ, ніж будується екран: перший
        // кадр він малює ще без стану. Без оновлення заголовка гість так і
        // сидів би в кімнаті «ЛОББІ», хоч би як хост її назвав.
        if (header != null) header.setTitle(lobbyTitle());
        if (mapLabel != null && state != null) {
            mapLabel.setText(ScenarioCatalog.byId(state.scenarioId).title.toUpperCase());
        }
        if (readyLabel != null) {
            readyLabel.setText(state == null ? "" : "ГОТОВІ " + readyCount() + "/" + state.seatedCount());
        }
        if (actionBtn == null) return;

        boolean enabled;
        if (sessionDead) {
            enabled = false;
        } else if (session.isHost()) {
            enabled = state != null && state.allReady();
        } else {
            enabled = session.isConnected();
            actionBtn.setText(localReady ? "НЕ ГОТОВИЙ" : "ГОТОВИЙ");
        }

        actionBtn.setDisabled(!enabled);
        actionBtn.setMuted(!enabled);

        if (enabled && session.isHost() && !autoStarted && autoStartWanted()) {
            autoStarted = true;
            session.startMatch();
        }

        // Підказка хоста мусить не лише з'являтись, а й ЗНИКАТИ. Поки її тільки
        // ставили, вона лишалась на екрані й тоді, коли всі вже готові: гравець
        // бачив увімкнений «Старт» і напис «чекаємо, поки в обох командах буде
        // хоч по одному», тобто інтерфейс суперечив сам собі. Повідомлення про
        // помилку при цьому не чіпаємо — воно важливіше за підказку.
        if (!sessionDead && session.isHost() && state != null && !statusIsError) {
            if (state.allReady()) {
                setStatus("Усі готові — можна починати.", false);
            } else {
                boolean bothSidesManned = state.seatedCount(0) > 0 && state.seatedCount(1) > 0;
                setStatus(bothSidesManned
                    ? "Чекаємо, поки всі підтвердять готовність."
                    : "Чекаємо, поки в обох командах буде хоч по одному учаснику.", false);
            }
        }
    }

    /** Скільки з тих, хто СИДИТЬ, підтвердили готовність. Боти — завжди. */
    private int readyCount() {
        if (state == null) return 0;
        int n = 0;
        for (int i = 0; i < state.slots.size(); i++) {
            PlayerSlot s = state.slots.get(i);
            if (s.seated() && s.ready) n++;
        }
        return n;
    }

    private void setStatus(String text, boolean error) {
        status = text;
        statusIsError = error;
        if (statusLabel != null) {
            statusLabel.setText(text);
            statusLabel.setStyle(error ? errorStyle : hintStyle);
        }
    }

    /** Кнопка звичайного розміру — поза рядками місць, де є місце. */
    private PlateButton smallButton(String text, final Runnable action) {
        return listen(PlateButton.action(actionStyle, actionLabelStyle, text), action);
    }

    /** Кнопка для рядка місця: та сама, але з мінімальними полями. */
    private PlateButton compactButton(String text, final Runnable action) {
        return listen(PlateButton.compact(actionStyle, actionLabelStyle, text), action);
    }

    private PlateButton listen(PlateButton button, final Runnable action) {
        button.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (!sessionDead) action.run();
            }
        });
        return button;
    }

    private static String shortLevel(String name) {
        Difficulty level = Difficulty.byName(name);
        return level == null ? "?" : level.title;
    }

    // ── Життєвий цикл ─────────────────────────────────────────────────────

    private void leave() {
        session.leave();
        game.setScreen(new MultiplayerScreen(game));
    }

    @Override
    public void render(float delta) {
        // Мережеві події приходять у чужому потоці; тут вони перетворюються на
        // зміни віджетів — у потоці рендеру, єдиному, де це дозволено.
        session.pump(listener);

        // Серед цих подій є StartMatch: він перемикає екран, а перехід одразу
        // звільняє цей. Малювати вже нічого.
        if (stage == null) return;

        // Аватарки приходять асинхронно й без жодної події лоббі — див.
        // avatarRevision. Звірка щокадру дешева: це порівняння двох чисел.
        if (avatars.revision() != avatarRevision) {
            avatarRevision = avatars.revision();
            rebuildRows();
        }

        super.render(delta);
    }

    @Override
    public void dispose() {
        disposeOwnedTextures();
        super.dispose();
    }

    private void disposeOwnedTextures() {
        for (int i = 0; i < ownedTextures.size; i++) ownedTextures.get(i).dispose();
        ownedTextures.clear();
    }

    private void launchMatch(StartMatch start) {
        // Темп симуляції мусить збігатися побітово: різний tick rate означає,
        // що збірки різні, і матч розсинхронізується не одразу, а через
        // хвилину — коли причину вже не видно.
        if (start.tickRate != NetConfig.TICK_RATE) {
            setStatus("Несумісні параметри матчу — у хоста інша версія гри.", true);
            return;
        }
        // А от затримку вводу задає ХОСТ і саме її треба застосувати: вона
        // залежить від мережі (локальна проти Steam), а не від версії гри.
        NetConfig.setInputDelayTicks(start.inputDelayTicks);
        NetConfig.setChecksumIntervalTicks(start.checksumIntervalTicks);

        // Хто лишився в списку очікування, у матч не йде. Це не аварія: хост
        // цілком може стартувати, не дочекавшись, поки останній обере сторону.
        // Пускати такого в матч не можна — у lockstep він учасник без армії,
        // від якого решта чекатиме наказів.
        PlayerSlot own = state == null ? null : state.findSlot(session.getLocalPlayerId());
        if (own == null || !own.seated()) {
            setStatus("Матч почався без тебе — ти не обрав сторону.", true);
            return;
        }

        MatchTransport transport = session.openMatch(start);
        if (transport == null) {
            setStatus("Не вдалось відкрити канал матчу.", true);
            return;
        }

        // Склад матчу будується з тих самих слотів, що приїхали в StartMatch,
        // — і в хоста, і в гостя. Свого власного джерела складу тут ні в кого
        // немає навмисно: різні ростери означають різні армії при однаковому
        // хеші наказів.
        game.setScreen(new GameScreen(game, ScenarioCatalog.byId(start.scenarioId),
                                      start.rngSeed, transport,
                                      PlayerRoster.of(start.slots)));
    }
}
