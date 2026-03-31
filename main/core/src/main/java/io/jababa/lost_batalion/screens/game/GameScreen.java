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
import io.jababa.lost_batalion.mobile.GameInputHandler;
import io.jababa.lost_batalion.mobile.MobileTouchHandler;
import io.jababa.lost_batalion.screens.renderer.UnitRenderer;
import io.jababa.lost_batalion.screens.scenario.ScenarioCard;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.units.UnitManager;

public class GameScreen implements Screen {

    private static final float CAM_SPEED = 400f;
    private static final float ZOOM_MIN = 0.3f;
    private static final float ZOOM_MAX = 2.0f;
    private static final float ZOOM_STEP = 0.1f;

    private boolean selecting;
    private float   selStartX, selStartY, selCurX, selCurY;
    private boolean clickConsumedByUnit = false;

    private boolean      paused = false;
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
        this.game = game;
        this.scenario = scenario;
    }

    @Override
    public void show() {
        batch = new SpriteBatch();
        uiBatch = new SpriteBatch();
        shapes = new ShapeRenderer();

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
        terrainMask = new TerrainMaskManager(maskPath);
        forestTooltip = new ForestTooltip("ui/forest_tooltip.png");

        unitManager = new UnitManager();
        unitRenderer = new UnitRenderer();
        moveMarker = new MoveMarker();

        unitManager.spawnPlayerSquad(mapWidth / 2f, mapHeight / 2f);

        UIFactory.disposeAll();

        pauseStage = new Stage(new ScreenViewport(), batch);
        buildPauseOverlay();

        hudStage = new Stage(new ScreenViewport(), batch);
        buildHud();

        InputMultiplexer mux = new InputMultiplexer();

        mux.addProcessor(pauseStage);

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
        TextButton burgerBtn = new TextButton("\u2630", UIFactory.createSmallButtonStyle());
        burgerBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                togglePause();
            }
        });

        Table root = new Table();
        root.setFillParent(true);
        root.top().left().pad(12f);
        root.add(burgerBtn).size(54f, 48f);

        hudStage.addActor(root);
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (!paused) {
            handleCameraMovement(delta);
            updateTerrainUnderCursor();
            unitManager.update(delta);
            moveMarker.update(delta);
        }
        camera.update();

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        if (mapTexture != null) batch.draw(mapTexture, 0, 0);
        unitRenderer.drawSprites(batch, unitManager.getAllUnits());
        batch.end();

        unitRenderer.setProjectionMatrix(camera.combined);
        unitRenderer.drawOverlays(unitManager.getAllUnits());

        if (moveMarker.isActive()) {
            shapes.setProjectionMatrix(camera.combined);
            moveMarker.draw(shapes);
        }

        if (selecting && !paused) {
            drawSelectionRect();
        }

        if (!paused && currentTerrain == TerrainType.FOREST) {
            uiBatch.begin();
            forestTooltip.draw(uiBatch, cursorScreenX, cursorScreenY);
            uiBatch.end();
        }

        hudStage.getViewport().update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        hudStage.act(delta);
        hudStage.draw();

        if (paused) {
            pauseStage.getViewport().update(
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
            pauseStage.act(delta);
            pauseStage.draw();
        }
    }

    @Override
    public void resize(int width, int height) {
        gameViewport.update(width, height, false);
        hudStage.getViewport().update(width, height, true);
        pauseStage.getViewport().update(width, height, true);
    }

    @Override public void hide() { game.setScreenInputProcessor(new InputAdapter()); }
    @Override public void pause() {}
    @Override public void resume() {}

    @Override
    public void dispose() {
        if (batch != null) batch.dispose();
        if (uiBatch != null) uiBatch.dispose();
        if (shapes != null) shapes.dispose();
        if (mapTexture != null) mapTexture.dispose();
        if (pauseStage != null) pauseStage.dispose();
        if (hudStage != null) hudStage.dispose();
        if (terrainMask != null) terrainMask.dispose();
        if (forestTooltip != null) forestTooltip.dispose();
        if (unitRenderer != null) unitRenderer.dispose();
        UIFactory.disposeAll();
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
        if (Gdx.input.isKeyPressed(Input.Keys.W) || Gdx.input.isKeyPressed(Input.Keys.UP))
            camera.position.y += speed * delta;
        if (Gdx.input.isKeyPressed(Input.Keys.S) || Gdx.input.isKeyPressed(Input.Keys.DOWN))
            camera.position.y -= speed * delta;
        if (Gdx.input.isKeyPressed(Input.Keys.A) || Gdx.input.isKeyPressed(Input.Keys.LEFT))
            camera.position.x -= speed * delta;
        if (Gdx.input.isKeyPressed(Input.Keys.D) || Gdx.input.isKeyPressed(Input.Keys.RIGHT))
            camera.position.x += speed * delta;
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
            @Override
            public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE) { togglePause(); return true; }
                return false;
            }
        };
    }

    private InputAdapter buildGameInput() {
        return new InputAdapter() {
            @Override
            public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE) { togglePause(); return true; }
                return false;
            }

            @Override
            public boolean scrolled(float amountX, float amountY) {
                if (paused) return false;
                camera.zoom = MathUtils.clamp(
                    camera.zoom + amountY * ZOOM_STEP, ZOOM_MIN, ZOOM_MAX);
                camera.update();
                return true;
            }

            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, int button) {
                if (paused) return false;
                if (button == Input.Buttons.LEFT) {
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    if (unitManager == null) return false;
                    clickConsumedByUnit = unitManager.trySelectAtPoint(world.x, world.y);
                    if (!clickConsumedByUnit) startSelecting(world.x, world.y);
                    return true;
                }
                return false;
            }

            @Override
            public boolean touchDragged(int screenX, int screenY, int pointer) {
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
                    if (!selecting) return false;
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    selCurX = world.x; selCurY = world.y;
                    float rx = Math.min(selStartX, selCurX);
                    float ry = Math.min(selStartY, selCurY);
                    float rw = Math.abs(selCurX - selStartX);
                    float rh = Math.abs(selCurY - selStartY);
                    if (rw > 6f && rh > 6f) {
                        unitManager.selectInRect(rx, ry, rw, rh, true);
                    } else if (!clickConsumedByUnit) {
                        unitManager.clearSelection();
                    }
                    selecting = false;
                    return true;
                }
                if (button == Input.Buttons.RIGHT && unitManager.hasSelection()) {
                    Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                    unitManager.moveSelectedTo(world.x, world.y, mapWidth, mapHeight);
                    moveMarker.show(world.x, world.y);
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
            @Override public void onResume() { paused = false; pauseStage.clear(); }
            @Override public void onReturnToLobby() {
                game.setScreen(new io.jababa.lost_batalion.screens.scenario.ScenarioScreen(game));
            }
            @Override public void onSettings() {}
            @Override public void onExit() { Gdx.app.exit(); }
        });
    }

    public void startSelecting(float worldX, float worldY) {
        this.selecting = true;
        this.clickConsumedByUnit = false;
        this.selStartX = worldX; this.selCurX = worldX;
        this.selStartY = worldY; this.selCurY = worldY;
    }

    public void updateSelection(float worldX, float worldY) {
        this.selCurX = worldX;
        this.selCurY = worldY;
    }

    public void finishSelection() {
        if (!selecting) return;
        float rx = Math.min(selStartX, selCurX);
        float ry = Math.min(selStartY, selCurY);
        float rw = Math.abs(selCurX - selStartX);
        float rh = Math.abs(selCurY - selStartY);
        if (rw > 6f && rh > 6f) {
            unitManager.selectInRect(rx, ry, rw, rh, true);
        }
        selecting = false;
    }

    public void setClickConsumedByUnit(boolean val) { this.clickConsumedByUnit = val; }
    public void setZoom(float zoom) {
        camera.zoom = MathUtils.clamp(zoom, ZOOM_MIN, ZOOM_MAX);
    }

    public boolean isPaused() { return paused; }
    public boolean isSelecting() { return selecting; }
    public OrthographicCamera getCamera() { return camera; }
    public UnitManager getUnitManager() { return unitManager; }
    public MoveMarker getMoveMarker() { return moveMarker; }
    public float getMapWidth() { return mapWidth; }
    public float getMapHeight() { return mapHeight; }
}
