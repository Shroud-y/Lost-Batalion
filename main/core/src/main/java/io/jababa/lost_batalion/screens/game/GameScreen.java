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
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.Team;
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
    private TerrainType currentTerrain = TerrainType.NONE;
    private int cursorScreenX, cursorScreenY;

    private UnitManager unitManager;
    private UnitRenderer unitRenderer;
    private MoveMarker moveMarker;
    private FormationDragHandler formationDrag;
    private CombatManager combatManager;

    private SelectionPanel selectionPanel;
    private SpriteBatch panelBatch;
    private CurvedFormationCommand curvedFormation;

    // ── Система видимості ──────────────────────────────────────────────────
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

        if (scenario.texturePath != null && Gdx.files.internal(scenario.texturePath).exists()) {
            mapTexture = new Texture(Gdx.files.internal(scenario.texturePath));
        }

        mapWidth  = mapTexture != null ? mapTexture.getWidth()  : 900f;
        mapHeight = mapTexture != null ? mapTexture.getHeight() : 580f;

        camera = new OrthographicCamera();
        gameViewport = new ExtendViewport(900, 580, camera);
        gameViewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        camera.position.set(mapWidth / 2f, mapHeight / 2f, 0);
        camera.update();

        String maskPath = buildMaskPath(scenario.maskPath, scenario.texturePath);
        terrainMask  = new TerrainMaskManager(maskPath);
        forestTooltip = new ForestTooltip("ui/forest_tooltip.png");

        unitManager     = new UnitManager();
        unitRenderer    = new UnitRenderer();
        moveMarker      = new MoveMarker();
        formationDrag   = new FormationDragHandler();
        curvedFormation = new CurvedFormationCommand();

        unitManager.spawnPlayerSquad(mapWidth / 2f, mapHeight / 2f);
        unitManager.addUnit(new Infantry(Team.ENEMY, mapWidth * 0.75f, mapHeight * 0.6f));
        unitManager.addUnit(new Infantry(Team.ENEMY, mapWidth * 0.75f + Infantry.INF_SIZE + 8f, mapHeight * 0.6f));
        unitManager.addUnit(new Infantry(Team.ENEMY, mapWidth * 0.75f - Infantry.INF_SIZE - 8f, mapHeight * 0.6f));

        combatManager = new CombatManager(unitManager);
        selectionPanel = new SelectionPanel();

        // ── Ініціалізація системи видимості ───────────────────────────────
        visibilitySystem = new VisibilitySystem(terrainMask);
        fogRenderer      = new FogOfWarRenderer(mapWidth, mapHeight);

        selectionPanel.setListener(() -> {
            if (curvedFormation.isDrawing()) {
                curvedFormation.cancel();
                selectionPanel.setFormationActive(false);
            } else {
                selectionPanel.setFormationActive(true);
            }
        });

        UIFactory.disposeAll();

        pauseStage = new Stage(new ScreenViewport(), batch);
        buildPauseOverlay();

        hudStage = new Stage(new ScreenViewport(), batch);
        buildHud();

        InputMultiplexer mux = new InputMultiplexer();

        mux.addProcessor(new InputAdapter() {
            @Override public boolean touchDown(int x, int y, int p, int b) {
                if (!paused) return false; return pauseStage.touchDown(x, y, p, b);
            }
            @Override public boolean touchUp(int x, int y, int p, int b) {
                if (!paused) return false; return pauseStage.touchUp(x, y, p, b);
            }
            @Override public boolean touchDragged(int x, int y, int p) {
                if (!paused) return false; return pauseStage.touchDragged(x, y, p);
            }
            @Override public boolean mouseMoved(int x, int y) {
                if (!paused) return false; return pauseStage.mouseMoved(x, y);
            }
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
            @Override public void changed(ChangeEvent event, Actor actor) { togglePause(); }
        });

        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(12f);
        root.add(burgerBtn).size(54f, 48f);

        if (isMobile) {
            formationBtn = new TextButton("=", UIFactory.createSmallButtonStyle());
            formationBtn.setVisible(false);
            formationBtn.addListener(new ChangeListener() {
                @Override public void changed(ChangeEvent event, Actor actor) {
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

            if (curvedFormation.isDrawing()) {
                if (Gdx.input.isButtonPressed(com.badlogic.gdx.Input.Buttons.LEFT)) {
                    Vector3 cur = camera.unproject(new Vector3(Gdx.input.getX(), Gdx.input.getY(), 0));
                    curvedFormation.tickCurrentCursor(cur.x, cur.y);
                }
            }

            updateTerrainUnderCursor();
            unitManager.update(delta);
            moveMarker.update(delta);
            combatManager.update(delta);
            combatManager.updatePopups(delta);
            selectionPanel.update(delta, unitManager.getSelectedUnits());

            // ── Оновлення видимості (до рендеру) ──────────────────────────
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

        // ── Рендер карти та юнітів ─────────────────────────────────────────
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

        // ── Туман війни (поверх юнітів, до HUD) ───────────────────────────
        if (!paused) {
            shapes.setProjectionMatrix(camera.combined);
            fogRenderer.render(shapes, camera, unitManager.getAllUnits());
        }

        // ── Курсорна підказка лісу ─────────────────────────────────────────
        if (!paused && currentTerrain == TerrainType.FOREST) {
            uiBatch.begin();
            forestTooltip.draw(uiBatch, cursorScreenX, cursorScreenY);
            uiBatch.end();
        }

        if (!paused) {
            selectionPanel.draw(panelBatch, shapes,
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        }

        hudStage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        hudStage.act(delta);
        hudStage.draw();

        if (paused) {
            pauseStage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
            pauseStage.act(delta);
            pauseStage.draw();
        }
    }

    public boolean isFormationModeActive() { return formationModeActive; }

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

    @Override
    public void resize(int width, int height) {
        gameViewport.update(width, height, false);
        hudStage.getViewport().update(width, height, true);
        pauseStage.getViewport().update(width, height, true);
    }

    @Override public void hide()   { game.setScreenInputProcessor(new InputAdapter()); }
    @Override public void pause()  {}
    @Override public void resume() {}

    @Override
    public void dispose() {
        if (batch != null)          batch.dispose();
        if (uiBatch != null)        uiBatch.dispose();
        if (panelBatch != null)     panelBatch.dispose();
        if (shapes != null)         shapes.dispose();
        if (mapTexture != null)     mapTexture.dispose();
        if (pauseStage != null)     pauseStage.dispose();
        if (hudStage != null)       hudStage.dispose();
        if (terrainMask != null)    terrainMask.dispose();
        if (forestTooltip != null)  forestTooltip.dispose();
        if (unitRenderer != null)   unitRenderer.dispose();
        if (combatManager != null)  combatManager.dispose();
        if (selectionPanel != null) selectionPanel.dispose();
        if (curvedFormation != null) curvedFormation.dispose();
        UIFactory.disposeAll();
    }

    private boolean clickOnPanel(int screenX, int screenY) {
        int screenH = Gdx.graphics.getHeight();
        float yFromBottom = screenH - screenY;
        return selectionPanel.containsScreenPoint(screenX, yFromBottom);
    }

    private void updateTerrainUnderCursor() {
        cursorScreenX = Gdx.input.getX();
        cursorScreenY = Gdx.input.getY();
        Vector3 world = camera.unproject(new Vector3(cursorScreenX, cursorScreenY, 0));
        currentTerrain = terrainMask.getTerrainAt(world.x, world.y);
    }

    private String buildMaskPath(String explicitMask, String texturePath) {
        if (explicitMask != null) return explicitMask;
        if (texturePath  == null) return null;
        int dot = texturePath.lastIndexOf('.');
        if (dot < 0) return texturePath + "_mask";
        return texturePath.substring(0, dot) + "_mask" + texturePath.substring(dot);
    }

    private void handleCameraMovement(float delta) {
        float speed = CAM_SPEED * camera.zoom;
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP))    camera.position.y += speed * delta;
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN))  camera.position.y -= speed * delta;
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT))  camera.position.x -= speed * delta;
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT)) camera.position.x += speed * delta;
        clampCamera();
    }

    private void clampCamera() {
        camera.position.x = MathUtils.clamp(camera.position.x, 0, mapWidth);
        camera.position.y = MathUtils.clamp(camera.position.y, 0, mapHeight);
    }

    private void drawSelectionRect() {
        float x = Math.min(selStartX, selCurX);
        float y = Math.min(selStartY, selCurY);
        float w = Math.abs(selCurX - selStartX);
        float h = Math.abs(selCurY - selStartY);
        if (w < 1f || h < 1f) return;

        shapes.setProjectionMatrix(camera.combined);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 0.85f, 0f, 0.35f);
        shapes.rect(x, y, w, h);
        shapes.end();
        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1f, 0.85f, 0f, 1f);
        shapes.rect(x, y, w, h);
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    private InputAdapter buildKeyInput() {
        return new InputAdapter() {
            @Override public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE) { togglePause(); return true; }
                return false;
            }
        };
    }

    private InputAdapter buildGameInput() {
        return new InputAdapter() {

            private boolean awaitingDrawStart = false;

            @Override public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE) {
                    if (curvedFormation.isDrawing()) {
                        curvedFormation.cancel();
                        selectionPanel.setFormationActive(false);
                        awaitingDrawStart = false;
                        return true;
                    }
                    togglePause();
                    return true;
                }
                return false;
            }

            @Override public boolean scrolled(float amountX, float amountY) {
                if (paused) return false;
                camera.zoom = MathUtils.clamp(camera.zoom + amountY * ZOOM_STEP, ZOOM_MIN, ZOOM_MAX);
                camera.update();
                return true;
            }

            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                if (paused) return false;

                if (button == Input.Buttons.LEFT && clickOnPanel(screenX, screenY)) {
                    int screenH = Gdx.graphics.getHeight();
                    boolean wasActive = selectionPanel.isFormationActive();
                    selectionPanel.handleClick(screenX, screenH - screenY);
                    boolean nowActive = selectionPanel.isFormationActive();
                    if (!wasActive && nowActive) { awaitingDrawStart = true; curvedFormation.cancel(); }
                    if (wasActive && !nowActive)  { awaitingDrawStart = false; curvedFormation.cancel(); }
                    return true;
                }

                if (button == Input.Buttons.LEFT) {
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    if (awaitingDrawStart && selectionPanel.isFormationActive()) {
                        awaitingDrawStart = false;
                        curvedFormation.startDraw(world.x, world.y);
                        return true;
                    }
                    if (curvedFormation.isDrawing()) return true;

                    if (unitManager == null) return false;
                    clickConsumedByUnit = unitManager.trySelectAtPoint(world.x, world.y);
                    if (!clickConsumedByUnit) startSelecting(world.x, world.y);
                    return true;
                }

                if (button == Input.Buttons.RIGHT) {
                    if (curvedFormation.isDrawing()) {
                        curvedFormation.cancel();
                        selectionPanel.setFormationActive(false);
                        awaitingDrawStart = false;
                        return true;
                    }
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    formationDrag.onRmbDown(world.x, world.y);
                    return true;
                }
                return false;
            }

            @Override
            public boolean touchDragged(int screenX, int screenY, int pointer) {
                if (curvedFormation.isDrawing()) {
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    curvedFormation.addPoint(world.x, world.y);
                    return true;
                }
                if (selecting) {
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    updateSelection(world.x, world.y);
                    return true;
                }
                return false;
            }

            @Override
            public boolean touchUp(int screenX, int screenY, int pointer, int button) {
                if (paused) return false;

                if (button == Input.Buttons.LEFT) {
                    if (curvedFormation.isDrawing()) {
                        curvedFormation.finishAndApply(unitManager, mapWidth, mapHeight);
                        selectionPanel.setFormationActive(false);
                        awaitingDrawStart = false;
                        return true;
                    }
                    if (!selecting) return false;
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    selCurX = world.x; selCurY = world.y;
                    float rx = Math.min(selStartX, selCurX), ry = Math.min(selStartY, selCurY);
                    float rw = Math.abs(selCurX - selStartX), rh = Math.abs(selCurY - selStartY);
                    if (rw > 6f && rh > 6f) unitManager.selectInRect(rx, ry, rw, rh, true);
                    else if (!clickConsumedByUnit) unitManager.clearSelection();
                    selecting = false;
                    return true;
                }

                if (button == Input.Buttons.RIGHT) {
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    boolean wasFormation = formationDrag.onRmbUp();
                    if (unitManager.hasSelection()) {
                        if (wasFormation) {
                            combatManager.cancelAttackOrders(unitManager.getSelectedUnits());
                            unitManager.moveSelectedToLine(
                                formationDrag.getStartX(), formationDrag.getStartY(),
                                formationDrag.getEndX(),   formationDrag.getEndY(),
                                mapWidth, mapHeight);
                            float mx = (formationDrag.getStartX() + formationDrag.getEndX()) / 2f;
                            float my = (formationDrag.getStartY() + formationDrag.getEndY()) / 2f;
                            moveMarker.show(mx, my, MoveMarker.MarkerType.MOVE);
                        } else {
                            Unit enemy = combatManager.tryGetEnemyAtPoint(world.x, world.y);
                            if (enemy != null) {
                                combatManager.orderAttack(enemy);
                                moveMarker.show(enemy.position.x, enemy.position.y, MoveMarker.MarkerType.ATTACK);
                            } else {
                                combatManager.cancelAttackOrders(unitManager.getSelectedUnits());
                                unitManager.moveSelectedTo(world.x, world.y, mapWidth, mapHeight);
                                moveMarker.show(world.x, world.y, MoveMarker.MarkerType.MOVE);
                            }
                        }
                    }
                    return true;
                }
                return false;
            }
        };
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

    public void startSelecting(float worldX, float worldY) {
        this.selecting = true; this.clickConsumedByUnit = false;
        this.selStartX = worldX; this.selCurX = worldX;
        this.selStartY = worldY; this.selCurY = worldY;
    }

    public void updateSelection(float worldX, float worldY) {
        this.selCurX = worldX; this.selCurY = worldY;
    }

    public void finishSelection() {
        if (!selecting) return;
        float rx = Math.min(selStartX, selCurX), ry = Math.min(selStartY, selCurY);
        float rw = Math.abs(selCurX - selStartX), rh = Math.abs(selCurY - selStartY);
        if (rw > 6f && rh > 6f) unitManager.selectInRect(rx, ry, rw, rh, true);
        selecting = false;
    }

    public void setClickConsumedByUnit(boolean val) { this.clickConsumedByUnit = val; }
    public void setZoom(float zoom) { camera.zoom = MathUtils.clamp(zoom, ZOOM_MIN, ZOOM_MAX); }

    public boolean isPaused()       { return paused; }
    public boolean isSelecting()    { return selecting; }
    public OrthographicCamera getCamera() { return camera; }
    public UnitManager getUnitManager()   { return unitManager; }
    public MoveMarker getMoveMarker()     { return moveMarker; }
    public float getMapWidth()            { return mapWidth; }
    public float getMapHeight()           { return mapHeight; }
    public FormationDragHandler getFormationDrag()       { return formationDrag; }
    public CombatManager getCombatManager()              { return combatManager; }
    public CurvedFormationCommand getCurvedFormation()   { return curvedFormation; }
}
