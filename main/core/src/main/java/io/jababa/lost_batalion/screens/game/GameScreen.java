package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.commands.CurvedFormationCommand;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.mobile.GameInputHandler;
import io.jababa.lost_batalion.mobile.MobileTouchHandler;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.commands.AttackCommand;
import io.jababa.lost_batalion.net.commands.CurveFormationCommand;
import io.jababa.lost_batalion.net.commands.MoveCommand;
import io.jababa.lost_batalion.net.commands.MoveLineCommand;
import io.jababa.lost_batalion.net.commands.PathMoveCommand;
import io.jababa.lost_batalion.net.commands.StopCommand;
import io.jababa.lost_batalion.net.kryo.LocalMatchTransport;
import io.jababa.lost_batalion.screens.effects.BloomEffect;
import io.jababa.lost_batalion.screens.effects.MoveMarker;
import io.jababa.lost_batalion.screens.renderer.CapturePointRenderer;
import io.jababa.lost_batalion.screens.renderer.TerrainIndicatorRenderer;
import io.jababa.lost_batalion.screens.renderer.UnitRenderer;
import io.jababa.lost_batalion.screens.scenario.ScenarioCard;
import io.jababa.lost_batalion.screens.ui.Minimap;
import io.jababa.lost_batalion.screens.ui.SelectionPanel;
import io.jababa.lost_batalion.sim.GameSimulation;
import io.jababa.lost_batalion.sim.MatchRunner;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.units.*;
import io.jababa.lost_batalion.visibility.FogOfWarRenderer;
import io.jababa.lost_batalion.visibility.VisibilitySystem;

public class GameScreen implements Screen {

    private static final float CAM_SPEED = 400f;
    private static final float ZOOM_MIN  = 0.3f;
    private static final float ZOOM_MAX  = 2.0f;
    private static final float ZOOM_STEP = 0.1f;

    /**
     * Скільки кадрів очікування треба, щоб показати «чекаємо на гравця».
     * Коротка затримка в мережі — норма і трапляється щосекунди; підказка, що
     * блимає на кожен пакет, лише дратує.
     */
    private static final int WAIT_HINT_FRAMES = 12;

    /** Підписи кнопки строю. Раніше тут були значки, яких немає в шрифті. */
    private static final String FORMATION_OFF = "СТРІЙ";
    private static final String FORMATION_ON  = "СКАСУВ.";

    /**
     * Вікно подвійного кліку ПКМ, мілісекунди.
     *
     * <p>Це локальний стан інтерфейсу, а не симуляції, тож системний час тут
     * дозволений: на різних машинах він дасть різні результати, але й наслідок
     * буде різним лише в тому, ЯКУ команду відправив гравець — а команда далі
     * їде в мережу і виконується в усіх однаково.
     */
    private static final long DOUBLE_RMB_MILLIS = 350;
    /** Наскільки далеко може зсунутись курсор між кліками, у пікселях екрана. */
    private static final float DOUBLE_RMB_SLACK = 40f;

    private long  lastRmbTime;
    private float lastRmbX, lastRmbY;

    private boolean selecting;
    private float selStartX, selStartY, selCurX, selCurY;
    private boolean clickConsumedByUnit = false;

    private boolean paused = false;
    private PauseOverlay pauseOverlay;
    private Stage pauseStage;
    private Stage hudStage;
    private Label waitLabel;

    /** Модальні вікна матчу. Живуть на власній сцені поверх усього. */
    private Stage modalStage;
    private DesyncOverlay desyncOverlay;
    private MatchNoticeOverlay noticeOverlay;

    /**
     * Скільки часу тримати на екрані повідомлення про вибуття суперника.
     * Це не помилка й не тупик — матч триває, тож напис має згаснути сам.
     */
    private static final float DROP_NOTICE_SECONDS = 8f;
    private float dropNoticeTimer;
    private int   shownDropCount;

    private TerrainMaskManager terrainMask;
    private ForestTooltip forestTooltip;
    private TerrainMaskManager terrainCombatMask;
    /** Спільний доступ до обох масок — усі підсистеми ходять через нього. */
    private TerrainQuery terrain;
    private TerrainType currentTerrain = TerrainType.NONE;
    private int cursorScreenX, cursorScreenY;
    private float cursorWorldX, cursorWorldY;

    /** Увесь ігровий стан. Рухається тільки тіками по 25 мс. */
    private GameSimulation sim;

    /**
     * Lockstep-цикл: годинник, буфер команд і транспорт.
     *
     * <p>Ввід сюди не застосовується напряму — він перетворюється на команди,
     * які виконуються через {@code INPUT_DELAY_TICKS} тіків одночасно в усіх.
     * В одиночній грі шлях той самий, просто транспорт замкнений сам на себе.
     */
    private MatchRunner runner;

    /** Частка тіку для інтерполяції рендеру, оновлюється щокадру. */
    private float renderAlpha;

    /** За кого грає ця копія гри. Визначає і виділення, і туман. */
    private Team localTeam = Team.PLAYER;
    /** Чи в матчі більше одного гравця — від цього залежить поведінка паузи. */
    private boolean multiplayer;

    // Зручні посилання на підсистеми sim — щоб обробники вводу не писали
    // sim.getUnitManager() у кожному рядку.
    private UnitManager unitManager;
    private CombatManager combatManager;
    private VisibilitySystem visibilitySystem;

