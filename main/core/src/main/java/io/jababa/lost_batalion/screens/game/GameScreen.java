package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.*;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.input.GestureDetector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.commands.ArtilleryStrikeCommand;
import io.jababa.lost_batalion.commands.CurvedFormationCommand;
import io.jababa.lost_batalion.mobile.GameInputHandler;
import io.jababa.lost_batalion.mobile.MobileTouchHandler;
import io.jababa.lost_batalion.screens.renderer.UnitRenderer;
import io.jababa.lost_batalion.screens.scenario.ScenarioCard;
import io.jababa.lost_batalion.screens.ui.SelectionPanel;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.units.*;
import io.jababa.lost_batalion.visibility.FogOfWarRenderer;
import io.jababa.lost_batalion.visibility.VisibilitySystem;

public class GameScreen implements Screen {

    private static final float CAM_SPEED = 400f;
    private static final float ZOOM_MIN  = 0.3f;
    private static final float ZOOM_MAX  = 2.0f;
    private static final float ZOOM_STEP = 0.1f;

    private boolean selecting;
    private float selStartX, selStartY, selCurX, selCurY;
    private boolean clickConsumedByUnit = false;

    private boolean paused = false;
    private PauseOverlay pauseOverlay;
    private Stage pauseStage;
    private Stage hudStage;

    private TerrainMaskManager terrainMask;
    private ForestTooltip forestTooltip;
    private TerrainMaskManager terrainCombatMask;
    private TerrainType currentTerrain = TerrainType.NONE;
    private int cursorScreenX, cursorScreenY;
    private float cursorWorldX, cursorWorldY;

    private UnitManager unitManager;
    private UnitRenderer unitRenderer;
    private MoveMarker moveMarker;
    private FormationDragHandler formationDrag;
    private CombatManager combatManager;

    private SelectionPanel selectionPanel;
    private SpriteBatch panelBatch;
    private CurvedFormationCommand curvedFormation;
    private ArtilleryStrikeCommand artilleryCmd;

    private VisibilitySystem visibilitySystem;
    private FogOfWarRenderer fogRenderer;

    private TextButton formationBtn;
    private boolean formationModeActive = false;

    private final LostBatalion game;
    private final ScenarioCard scenario;

    public OrthographicCamera camera;
    private SpriteBatch batch;
    private SpriteBatch uiBatch;
    private ShapeRenderer shapes;
    private Texture mapTexture;
    private ExtendViewport gameViewport;

    private float mapWidth, mapHeight;

