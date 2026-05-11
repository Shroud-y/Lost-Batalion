package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.units.Unit;

/**
 * Fog-of-war renderer using a stencil buffer.
 *
 * Pass 1 — write sight circles into the stencil buffer (no colour output).
 * Pass 2 — draw the dark fog rectangle only where stencil == 0 (outside sight).
 * Pass 3 — draw a soft fade ring just inside the sight boundary (stencil == 1).
 *
 * Terrain modifiers mirror VisibilitySystem so the visual circle matches detection.
 */
public class FogOfWarRenderer {

    private static final float FOG_ALPHA = 0f;
    private static final float FOG_R     = 0.04f;
    private static final float FOG_G     = 0.04f;
    private static final float FOG_B     = 0.10f;

    private static final int   SOFT_STEPS      = 8;
    private static final float SOFT_ZONE        = 40f;
    private static final int   CIRCLE_SEGMENTS  = 48;

    // Must stay in sync with VisibilitySystem
    private static final float FOREST_SIGHT_PENALTY = 0.55f;

    private final float mapWidth;
    private final float mapHeight;
    private final TerrainMaskManager terrain;

    public FogOfWarRenderer(float mapWidth, float mapHeight, TerrainMaskManager terrain) {
        this.mapWidth  = mapWidth;
        this.mapHeight = mapHeight;
        this.terrain   = terrain;
    }