    private UnitRenderer unitRenderer;
    private TerrainIndicatorRenderer terrainIndicators;
    private MoveMarker moveMarker;
    /** Кола стратегічних точок. Малюються під юнітами, зі своїм проходом bloom. */
    private CapturePointRenderer capturePoints;
    /** Пост-обробка світіння для позначки наказу і кіл точок. */
    private BloomEffect bloom;
    private FormationDragHandler formationDrag;

    private SelectionPanel selectionPanel;
    private Minimap minimap;
    /** Чи тягне гравець камеру по мінікарті просто зараз. */
    private boolean minimapDragging = false;
    private final Vector2 minimapWorld = new Vector2();
    private SpriteBatch panelBatch;
    private CurvedFormationCommand curvedFormation;

    private FogOfWarRenderer fogRenderer;

    private PlateButton formationBtn;
    private boolean formationModeActive = false;

    private final LostBatalion game;
    private final ScenarioCard scenario;
    /** Seed ігрового RNG. У мультиплеєрі приходить від хоста в StartMatch. */
    private final long rngSeed;
    /** Канал матчу. null → одиночна гра, підставиться петля. */
    private final MatchTransport transport;

    public OrthographicCamera camera;
    private SpriteBatch batch;
    private SpriteBatch uiBatch;
    private ShapeRenderer shapes;
    private Texture mapTexture;
    private ExtendViewport gameViewport;

    private float mapWidth, mapHeight;

    /** Одиночна гра: seed довільний, відтворюваність нікому не потрібна. */
    public GameScreen(LostBatalion game, ScenarioCard scenario) {
        this(game, scenario, System.nanoTime(), null);
    }

    public GameScreen(LostBatalion game, ScenarioCard scenario, long rngSeed) {
        this(game, scenario, rngSeed, null);
    }

    /**
     * @param transport канал матчу; null означає одиночну гру, і тоді
     *                  підставляється {@link LocalMatchTransport} — той самий
     *                  lockstep-цикл, тільки замкнений сам на себе
     */
    public GameScreen(LostBatalion game, ScenarioCard scenario, long rngSeed,
                      MatchTransport transport) {
        this.game      = game;
        this.scenario  = scenario;
        this.rngSeed   = rngSeed;
        this.transport = transport;
    }

    @Override
    public void show() {
        batch      = new SpriteBatch();
        uiBatch    = new SpriteBatch();
        panelBatch = new SpriteBatch();
        shapes     = new ShapeRenderer();

        if (scenario.texturePath != null && Gdx.files.internal(scenario.texturePath).exists())
            mapTexture = new Texture(Gdx.files.internal(scenario.texturePath));

        mapWidth  = mapTexture != null ? mapTexture.getWidth()  : 900f;
        mapHeight = mapTexture != null ? mapTexture.getHeight() : 580f;

        camera = new OrthographicCamera();
        gameViewport = new ExtendViewport(900, 580, camera);
        gameViewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);

        String maskPath = buildMaskPath(scenario.maskPath, scenario.texturePath);
        terrainMask      = new TerrainMaskManager(maskPath);                    // ліс + річки
        terrainCombatMask= new TerrainMaskManager(scenario.terrainMaskPath);    // яруси висот
        terrain          = new TerrainQuery(terrainMask, terrainCombatMask);

        sim = new GameSimulation(terrain, mapWidth, mapHeight, rngSeed);
        sim.spawnInitialForces();

        MatchTransport channel = transport != null ? transport : new LocalMatchTransport();
        runner      = new MatchRunner(sim, channel);
        localTeam   = Team.forPlayer(runner.getLocalPlayerId());
        multiplayer = channel.getPlayerIds().length > 1;

        unitManager      = sim.getUnitManager();
        combatManager    = sim.getCombatManager();
        visibilitySystem = sim.getVisibility();

        // Камера дивиться на власну армію, а не в центр карти: у 1v1 сторони
        // розведені по краях, і гість інакше стартував би дивлячись у поле.
        camera.position.set(localTeam == Team.PLAYER ? mapWidth * 0.25f : mapWidth * 0.75f,
                            mapHeight / 2f, 0);
        camera.update();