    public GameScreen(LostBatalion game, ScenarioCard scenario) {
        this.game     = game;
        this.scenario = scenario;
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
        camera.position.set(mapWidth / 2f, mapHeight / 2f, 0);
        camera.update();

        String maskPath = buildMaskPath(scenario.maskPath, scenario.texturePath);
        terrainMask      = new TerrainMaskManager(maskPath);
        terrainCombatMask= new TerrainMaskManager(scenario.terrainMaskPath);

        unitManager     = new UnitManager();
        unitRenderer    = new UnitRenderer();
        moveMarker      = new MoveMarker();
        formationDrag   = new FormationDragHandler();
        curvedFormation = new CurvedFormationCommand();
        artilleryCmd    = new ArtilleryStrikeCommand();

        // Спавн піхоти
        unitManager.spawnPlayerSquad(mapWidth / 2f, mapHeight / 2f);
        unitManager.spawnPlayerSquad(mapWidth / 2f, mapHeight / 1.7f);
        unitManager.spawnPlayerSquad(mapWidth / 2f, mapHeight / 1.5f);

        // Спавн артилерії гравця
        unitManager.addUnit(new Artillery(Team.PLAYER, mapWidth / 2f - 80f, mapHeight / 2f - 60f));
        unitManager.addUnit(new Artillery(Team.PLAYER, mapWidth / 2f + 80f, mapHeight / 2f - 60f));

        // Ворог
        unitManager.addUnit(new Infantry(Team.ENEMY, mapWidth * 0.75f, mapHeight * 0.6f));
        unitManager.addUnit(new Infantry(Team.ENEMY, mapWidth * 0.75f + Infantry.INF_SIZE + 8f, mapHeight * 0.6f));
        unitManager.addUnit(new Infantry(Team.ENEMY, mapWidth * 0.75f - Infantry.INF_SIZE - 8f, mapHeight * 0.6f));

        combatManager    = new CombatManager(unitManager, terrainCombatMask);
        selectionPanel   = new SelectionPanel();
        visibilitySystem = new VisibilitySystem(terrainMask);
        fogRenderer      = new FogOfWarRenderer(mapWidth, mapHeight, terrainMask);
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

            @Override
            public void onArtilleryFire() {
                if (artilleryCmd.isActive()) {
                    artilleryCmd.cancel();
                    selectionPanel.setArtilleryActive(false);
                } else {
                    Array<Artillery> cannons = getSelectedArtillery();
                    if (cannons.size > 0) {
                        artilleryCmd.beginTargeting(cannons);
                        selectionPanel.setArtilleryActive(true);
                    }
                }
            }
        });

        UIFactory.disposeAll();
        pauseStage = new Stage(new ScreenViewport(), batch);
        buildPauseOverlay();
        hudStage = new Stage(new ScreenViewport(), batch);
        buildHud();

        InputMultiplexer mux = new InputMultiplexer();
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
        TextButton burgerBtn = new TextButton("\u2630", UIFactory.createSmallButtonStyle());
        burgerBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { togglePause(); }
        });
        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(12f);
        root.add(burgerBtn).size(54f, 48f);

        if (isMobile) {
            formationBtn = new TextButton("=", UIFactory.createSmallButtonStyle());
            formationBtn.setVisible(false);
            formationBtn.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent e, Actor a) {
                    formationModeActive = !formationModeActive;
                    formationBtn.setText(formationModeActive ? "X" : "|");
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
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (!paused) {
            handleCameraMovement(delta);

            if (curvedFormation.isDrawing() && Gdx.input.isButtonPressed(Input.Buttons.LEFT)) {
                Vector3 cur = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));
                curvedFormation.tickCurrentCursor(cur.x, cur.y);
            }

            // Оновлення курсора артилерії
            if (artilleryCmd.isTargeting()) {
                Vector3 cur = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));
                artilleryCmd.updateCursor(cur.x, cur.y);
            }

            updateTerrainUnderCursor();
            unitManager.update(delta, terrainMask);
            moveMarker.update(delta);
            combatManager.update(delta);
            combatManager.updatePopups(delta);
            selectionPanel.update(delta, unitManager.getSelectedUnits());

            artilleryCmd.update(delta, unitManager);

            // Скидаємо кнопку коли команда завершена
            if (artilleryCmd.isIdle()) selectionPanel.setArtilleryActive(false);

            visibilitySystem.update(unitManager.getAllUnits());

            if (formationBtn != null) {
                formationBtn.setVisible(unitManager.hasSelection());
                if (!unitManager.hasSelection() && formationModeActive) {
                    formationModeActive = false;
                    formationBtn.setText("\u2261");
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
        unitRenderer.drawSprites(batch, unitManager.getAllUnits());
        combatManager.drawPopups(batch);
        batch.end();

        shapes.setProjectionMatrix(camera.combined);
        unitRenderer.setProjectionMatrix(camera.combined);
        unitRenderer.drawOverlays(unitManager.getAllUnits());

        combatManager.drawShots(shapes);
        formationDrag.draw(shapes);

        if (curvedFormation.isDrawing()) {
            batch.setProjectionMatrix(camera.combined);
            curvedFormation.draw(batch, camera.zoom);
        }

        if (moveMarker.isActive()) moveMarker.draw(shapes);
        if (selecting && !paused && !clickConsumedByUnit) drawSelectionRect();

        // Артилерійська команда (прицілювання + ефекти)
        if (!paused && artilleryCmd.isActive()) {
            batch.setProjectionMatrix(camera.combined);
            shapes.setProjectionMatrix(camera.combined);
            artilleryCmd.draw(batch, shapes);
        }

        // Туман війни
        if (!paused) {
            shapes.setProjectionMatrix(camera.combined);
            fogRenderer.render(shapes, camera, unitManager.getAllUnits());
            boolean altHeld = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT);
            if (altHeld) fogRenderer.renderCursorSightOverlay(shapes, cursorWorldX, cursorWorldY);
        }

        // Підказка лісу
        if (!paused && currentTerrain == TerrainType.FOREST) {
            uiBatch.begin();
            forestTooltip.draw(uiBatch, cursorScreenX, cursorScreenY);
            uiBatch.end();
        }

        if (!paused)
            selectionPanel.draw(panelBatch, shapes, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());

        hudStage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        hudStage.act(delta);
        hudStage.draw();

        if (paused) {
            pauseStage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
            pauseStage.act(delta);
            pauseStage.draw();
        }
    }

    // ── Геттери ───────────────────────────────────────────────────────────
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
    public ArtilleryStrikeCommand getArtilleryCmd()   { return artilleryCmd; }

    public void applyFormationLine() {
        boolean applied = formationDrag.onRmbUp();
        if (applied && unitManager.hasSelection()) {
            unitManager.moveSelectedToLine(
                formationDrag.getStartX(), formationDrag.getStartY(),
                formationDrag.getEndX(),   formationDrag.getEndY(),
                mapWidth, mapHeight);
            float mx = (formationDrag.getStartX() + formationDrag.getEndX()) / 2f;
            float my = (formationDrag.getStartY() + formationDrag.getEndY()) / 2f;
            moveMarker.show(mx, my, MoveMarker.MarkerType.MOVE);
        }
        formationModeActive = false;
        if (formationBtn != null) formationBtn.setText("\u2261");
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
        if (rw > 6f && rh > 6f) unitManager.selectInRect(rx, ry, rw, rh, true);
        selecting = false;
    }
    public void setClickConsumedByUnit(boolean v)  { clickConsumedByUnit = v; }
    public void setZoom(float z) { camera.zoom = MathUtils.clamp(z, ZOOM_MIN, ZOOM_MAX); }

    @Override public void resize(int w, int h) {
        gameViewport.update(w, h, false);
        hudStage.getViewport().update(w, h, true);
        pauseStage.getViewport().update(w, h, true);
    }
    @Override public void hide()   { game.setScreenInputProcessor(new InputAdapter()); }
    @Override public void pause()  {}
    @Override public void resume() {}

    @Override
    public void dispose() {
        if (batch           != null) batch.dispose();
        if (uiBatch         != null) uiBatch.dispose();
        if (panelBatch      != null) panelBatch.dispose();
        if (shapes          != null) shapes.dispose();
        if (mapTexture      != null) mapTexture.dispose();
        if (pauseStage      != null) pauseStage.dispose();
        if (hudStage        != null) hudStage.dispose();
        if (terrainMask     != null) terrainMask.dispose();
        if (forestTooltip   != null) forestTooltip.dispose();
        if (unitRenderer    != null) unitRenderer.dispose();
        if (combatManager   != null) combatManager.dispose();
        if (selectionPanel  != null) selectionPanel.dispose();
        if (curvedFormation != null) curvedFormation.dispose();
        if (artilleryCmd    != null) artilleryCmd.dispose();
        if (terrainCombatMask != null) terrainCombatMask.dispose();
        UIFactory.disposeAll();
    }

    // ── Утиліти ───────────────────────────────────────────────────────────

    private Array<Artillery> getSelectedArtillery() {
        Array<Artillery> res = new Array<>();
        for (Unit u : unitManager.getSelectedUnits())
            if (u instanceof Artillery) res.add((Artillery) u);
        return res;
    }

    private boolean clickOnPanel(int sx, int sy) {
        return selectionPanel.containsScreenPoint(sx, Gdx.graphics.getHeight() - sy);
    }

    private void updateTerrainUnderCursor() {
        cursorScreenX = Gdx.input.getX();
        cursorScreenY = Gdx.input.getY();
        Vector3 world = camera.unproject(new Vector3(cursorScreenX, cursorScreenY, 0));
        cursorWorldX = world.x; cursorWorldY = world.y;
        if (terrainMask != null) {
            boolean isForest = terrainMask.isForestAt(world.x, world.y);
            TerrainType elev = terrainMask.getElevationAt(world.x, world.y);
            currentTerrain   = isForest ? TerrainType.FOREST : elev;
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
        if (paused) buildPauseOverlay();
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
                    if (artilleryCmd.isActive()) { artilleryCmd.cancel(); selectionPanel.setArtilleryActive(false); return true; }
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
                    if (artilleryCmd.isActive()) { artilleryCmd.cancel(); selectionPanel.setArtilleryActive(false); return true; }
                    if (curvedFormation.isDrawing()) { curvedFormation.cancel(); selectionPanel.setFormationActive(false); awaitingDrawStart=false; return true; }
                    togglePause(); return true;
                }
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
                    // Якщо артилерія в TARGETING — фіксуємо ціль (крім кліку по панелі)
                    if (artilleryCmd.isTargeting() && !clickOnPanel(sx, sy)) {
                        Vector3 w = camera.unproject(new Vector3(sx, sy, 0));
                        boolean accepted = artilleryCmd.setTarget(w.x, w.y);
                        // Якщо поза range — нічого не робимо (можна додати звук/flash)
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
                    clickConsumedByUnit = unitManager.trySelectAtPointAnyTeam(w.x, w.y, shift);
                    if (!clickConsumedByUnit) startSelecting(w.x, w.y);
                    return true;
                }

                if (btn == Input.Buttons.RIGHT) {
                    if (artilleryCmd.isActive()) { artilleryCmd.cancel(); selectionPanel.setArtilleryActive(false); return true; }
                    if (curvedFormation.isDrawing()) { curvedFormation.cancel(); selectionPanel.setFormationActive(false); awaitingDrawStart=false; return true; }
                    Vector3 w = camera.unproject(new Vector3(sx, sy, 0));
                    formationDrag.onRmbDown(w.x, w.y); return true;
                }
                return false;
            }

            @Override
            public boolean touchDragged(int sx, int sy, int ptr) {
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
                    if (curvedFormation.isDrawing()) {
                        curvedFormation.finishAndApply(unitManager, mapWidth, mapHeight);
                        selectionPanel.setFormationActive(false); awaitingDrawStart=false; return true;
                    }
                    if (!selecting) return false;
                    Vector3 w = camera.unproject(new Vector3(sx,sy,0));
                    selCurX=w.x; selCurY=w.y;
                    float rx=Math.min(selStartX,selCurX), ry=Math.min(selStartY,selCurY);
                    float rw=Math.abs(selCurX-selStartX), rh=Math.abs(selCurY-selStartY);
                    boolean shift=Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)||Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
                    if (rw>6f&&rh>6f) unitManager.selectInRect(rx,ry,rw,rh,shift);
                    else if (!clickConsumedByUnit&&!shift) unitManager.clearSelection();
                    selecting=false; return true;
                }

                if (btn == Input.Buttons.RIGHT) {
                    if (artilleryCmd.isActive()) return true;
                    Vector3 w = camera.unproject(new Vector3(sx,sy,0));
                    boolean wasForm = formationDrag.onRmbUp();
                    if (unitManager.hasSelection()) {
                        if (wasForm) {
                            combatManager.cancelAttackOrders(unitManager.getSelectedUnits());
                            unitManager.moveSelectedToLine(
                                formationDrag.getStartX(), formationDrag.getStartY(),
                                formationDrag.getEndX(),   formationDrag.getEndY(),
                                mapWidth, mapHeight);
                            float mx=(formationDrag.getStartX()+formationDrag.getEndX())/2f;
                            float my=(formationDrag.getStartY()+formationDrag.getEndY())/2f;
                            moveMarker.show(mx,my,MoveMarker.MarkerType.MOVE);
                        } else {
                            Unit enemy = combatManager.tryGetEnemyAtPoint(w.x, w.y);
                            if (enemy!=null) {
                                combatManager.orderAttack(enemy);
                                moveMarker.show(enemy.position.x,enemy.position.y,MoveMarker.MarkerType.ATTACK);
                            } else {
                                combatManager.cancelAttackOrders(unitManager.getSelectedUnits());
                                unitManager.moveSelectedTo(w.x,w.y,mapWidth,mapHeight);
                                moveMarker.show(w.x,w.y,MoveMarker.MarkerType.MOVE);
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