    /**
     * Render fog. Call AFTER map and units, BEFORE HUD.
     * shapes must NOT be in a begin() state.
     */
    public void render(ShapeRenderer shapes, OrthographicCamera camera, Array<Unit> allUnits) {
        if (FOG_ALPHA <= 0f) return;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // ── Pass 1: stamp sight circles into stencil (no colour write) ────────
        Gdx.gl.glEnable(GL20.GL_STENCIL_TEST);
        Gdx.gl.glClear(GL20.GL_STENCIL_BUFFER_BIT);        // clear stencil to 0

        Gdx.gl.glColorMask(false, false, false, false);     // suppress colour output
        Gdx.gl.glStencilFunc(GL20.GL_ALWAYS, 1, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_REPLACE);
        Gdx.gl.glStencilMask(0xFF);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, 1f);
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u.team != Team.PLAYER || !u.alive) continue;
            float sight = computeEffectiveSight(u);
            shapes.circle(u.position.x, u.position.y, sight, CIRCLE_SEGMENTS);
        }
        shapes.end();

        // ── Pass 2: draw fog only where stencil == 0 (outside all sight) ─────
        Gdx.gl.glColorMask(true, true, true, true);
        Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 0, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP);
        Gdx.gl.glStencilMask(0x00);                        // don't modify stencil

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(FOG_R, FOG_G, FOG_B, FOG_ALPHA);
        shapes.rect(0, 0, mapWidth, mapHeight);
        shapes.end();

        // ── Pass 3: soft fade rings inside sight boundary (stencil == 1) ─────
        Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 1, 0xFF);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u.team != Team.PLAYER || !u.alive) continue;
            drawSoftEdge(shapes, u);
        }
        shapes.end();

        // ── Cleanup ───────────────────────────────────────────────────────────
        Gdx.gl.glStencilMask(0xFF);
        Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    /**
     * Renders a cursor-centred line-of-sight overlay when Alt is held.
     *
     * Casts 720 rays from the cursor. Each ray marches in 6-px steps and stops
     * the first time it enters a forest pixel or leaves the map. The resulting
     * "visibility fan" is stamped into the stencil buffer; then:
     *   - blocked areas (stencil == 0) receive a dark overlay
     *   - visible areas (stencil == 1) receive a faint green tint
     *
     * Requires an 8-bit stencil buffer (set in Lwjgl3Launcher).
     */
    public void renderCursorSightOverlay(ShapeRenderer shapes, float cx, float cy) {
        final int   RAY_COUNT = 720;
        final float STEP      = 6f;
        final float MAX_DIST  = (float) Math.sqrt(mapWidth * mapWidth + mapHeight * mapHeight) + 1f;

        float[] epx = new float[RAY_COUNT];
        float[] epy = new float[RAY_COUNT];

        for (int i = 0; i < RAY_COUNT; i++) {
            double angle = 2.0 * Math.PI * i / RAY_COUNT;
            float  dx    = (float) Math.cos(angle);
            float  dy    = (float) Math.sin(angle);

            epx[i] = cx;
            epy[i] = cy;

            for (float d = STEP; d <= MAX_DIST; d += STEP) {
                float wx = cx + dx * d;
                float wy = cy + dy * d;
                if (wx < 0 || wx >= mapWidth || wy < 0 || wy >= mapHeight) break;
                if (terrain != null && terrain.isForestAt(wx, wy)) break;
                epx[i] = wx;
                epy[i] = wy;
            }
        }

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Pass 1 – write visible fan into stencil (no colour output)
        Gdx.gl.glEnable(GL20.GL_STENCIL_TEST);
        Gdx.gl.glClear(GL20.GL_STENCIL_BUFFER_BIT);
        Gdx.gl.glColorMask(false, false, false, false);
        Gdx.gl.glStencilFunc(GL20.GL_ALWAYS, 1, 0xFF);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_REPLACE);
        Gdx.gl.glStencilMask(0xFF);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 1f, 1f, 1f);
        for (int i = 0; i < RAY_COUNT; i++) {
            int j = (i + 1) % RAY_COUNT;
            shapes.triangle(cx, cy, epx[i], epy[i], epx[j], epy[j]);
        }
        shapes.end();

        Gdx.gl.glColorMask(true, true, true, true);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP);
        Gdx.gl.glStencilMask(0x00);

        // Pass 2 – dark shadow over blocked (stencil == 0) areas
        Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 0, 0xFF);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.68f);
        shapes.rect(0, 0, mapWidth, mapHeight);
        shapes.end();

        // Pass 3 – faint green tint over visible (stencil == 1) areas
        Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 1, 0xFF);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.15f, 0.85f, 0.25f, 0.12f);
        shapes.rect(0, 0, mapWidth, mapHeight);
        shapes.end();

        // Cleanup stencil state
        Gdx.gl.glStencilMask(0xFF);
        Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);

        // Cursor marker
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 0.9f, 0.1f, 1f);
        shapes.circle(cx, cy, 5f, 16);
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Fading rings from the inner clear zone up to the sight boundary,
     * so fog blends smoothly into visibility rather than hard-clipping.
     */
    private void drawSoftEdge(ShapeRenderer shapes, Unit observer) {
        float sight      = computeEffectiveSight(observer);
        float innerBound = Math.max(0f, sight - SOFT_ZONE);
        float cx         = observer.position.x;
        float cy         = observer.position.y;

        for (int s = 0; s < SOFT_STEPS; s++) {
            float t     = (float) s       / SOFT_STEPS;
            float tNext = (float)(s + 1)  / SOFT_STEPS;
            float r     = innerBound + (sight - innerBound) * t;
            float rNext = innerBound + (sight - innerBound) * tNext;
            float alpha = FOG_ALPHA * t * t;           // quadratic ease-in
            drawRing(shapes, cx, cy, r, rNext, alpha);
        }
    }

    private void drawRing(ShapeRenderer shapes, float cx, float cy,
                          float innerR, float outerR, float alpha) {
        shapes.setColor(FOG_R, FOG_G, FOG_B, alpha);
        float step = (float)(2.0 * Math.PI / CIRCLE_SEGMENTS);
        for (int seg = 0; seg < CIRCLE_SEGMENTS; seg++) {
            float a0 = seg * step, a1 = (seg + 1) * step;
            float cos0 = (float)Math.cos(a0), sin0 = (float)Math.sin(a0);
            float cos1 = (float)Math.cos(a1), sin1 = (float)Math.sin(a1);

            float x0i = cx + innerR * cos0,  y0i = cy + innerR * sin0;
            float x1i = cx + innerR * cos1,  y1i = cy + innerR * sin1;
            float x0o = cx + outerR * cos0,  y0o = cy + outerR * sin0;
            float x1o = cx + outerR * cos1,  y1o = cy + outerR * sin1;

            shapes.triangle(x0i, y0i, x0o, y0o, x1i, y1i);
            shapes.triangle(x1i, y1i, x0o, y0o, x1o, y1o);
        }
    }

    /**
     * Observer's effective sight radius including terrain modifiers.
     * Mirrors VisibilitySystem.sightMod so the visual circle matches detection.
     */
    private float computeEffectiveSight(Unit observer) {
        if (terrain == null) return observer.sightRange;

        float x = observer.position.x;
        float y = observer.position.y;

        float mod = terrain.isForestAt(x, y) ? FOREST_SIGHT_PENALTY : 1f;

        switch (terrain.getElevationAt(x, y)) {
            case HIGHLANDS:     mod *= 1.30f; break;
            case PRE_HIGHLANDS: mod *= 1.15f; break;
            case PRE_LOWLANDS:  mod *= 0.92f; break;
            case LOWLANDS:      mod *= 0.80f; break;
            default: break;
        }

        return observer.sightRange * mod;
    }
}