        unitRenderer      = new UnitRenderer();
        terrainIndicators = new TerrainIndicatorRenderer();
        moveMarker      = new MoveMarker();
        capturePoints   = new CapturePointRenderer();
        bloom           = new BloomEffect(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        formationDrag   = new FormationDragHandler();
        curvedFormation = new CurvedFormationCommand();

        selectionPanel   = new SelectionPanel();
        minimap          = new Minimap(mapTexture, mapWidth, mapHeight);
        fogRenderer      = new FogOfWarRenderer(mapWidth, mapHeight, terrain);
        fogRenderer.setViewer(localTeam);
        forestTooltip    = new ForestTooltip("ui/forest_tooltip.png");

        selectionPanel.setListener(new SelectionPanel.CommandListener() {
            @Override
            public void onCurvedFormation() {
                if (curvedFormation.isDrawing()) {
                    curvedFormation.cancel();
                    selectionPanel.setFormationActive(false);
                } else {
                    selectionPanel.setFormationActive(true);
                }
            }
        });

        UIFactory.disposeAll();
        pauseStage = new Stage(new ScreenViewport(), batch);
        buildPauseOverlay();
        hudStage = new Stage(new ScreenViewport(), batch);
        buildHud();
        modalStage = new Stage(new ScreenViewport(), batch);

        InputMultiplexer mux = new InputMultiplexer();
        // Вікно десинхрону перехоплює ввід першим: поки стан розійшовся,
        // командувати військами не можна взагалі.
        mux.addProcessor(new InputAdapter() {
            @Override public boolean touchDown(int x, int y, int p, int b) { if (!modalActive()) return false; return modalStage.touchDown(x,y,p,b); }
            @Override public boolean touchUp  (int x, int y, int p, int b) { if (!modalActive()) return false; return modalStage.touchUp  (x,y,p,b); }
            @Override public boolean touchDragged(int x, int y, int p)     { if (!modalActive()) return false; return modalStage.touchDragged(x,y,p); }
            @Override public boolean mouseMoved  (int x, int y)             { if (!modalActive()) return false; return modalStage.mouseMoved(x,y); }
            @Override public boolean keyDown(int k)                         { return modalActive(); }
        });
        mux.addProcessor(new InputAdapter() {
            @Override public boolean touchDown(int x, int y, int p, int b) { if (!paused) return false; return pauseStage.touchDown(x,y,p,b); }
            @Override public boolean touchUp  (int x, int y, int p, int b) { if (!paused) return false; return pauseStage.touchUp  (x,y,p,b); }
            @Override public boolean touchDragged(int x, int y, int p)     { if (!paused) return false; return pauseStage.touchDragged(x,y,p); }
            @Override public boolean mouseMoved  (int x, int y)             { if (!paused) return false; return pauseStage.mouseMoved(x,y); }
        });
        mux.addProcessor(hudStage);

        boolean isMobile = Gdx.app.getType() != Application.ApplicationType.Desktop;
        if (isMobile) {
            mux.addProcessor(buildKeyInput());
            mux.addProcessor(new MobileTouchHandler(this));
            mux.addProcessor(new GestureDetector(new GameInputHandler(this)));
        } else {
            mux.addProcessor(buildGameInput());
            mux.addProcessor(new GestureDetector(20, 0.4f, 0.8f, 0.15f, new GameInputHandler(this)));
        }
        game.setScreenInputProcessor(mux);
    }

