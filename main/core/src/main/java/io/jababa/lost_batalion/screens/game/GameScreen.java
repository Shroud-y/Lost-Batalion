package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.Screen;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.viewport.ExtendViewport;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.screens.scenario.ScenarioCard;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.ui.UIFactory;

public class GameScreen implements Screen {

    private static final float CAM_SPEED = 400f;
    private static final float ZOOM_MIN = 0.3f;
    private static final float ZOOM_MAX = 2.0f;
    private static final float ZOOM_STEP = 0.1f;

    private boolean selecting;
    private float selStartX, selStartY, selCurX, selCurY;

    private boolean paused = false;
    private PauseOverlay pauseOverlay;
    private Stage pauseStage;
    private TerrainMaskManager terrainMask;
    private ForestTooltip forestTooltip;
    private TerrainType currentTerrain = TerrainType.NONE;

    private int cursorScreenX, cursorScreenY;

    private final LostBatalion game;
    private final ScenarioCard scenario;

    private OrthographicCamera camera;
    private SpriteBatch batch;

    private SpriteBatch uiBatch;
    private ShapeRenderer shapes;
    private Texture mapTexture;
    private ExtendViewport gameViewport;

    public GameScreen(LostBatalion game, ScenarioCard scenario) {
        this.game     = game;
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

        camera = new OrthographicCamera();
        gameViewport = new ExtendViewport(900, 580, camera);
        gameViewport.update(Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), true);
        if (mapTexture != null) {
            camera.position.set(mapTexture.getWidth() / 2f, mapTexture.getHeight() / 2f, 0);
        }
        camera.update();

        String maskPath = buildMaskPath(scenario.maskPath, scenario.texturePath);
        terrainMask   = new TerrainMaskManager(maskPath);
        forestTooltip = new ForestTooltip("ui/forest_tooltip.png");

        pauseStage = new Stage(new ScreenViewport(), batch);
        UIFactory.disposeAll();
        buildPauseOverlay();

        InputMultiplexer mux = new InputMultiplexer();
        mux.addProcessor(pauseStage);
        mux.addProcessor(buildGameInput());
        game.setScreenInputProcessor(mux);
    }

    @Override
    public void render(float delta) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        if (!paused) {
            handleCameraMovement(delta);
            updateTerrainUnderCursor();
        }
        camera.update();

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        if (mapTexture != null) batch.draw(mapTexture, 0, 0);
        batch.end();

        if (selecting && !paused) drawSelectionRect();

        if (!paused && currentTerrain == TerrainType.FOREST) {
            uiBatch.begin();
            forestTooltip.draw(uiBatch, cursorScreenX, cursorScreenY);
            uiBatch.end();
        }

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
        pauseStage.getViewport().update(width, height, true);
    }

    @Override public void hide()   { game.setScreenInputProcessor(new InputAdapter()); }
    @Override public void pause() {}
    @Override public void resume() {}

    @Override
    public void dispose() {
        if (batch != null) batch.dispose();
        if (uiBatch != null) uiBatch.dispose();
        if (shapes != null) shapes.dispose();
        if (mapTexture != null) mapTexture.dispose();
        if (pauseStage != null) pauseStage.dispose();
        if (terrainMask != null) terrainMask.dispose();
        if (forestTooltip != null) forestTooltip.dispose();
        UIFactory.disposeAll();
    }

    private void updateTerrainUnderCursor() {
        cursorScreenX = Gdx.input.getX();
        cursorScreenY = Gdx.input.getY();

        Vector3 world = camera.unproject(
            new Vector3(cursorScreenX, cursorScreenY, 0));
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
        if (mapTexture == null) return;
        camera.position.x = MathUtils.clamp(camera.position.x, 0, mapTexture.getWidth());
        camera.position.y = MathUtils.clamp(camera.position.y, 0, mapTexture.getHeight());
    }

    private void drawSelectionRect() {
        float x = Math.min(selStartX, selCurX);
        float y = Math.min(selStartY, selCurY);
        float w = Math.abs(selCurX - selStartX);
        float h = Math.abs(selCurY - selStartY);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(camera.combined);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 0.85f, 0f, 0.15f);
        shapes.rect(x, y, w, h);
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Line);
        shapes.setColor(1f, 0.85f, 0f, 0.9f);
        shapes.rect(x, y, w, h);
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
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
                if (paused || button != Input.Buttons.LEFT) return false;
                Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                selStartX = selCurX = world.x;
                selStartY = selCurY = world.y;
                selecting = true;
                return true;
            }

            @Override
            public boolean touchDragged(int screenX, int screenY, int pointer) {
                if (!selecting) return false;
                Vector3 world = camera.unproject(new Vector3(screenX, screenY, 0));
                selCurX = world.x;
                selCurY = world.y;
                return true;
            }

            @Override
            public boolean touchUp(int screenX, int screenY, int pointer, int button) {
                if (button != Input.Buttons.LEFT) return false;
                selecting = false;
                return true;
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
            @Override public void onResume(){ paused = false; pauseStage.clear(); }
            @Override public void onReturnToLobby() {
                game.setScreen(
                    new io.jababa.lost_batalion.screens.scenario.ScenarioScreen(game));
            }
            @Override public void onSettings() {}
            @Override public void onExit(){ Gdx.app.exit(); }
        });
    }
}