    private void buildHud() {
        boolean isMobile = Gdx.app.getType() != Application.ApplicationType.Desktop;
        // Словом, а не піктограмою: ані Cinzel, ані PT Serif не мають гліфів
        // ☰ ≡ ✕, і кнопка малювалась порожнім квадратом. Тягнути заради трьох
        // значків окремий шрифт із символами не варто.
        PlateButton burgerBtn = PlateButton.action("МЕНЮ");
        burgerBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { togglePause(); }
        });
        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(12f);
        root.add(burgerBtn).size(84f, 40f);

        // Підказка про очікування чужих наказів. У lockstep гра просто стоїть,
        // і без пояснення це виглядає як зависання.
        waitLabel = new Label("", UIFactory.createHintStyle());
        waitLabel.setVisible(false);
        Table waitRow = new Table();
        waitRow.setFillParent(true);
        waitRow.top().padTop(18f);
        waitRow.add(waitLabel);
        hudStage.addActor(waitRow);

        if (isMobile) {
            formationBtn = PlateButton.action(FORMATION_OFF);
            formationBtn.setVisible(false);
            formationBtn.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent e, Actor a) {
                    formationModeActive = !formationModeActive;
                    formationBtn.setText(formationModeActive ? FORMATION_ON : FORMATION_OFF);
                    if (!formationModeActive) formationDrag.cancel();
                }
            });
            Table bottomBar = new Table();
            bottomBar.setFillParent(true);
            bottomBar.add(formationBtn).size(64f, 54f).expand().bottom().padBottom(48f);
            hudStage.addActor(bottomBar);
        }
        hudStage.addActor(root);
    }

    @Override
    public void render(float delta) {
        // Кнопки «Меню» і «Вийти» живуть у модалках і на HUD, тобто спрацьовують
        // усередині цього ж кадру, під час stage.act(). Перехід на інший екран
        // звільняє GameScreen негайно, разом із батчами й текстурами —
        // усе, що після цього, малювало б уже нічим.
        if (disposed) return;

        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (!paused) {
            handleCameraMovement(delta);

            if (curvedFormation.isDrawing() && Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                Vector3 cur = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));
                curvedFormation.tickCurrentCursor(cur.x, cur.y);
            }

            updateTerrainUnderCursor();
        }

        // ── Симуляція ────────────────────────────────────────────────────
        // У матчі вона не зупиняється навіть на паузі: суперник не зобов'язаний
        // чекати, поки хтось читає меню. Пауза лишається паузою тільки в
        // одиночній грі — там зупиняти нікого.
        if (!paused || multiplayer) {
            runner.update(delta);
            renderAlpha = runner.getRenderAlpha();
        }
        if (dropNoticeTimer > 0f) dropNoticeTimer -= delta;
        updateWaitHint();
        updateModals();
        if (disposed) return;

        if (!paused) {
            // ── Візуал: за часом кадру, на стан гри не впливає ─────────────
            moveMarker.update(delta);
            capturePoints.update(delta);
            combatManager.updateVisuals(delta);
            combatManager.updatePopups(delta);
            selectionPanel.update(delta, unitManager.getSelectedUnits());

            if (formationBtn != null) {
                formationBtn.setVisible(unitManager.hasSelection());
                if (!unitManager.hasSelection() && formationModeActive) {
                    formationModeActive = false;
                    formationBtn.setText(FORMATION_OFF);
                    formationDrag.cancel();
                }
            }

            if (formationDrag.isPressed()) {
                Vector3 cur = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));
                formationDrag.update(delta, cur.x, cur.y);
            }
        }
        camera.update();

        // Рендер карти та юнітів
        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        if (mapTexture != null) batch.draw(mapTexture, 0, 0);
        batch.end();

        // Кола стратегічних точок — окремим проходом bloom і ДО юнітів: це
        // мітка на землі, а не над військами. Позначка наказу світиться своїм
        // проходом нижче, бо вона, навпаки, має лежати поверх усього.
        bloom.begin(gameViewport);
        shapes.setProjectionMatrix(camera.combined);
        capturePoints.draw(shapes, sim.getCapturePoints());
        bloom.end();

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        unitRenderer.drawSprites(batch, unitManager.getAllUnits(), renderAlpha, localTeam);
        // Значки місцевості — поверх юнітів, але під бойовими попапами.
        terrainIndicators.draw(batch, unitManager.getSelectedUnits(), renderAlpha,
                               localTeam, terrain);
        combatManager.drawPopups(batch);
        batch.end();

        shapes.setProjectionMatrix(camera.combined);
        unitRenderer.setProjectionMatrix(camera.combined);
        unitRenderer.drawOverlays(unitManager.getAllUnits(), renderAlpha, localTeam);

        combatManager.drawShots(shapes);
        formationDrag.draw(shapes);

        if (curvedFormation.isDrawing()) {
            batch.setProjectionMatrix(camera.combined);
            curvedFormation.draw(batch, camera.zoom);
        }

        if (moveMarker.isActive()) {
            // Позначка йде в окремий буфер, звідти повертається вже зі світінням.
            bloom.begin(gameViewport);
            batch.setProjectionMatrix(camera.combined);
            moveMarker.draw(batch);
            bloom.end();
        }
        if (selecting && !paused && !clickConsumedByUnit) drawSelectionRect();

        // Артилерія: снаряди в польоті + вибухи + індикатор заряджання
        if (!paused) {
            // Без bloom: ефект пострілу — це сірий дим, а розмиття робило б із
            // нього світляну хмару вдвічі більшу за сам вибух. Яскраві частинки
            // тут і так додаються (premultiplied), тобто світяться самі.
            batch.setProjectionMatrix(camera.combined);
            shapes.setProjectionMatrix(camera.combined);
            combatManager.drawArtilleryEffects(batch, shapes);

            shapes.setProjectionMatrix(camera.combined);
            combatManager.drawArtilleryAim(shapes);
        }

        // Туман війни
        if (!paused) {
            shapes.setProjectionMatrix(camera.combined);
            fogRenderer.render(shapes, camera, unitManager.getAllUnits());
            boolean altHeld = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT);
            if (altHeld) fogRenderer.renderCursorSightOverlay(shapes, camera,
                                                              cursorWorldX, cursorWorldY);
        }

        // Підказка лісу
        if (!paused && currentTerrain == TerrainType.FOREST) {
            uiBatch.begin();
            forestTooltip.draw(uiBatch, cursorScreenX, cursorScreenY);
            uiBatch.end();
        }

        if (!paused) {
            selectionPanel.draw(panelBatch, shapes, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            minimap.draw(uiBatch, shapes, camera,
                         unitManager.getAllUnits(), localTeam,
                         Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }

        hudStage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        hudStage.act(delta);
        if (disposed) return;
        hudStage.draw();

        if (paused) {
            pauseStage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
            pauseStage.act(delta);
            if (disposed) return;
            pauseStage.draw();
        }

        if (modalActive()) {
            modalStage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
            modalStage.act(delta);
            if (disposed) return;
            modalStage.draw();
        }
    }

    private boolean modalActive() { return desyncOverlay != null || noticeOverlay != null; }

    /**
     * Показати або сховати вікно розсинхронізації.
     *
     * <p>Вікно зникає само, коли ресинк вдався: {@code MatchRunner} чистить
     * книгу хешів, і десинхрону більше немає. Тому стан вікна не тримається
     * окремим прапорцем — він завжди виводиться з цикла.
     */
    private void updateModals() {
        // Втрачений власний канал важливіший за все: із нього виходу немає,
        // і показувати поверх нього кнопку синхронізації було б знущанням.
        if (runner.isDisconnected() || runner.isAlone()) {
            showNoticeIfNeeded();
            return;
        }

        boolean shouldShow = runner.isDesynced();

        if (shouldShow && desyncOverlay == null) {
            modalStage.clear();
            desyncOverlay = new DesyncOverlay(modalStage, runner, new DesyncOverlay.Listener() {
                @Override public void onResync() { runner.beginResync(); }
                @Override public void onLeave()  { leaveToMenu(); }
            });
        } else if (!shouldShow && desyncOverlay != null) {
            modalStage.clear();
            desyncOverlay = null;
        } else if (desyncOverlay != null) {
            desyncOverlay.update(runner);
        }
    }

    /**
     * Вікно «далі не піде»: або обірвався власний канал, або в матчі не
     * лишилось суперників.
     *
     * <p>Останнє в 1v1 означає, що опонент вийшов. Механічно симуляція готова
     * крутитись і далі, але грати вже нема з ким, тож чесніше сказати це прямо,
     * ніж лишити гравця ганяти війська по порожній карті.
     */
    private void showNoticeIfNeeded() {
        if (noticeOverlay != null) return;

        String title, text;
        if (runner.isDisconnected()) {
            String reason = runner.getDisconnectReason();
            title = "Зв'язок втрачено";
            text  = (reason == null ? "З'єднання обірвалось." : reason)
                  + " Матч продовжити не можна.";
        } else {
            title = "Суперник вийшов";
            text  = String.join(", ", runner.getDroppedNicks())
                  + " більше не в матчі. Грати нема з ким.";
        }

        modalStage.clear();
        desyncOverlay = null;
        noticeOverlay = new MatchNoticeOverlay(modalStage, title, text, this::leaveToMenu);
    }

    private void leaveToMenu() {
        game.setScreen(new io.jababa.lost_batalion.screens.scenario.ScenarioScreen(game));
    }

    private void updateWaitHint() {
        if (waitLabel == null) return;

        // Про десинхрон і про обрив говорять модальні вікна — дублювати їх
        // підказкою під ними немає сенсу.
        if (modalActive() || runner.isDesynced() || runner.isDisconnected()) {
            waitLabel.setVisible(false);
            return;
        }

        // Хтось вибув, але матч триває — сказати про це один раз і згаснути.
        if (runner.getDroppedNicks().size() > shownDropCount) {
            shownDropCount = runner.getDroppedNicks().size();
            dropNoticeTimer = DROP_NOTICE_SECONDS;
        }
        if (dropNoticeTimer > 0f) {
            waitLabel.setText(String.join(", ", runner.getDroppedNicks())
                            + " вибув з матчу. Гра триває.");
            waitLabel.setStyle(UIFactory.createErrorStyle());
            waitLabel.setVisible(true);
            return;
        }

        boolean show = runner.isWaiting() && runner.getWaitingFrames() > WAIT_HINT_FRAMES;
        waitLabel.setVisible(show);
        if (show) {
            int who = runner.getWaitingForPlayer();
            // Коротка затримка — це просто мережа; довга вже варта окремих слів.
            boolean lagging = runner.isLagWarning();
            waitLabel.setStyle(lagging ? UIFactory.createErrorStyle() : UIFactory.createHintStyle());
            if (who < 0) {
                waitLabel.setText("Очікування…");
            } else if (lagging) {
                waitLabel.setText("Гравець #" + who + " гальмує матч ("
                                + (int) (runner.getWaitingMillis() / 1000f) + " с)…");
            } else {
                waitLabel.setText("Очікуємо наказів гравця #" + who + "…");
            }
        }
    }

    // ── Накази ────────────────────────────────────────────────────────────
    //
    // Ввід нічого не змінює одразу. Він складає команду, яка через
    // INPUT_DELAY_TICKS тіків виконається однаково в усіх — і в автора теж.
    // Маркери й мітки натомість показуються негайно: підтвердження кліку має
    // бути миттєвим, інакше керування відчувається залиплим.

    public void issueMove(float worldX, float worldY) {
        if (!unitManager.hasSelection()) return;
        runner.issue(new MoveCommand(runner.getLocalPlayerId(), unitManager.selectedIds(),
                                     Fixed.fromFloat(worldX), Fixed.fromFloat(worldY)));
        moveMarker.show(worldX, worldY, MoveMarker.MarkerType.MOVE);
    }

    /**
     * Рух із пошуком найшвидшого шляху — подвійний ПКМ.
     *
     * <p>Перший клік уже відправив звичайний наказ, і скасувати його не можна:
     * він пішов у мережу. Тому другий клік просто перекриває його маршрутом —
     * юніти встигають зробити крок-два навпростець, а потім повертають на
     * обхід. Альтернатива — притримувати КОЖЕН наказ на час очікування
     * подвійного кліку — зробила б звичайне керування помітно млявішим заради
     * рідкої дії.
     */
    public void issuePathMove(float worldX, float worldY) {
        if (!unitManager.hasSelection()) return;
        runner.issue(new PathMoveCommand(runner.getLocalPlayerId(), unitManager.selectedIds(),
                                         Fixed.fromFloat(worldX), Fixed.fromFloat(worldY)));
        moveMarker.show(worldX, worldY, MoveMarker.MarkerType.MOVE);
    }

    public void issueMoveLine(float x1, float y1, float x2, float y2) {
        if (!unitManager.hasSelection()) return;
        runner.issue(new MoveLineCommand(runner.getLocalPlayerId(), unitManager.selectedIds(),
                                         Fixed.fromFloat(x1), Fixed.fromFloat(y1),
                                         Fixed.fromFloat(x2), Fixed.fromFloat(y2)));
        moveMarker.show((x1 + x2) / 2f, (y1 + y2) / 2f, MoveMarker.MarkerType.MOVE);
    }

    public void issueCurve(long[] pointsXY) {
        if (pointsXY == null || !unitManager.hasSelection()) return;
        runner.issue(new CurveFormationCommand(runner.getLocalPlayerId(),
                                               unitManager.selectedIds(), pointsXY));
    }

    public void issueAttack(Unit enemy) {
        if (enemy == null || !unitManager.hasSelection()) return;
        runner.issue(new AttackCommand(runner.getLocalPlayerId(), unitManager.selectedIds(),
                                       enemy.id));
        combatManager.showTargetPopup(enemy);
        moveMarker.show(enemy.worldX(), enemy.worldY(), MoveMarker.MarkerType.ATTACK);
    }

    public void issueStop() {
        if (!unitManager.hasSelection()) return;
        runner.issue(new StopCommand(runner.getLocalPlayerId(), unitManager.selectedIds()));
    }

    /**
     * Чи це другий клік подвійного ПКМ.
     *
     * <p>Перевіряється і час, і зсув курсора: два кліки в різних кінцях екрана
     * — це два різні накази, навіть якщо гравець зробив їх швидко.
     */
    private boolean isDoubleRmb(int screenX, int screenY) {
        if (lastRmbTime == 0) return false;
        if (System.currentTimeMillis() - lastRmbTime > DOUBLE_RMB_MILLIS) return false;
        return Math.abs(screenX - lastRmbX) <= DOUBLE_RMB_SLACK
            && Math.abs(screenY - lastRmbY) <= DOUBLE_RMB_SLACK;
    }

    /** Ворог під курсором очима локального гравця — для збирання наказу атаки. */
    public Unit enemyAt(float worldX, float worldY) {
        return combatManager.tryGetEnemyAtPoint(worldX, worldY, localTeam);
    }

    // ── Геттери ───────────────────────────────────────────────────────────
    public GameSimulation getSimulation()              { return sim; }
    public MatchRunner getRunner()                     { return runner; }
    public Team getLocalTeam()                         { return localTeam; }
    public boolean isFormationModeActive()             { return formationModeActive; }
    public boolean isPaused()                          { return paused; }
    public boolean isSelecting()                       { return selecting; }
    public OrthographicCamera getCamera()              { return camera; }
    public UnitManager getUnitManager()                { return unitManager; }
    public MoveMarker getMoveMarker()                  { return moveMarker; }
    public float getMapWidth()                         { return mapWidth; }
    public float getMapHeight()                        { return mapHeight; }
    public FormationDragHandler getFormationDrag()     { return formationDrag; }
    public CombatManager getCombatManager()            { return combatManager; }
    public CurvedFormationCommand getCurvedFormation() { return curvedFormation; }

    public void applyFormationLine() {
        boolean applied = formationDrag.onRmbUp();
        if (applied) {
            issueMoveLine(formationDrag.getStartX(), formationDrag.getStartY(),
                          formationDrag.getEndX(),   formationDrag.getEndY());
        }
        formationModeActive = false;
        if (formationBtn != null) formationBtn.setText(FORMATION_OFF);
    }

    public void startSelecting(float wx, float wy) {
        selecting = true; clickConsumedByUnit = false;
        selStartX = wx; selCurX = wx; selStartY = wy; selCurY = wy;
    }
    public void updateSelection(float wx, float wy) { selCurX = wx; selCurY = wy; }
    public void finishSelection() {
        if (!selecting) return;
        float rx = Math.min(selStartX, selCurX), ry = Math.min(selStartY, selCurY);
        float rw = Math.abs(selCurX - selStartX), rh = Math.abs(selCurY - selStartY);
        if (rw > 6f && rh > 6f) unitManager.selectInRect(rx, ry, rw, rh, true, localTeam);
        selecting = false;
    }
    public void setClickConsumedByUnit(boolean v)  { clickConsumedByUnit = v; }
    public void setZoom(float z) { camera.zoom = MathUtils.clamp(z, ZOOM_MIN, ZOOM_MAX); }

    @Override public void resize(int w, int h) {
        gameViewport.update(w, h, false);
        if (bloom != null) bloom.resize(w, h);
        hudStage.getViewport().update(w, h, true);
        pauseStage.getViewport().update(w, h, true);
        if (modalStage != null) modalStage.getViewport().update(w, h, true);
    }
    @Override public void hide()   { game.setScreenInputProcessor(new InputAdapter()); }
    @Override public void pause()  {}
    @Override public void resume() {}

    /** Захист від подвійного звільнення: dispose може прийти і з перемикання
     *  екрана, і з закриття гри. Другий раз має бути безпечним. */
    private boolean disposed;

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;

        if (runner          != null) runner.close();
        if (batch           != null) batch.dispose();
        if (uiBatch         != null) uiBatch.dispose();
        if (panelBatch      != null) panelBatch.dispose();
        if (shapes          != null) shapes.dispose();
        if (mapTexture      != null) mapTexture.dispose();
        if (pauseStage      != null) pauseStage.dispose();
        if (hudStage        != null) hudStage.dispose();
        if (modalStage      != null) modalStage.dispose();
        if (terrainMask     != null) terrainMask.dispose();
        if (forestTooltip   != null) forestTooltip.dispose();
        if (unitRenderer    != null) unitRenderer.dispose();
        if (terrainIndicators != null) terrainIndicators.dispose();
        if (bloom           != null) bloom.dispose();
        if (moveMarker      != null) moveMarker.dispose();
        if (combatManager   != null) combatManager.dispose();
        if (selectionPanel  != null) selectionPanel.dispose();
        if (minimap         != null) minimap.dispose();
        if (curvedFormation != null) curvedFormation.dispose();
        if (terrainCombatMask != null) terrainCombatMask.dispose();
        UIFactory.disposeAll();
    }

    // ── Утиліти ───────────────────────────────────────────────────────────

    private boolean clickOnPanel(int sx, int sy) {
        return selectionPanel.containsScreenPoint(sx, Gdx.graphics.getHeight() - sy);
    }

    private boolean clickOnMinimap(int sx, int sy) {
        return minimap != null
            && minimap.containsScreenPoint(sx, Gdx.graphics.getHeight() - sy);
    }

    /** Перенести камеру в точку карти під курсором на мінікарті. */
    private void moveCameraFromMinimap(int sx, int sy) {
        if (minimap == null) return;
        minimap.worldAt(sx, Gdx.graphics.getHeight() - sy, minimapWorld);
        camera.position.set(minimapWorld.x, minimapWorld.y, 0);
        camera.update();
    }

    private void updateTerrainUnderCursor() {
        cursorScreenX = Gdx.input.getX();
        cursorScreenY = Gdx.input.getY();
        Vector3 world = camera.unproject(new Vector3(cursorScreenX, cursorScreenY, 0));
        cursorWorldX = world.x; cursorWorldY = world.y;
        if (terrain != null) {
            currentTerrain = terrain.isForest(world.x, world.y)
                ? TerrainType.FOREST
                : terrain.elevation(world.x, world.y);
        } else currentTerrain = TerrainType.NONE;
    }

    private String buildMaskPath(String explicit, String tex) {
        if (explicit != null) return explicit;
        if (tex      == null) return null;
        int dot = tex.lastIndexOf('.');
        return dot < 0 ? tex + "_mask" : tex.substring(0, dot) + "_mask" + tex.substring(dot);
    }

    private void handleCameraMovement(float delta) {
        float speed = CAM_SPEED * camera.zoom;
        if (Gdx.input.isKeyPressed(Input.Keys.W)||Gdx.input.isKeyPressed(Input.Keys.UP))    camera.position.y+=speed*delta;
        if (Gdx.input.isKeyPressed(Input.Keys.S)||Gdx.input.isKeyPressed(Input.Keys.DOWN))  camera.position.y-=speed*delta;
        if (Gdx.input.isKeyPressed(Input.Keys.A)||Gdx.input.isKeyPressed(Input.Keys.LEFT))  camera.position.x-=speed*delta;
        if (Gdx.input.isKeyPressed(Input.Keys.D)||Gdx.input.isKeyPressed(Input.Keys.RIGHT)) camera.position.x+=speed*delta;
        camera.position.x = MathUtils.clamp(camera.position.x, 0, mapWidth);
        camera.position.y = MathUtils.clamp(camera.position.y, 0, mapHeight);
    }

    private void drawSelectionRect() {
        float x = Math.min(selStartX, selCurX), y = Math.min(selStartY, selCurY);
        float w = Math.abs(selCurX-selStartX),  h = Math.abs(selCurY-selStartY);
        if (w<1f||h<1f) return;
        shapes.setProjectionMatrix(camera.combined);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, 0.12f);    // білий напівпрозорий fill
        shapes.rect(x, y, w, h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1f, 1f, 1f, 0.90f);    // білий контур
        shapes.rect(x, y, w, h);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private void togglePause() {
        paused = !paused;
        pauseStage.clear();
        if (paused) {
            buildPauseOverlay();
        } else if (!multiplayer) {
            // Час, що минув на паузі, не має перетворитись на пачку тіків
            // наздоганяння в перший же кадр після зняття паузи. У матчі
            // симуляція не спинялась, тож скидати нічого.
            runner.resetClock();
        }
    }

    private void buildPauseOverlay() {
        pauseOverlay = new PauseOverlay(pauseStage, new PauseOverlay.PauseListener() {
            @Override public void onResume()        { paused = false; pauseStage.clear(); }
            @Override public void onReturnToLobby() { game.setScreen(new io.jababa.lost_batalion.screens.scenario.ScenarioScreen(game)); }
            @Override public void onSettings()      {}
            @Override public void onExit()          { Gdx.app.exit(); }
        });
    }

    private InputAdapter buildKeyInput() {
        return new InputAdapter() {
            @Override public boolean keyDown(int k) {
                if (k == Input.Keys.ESCAPE) {
                    togglePause(); return true;
                }
                return false;
            }
        };
    }

    private InputAdapter buildGameInput() {
        return new InputAdapter() {
            private boolean awaitingDrawStart = false;

            @Override public boolean keyDown(int k) {
                if (k == Input.Keys.ESCAPE) {
                    if (curvedFormation.isDrawing()) { curvedFormation.cancel(); selectionPanel.setFormationActive(false); awaitingDrawStart=false; return true; }
                    togglePause(); return true;
                }
                // Окремої клавіші «стій» тут навмисно немає: S уже зайнята рухом
                // камери. issueStop() лишається для UI, який її викличе.
                return false;
            }

            @Override public boolean scrolled(float ax, float ay) {
                if (paused) return false;
                camera.zoom = MathUtils.clamp(camera.zoom + ay * ZOOM_STEP, ZOOM_MIN, ZOOM_MAX);
                camera.update(); return true;
            }

            @Override
            public boolean touchDown(int sx, int sy, int ptr, int btn) {
                if (paused) return false;

                if (btn == Input.Buttons.LEFT) {
                    // Мінікарта перехоплює клік раніше за все інше: вона
                    // лежить поверх світу, і виділяти крізь неї не можна.
                    if (clickOnMinimap(sx, sy)) {
                        minimapDragging = true;
                        moveCameraFromMinimap(sx, sy);
                        return true;
                    }

                    // Клік по панелі
                    if (clickOnPanel(sx, sy)) {
                        int sh = Gdx.graphics.getHeight();
                        boolean wasForm = selectionPanel.isFormationActive();
                        selectionPanel.handleClick(sx, sh - sy);
                        boolean nowForm = selectionPanel.isFormationActive();
                        if (!wasForm && nowForm) { awaitingDrawStart=true; curvedFormation.cancel(); }
                        if  (wasForm && !nowForm){ awaitingDrawStart=false; curvedFormation.cancel(); }
                        return true;
                    }

                    Vector3 w = camera.unproject(new Vector3(sx, sy, 0));
                    if (awaitingDrawStart && selectionPanel.isFormationActive()) {
                        awaitingDrawStart=false; curvedFormation.startDraw(w.x, w.y); return true;
                    }
                    if (curvedFormation.isDrawing()) return true;
                    if (unitManager == null) return false;
                    boolean shift = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)||Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                    clickConsumedByUnit = unitManager.trySelectAtPoint(w.x, w.y, shift, localTeam);
                    if (!clickConsumedByUnit) startSelecting(w.x, w.y);
                    return true;
                }

                if (btn == Input.Buttons.RIGHT) {
                    if (curvedFormation.isDrawing()) { curvedFormation.cancel(); selectionPanel.setFormationActive(false); awaitingDrawStart=false; return true; }
                    Vector3 w = camera.unproject(new Vector3(sx, sy, 0));
                    formationDrag.onRmbDown(w.x, w.y); return true;
                }
                return false;
            }

            @Override
            public boolean touchDragged(int sx, int sy, int ptr) {
                // Протяг по мінікарті — це та сама навігація, тільки без
                // відпускання кнопки; за межі рамки координата обрізається.
                if (minimapDragging) { moveCameraFromMinimap(sx, sy); return true; }
                if (curvedFormation.isDrawing()) {
                    Vector3 w = camera.unproject(new Vector3(sx,sy,0));
                    curvedFormation.addPoint(w.x, w.y); return true;
                }
                if (selecting) {
                    Vector3 w = camera.unproject(new Vector3(sx,sy,0));
                    updateSelection(w.x, w.y); return true;
                }
                return false;
            }

            @Override
            public boolean touchUp(int sx, int sy, int ptr, int btn) {
                if (paused) return false;

                if (btn == Input.Buttons.LEFT) {
                    if (minimapDragging) { minimapDragging = false; return true; }
                    if (curvedFormation.isDrawing()) {
                        issueCurve(curvedFormation.finishAndCollect());
                        selectionPanel.setFormationActive(false); awaitingDrawStart=false; return true;
                    }
                    if (!selecting) return false;
                    Vector3 w = camera.unproject(new Vector3(sx,sy,0));
                    selCurX=w.x; selCurY=w.y;
                    float rx=Math.min(selStartX,selCurX), ry=Math.min(selStartY,selCurY);
                    float rw=Math.abs(selCurX-selStartX), rh=Math.abs(selCurY-selStartY);
                    boolean shift=Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)||Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                    if (rw>6f&&rh>6f) unitManager.selectInRect(rx,ry,rw,rh,shift,localTeam);
                    else if (!clickConsumedByUnit&&!shift) unitManager.clearSelection();
                    selecting=false; return true;
                }

                if (btn == Input.Buttons.RIGHT) {
                    Vector3 w = camera.unproject(new Vector3(sx,sy,0));
                    boolean wasForm = formationDrag.onRmbUp();
                    if (unitManager.hasSelection()) {
                        if (wasForm) {
                            issueMoveLine(formationDrag.getStartX(), formationDrag.getStartY(),
                                          formationDrag.getEndX(),   formationDrag.getEndY());
                            lastRmbTime = 0;   // драг не рахується за клік
                        } else {
                            Unit enemy = enemyAt(w.x, w.y);
                            if (enemy != null) {
                                issueAttack(enemy);
                                lastRmbTime = 0;
                            } else if (isDoubleRmb(sx, sy)) {
                                issuePathMove(w.x, w.y);
                                lastRmbTime = 0;   // третій клік не має рахуватись
                            } else {
                                issueMove(w.x, w.y);
                                lastRmbTime = System.currentTimeMillis();
                                lastRmbX = sx; lastRmbY = sy;
                            }
                        }
                    }
                    return true;
                }
                return false;
            }
        };
    }
}
