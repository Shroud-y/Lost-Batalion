package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainQuery;
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

    // ── ALT sight overlay colours ─────────────────────────────────────────────
    /** Opaque light brown, drawn along the fan boundary. */
    private static final float EDGE_R = 0.78f, EDGE_G = 0.61f, EDGE_B = 0.42f;
    /** Half-transparent dark brown, covering the BLOCKED ground outside the fan. */
    private static final float FILL_R = 0.23f, FILL_G = 0.14f, FILL_B = 0.09f;
    private static final float FILL_ALPHA = 0.5f;
    /**
     * Boundary thickness in SCREEN pixels. Converted to world units against the
     * current zoom before drawing, so the outline keeps the same weight whether
     * the camera is pulled back over the whole map or pushed into a treeline.
     */
    private static final float EDGE_WIDTH_PX = 2f;

    // ── ALT sight overlay tuning ──────────────────────────────────────────────
    /**
     * Rays cast at uniform angles before refinement. Deliberately low: open
     * ground needs almost no angular resolution, and everything interesting
     * happens at silhouettes, which SUBDIV_* below resolve far more finely than
     * a uniform sweep ever could at the same cost.
     */
    private static final int   OVERLAY_BASE_RAYS = 360;
    /**
     * Endpoint distance gap (world units) between neighbouring rays that marks a
     * silhouette worth resolving. Below this the boundary is treated as smooth.
     */
    private static final float SUBDIV_THRESHOLD   = 5f;
    /**
     * Bisection depth at a silhouette. 7 levels split one base step 128 ways, so
     * edges resolve as if 360 × 128 = 46080 rays had been cast — but only there.
     */
    private static final int   SUBDIV_MAX_DEPTH   = 7;

    /*
     * These three were measured against a dense reference sweep on the Zhovti Vody
     * masks, not guessed. Error of the RENDERED boundary (the straight segments
     * between endpoints — i.e. what is actually visible on screen), averaged over
     * two cursor positions at ELEVATION_BLOCK_MARGIN = 0.5:
     *
     *   base  thresh  depth |  points   mean err   p99 err
     *   ------------------- + -----------------------------
     *    360     6      5   |    825     1.09 px    4.03 px   ← was
     *    360     5      7   |   1042     0.94 px    2.01 px   ← current
     *    360     4      7   |   1187     0.92 px    1.79 px
     *    360     3      7   |   1463     0.85 px    1.55 px
     *
     * Depth used to plateau at 5 because hit distances were quantised to a
     * 1-unit sampling lattice — that quantisation WAS the error floor, so finer
     * angles had nothing to resolve. Now that walkPixels returns the exact pixel
     * edge, depth pays again: the p99 (the worst visible edge, which is what the
     * eye actually catches) halves for +26% points, while the mean barely moves.
     * Past threshold 5 the trade goes bad — hundreds more rays for hundredths of
     * a pixel, and 1 world unit = 1 mask pixel is the data floor regardless.
     *
     * Raising the base ray count is still the wrong lever — it costs everywhere
     * and helps only where the terrain is flat.
     *
     * Re-measure these if ELEVATION_BLOCK_MARGIN or LOS_CREST_MIN_RUN changes:
     * both change how many silhouette edges share the subdivision budget.
     */
    /** Hard ceiling on fan vertices, so pathological terrain cannot stall a frame. */
    private static final int   MAX_FAN_POINTS     = 4096;
    /** Cursor movement (world units) that invalidates the cached fan. */
    private static final float OVERLAY_CACHE_EPS  = 2f;

    private final float mapWidth;
    private final float mapHeight;
    /**
     * Ray marching lives in {@link SightCaster}, shared with
     * {@link SightRingRenderer} so both overlays agree with each other and with
     * {@link VisibilitySystem}.
     */
    private final SightCaster caster;

    /**
     * Чиїми очима малюється туман. Раніше було жорстко Team.PLAYER — в одиночній
     * грі спостерігач один. У матчі 1v1 гість дивиться зі свого боку, тож
     * сторона задається ззовні.
     */
    private Team viewer = Team.PLAYER;

    // Cached ALT-overlay fan, as a boundary polygon in increasing-angle order.
    // The masks are immutable, so the fan depends only on the cursor position —
    // recomputed only when the cursor actually moves. Size varies with how much
    // silhouette detail the terrain around the cursor needs.
    private final FloatArray fanX = new FloatArray(MAX_FAN_POINTS);
    private final FloatArray fanY = new FloatArray(MAX_FAN_POINTS);
    private boolean fanValid = false;
    private float   fanCx    = 0f;
    private float   fanCy    = 0f;

    // Scratch state for the recursive build, so subdivide() need not thread the
    // observer through every call.
    private float buildCx, buildCy, buildHCursor;

    public FogOfWarRenderer(float mapWidth, float mapHeight, TerrainQuery terrain) {
        this.mapWidth  = mapWidth;
        this.mapHeight = mapHeight;
        this.caster    = new SightCaster(mapWidth, mapHeight, terrain);
    }

    /** Сторона, з чиєї позиції малюється туман. */
    public void setViewer(Team viewer) { this.viewer = viewer; }

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
            if (u.team != viewer || !u.alive) continue;
            float sight = computeEffectiveSight(u);
            shapes.circle(u.worldX(), u.worldY(), sight, CIRCLE_SEGMENTS);
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
            if (u.team != viewer || !u.alive) continue;
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
     * Casts OVERLAY_BASE_RAYS rays from the cursor, refined at silhouettes. Each
     * ray walks the mask pixel by pixel and stops the first time it enters a
     * forest pixel, is occluded by a hill crest, or leaves the map — rays are
     * deliberately not range-limited, they must reach across the whole map. The
     * resulting "visibility fan" is stamped into the stencil buffer; then:
     *   - blocked areas (stencil == 0) receive a half-transparent dark brown wash
     *   - visible areas (stencil == 1) are left untouched — they must look exactly
     *     as they do without the overlay
     *   - the fan boundary itself is traced in opaque light brown
     *
     * Elevation occlusion mirrors VisibilitySystem: a point is hidden when the
     * straight sight line to it passes UNDER a crest already met on the ray —
     * carried here as a slope comparison, see raySlope. High ground still sees
     * across and down freely; a hollow, on the other hand, is now closed in by
     * its own rim, exactly as detection computes it.
     *
     * The fan is cached and only recomputed when the cursor moves more than
     * OVERLAY_CACHE_EPS — holding ALT with a still cursor costs nothing.
     *
     * Requires an 8-bit stencil buffer (set in Lwjgl3Launcher).
     */
    public void renderCursorSightOverlay(ShapeRenderer shapes, OrthographicCamera camera,
                                         float cx, float cy) {
        updateSightFan(cx, cy);

        final float[] epx = fanX.items;
        final float[] epy = fanY.items;
        final int pointCount = fanX.size;
        if (pointCount < 3) return;

        // Draw from the cached fan's origin, not the live cursor: while the
        // cursor is inside OVERLAY_CACHE_EPS the endpoints belong to fanCx/fanCy,
        // and mixing the two would skew every triangle.
        final float ox = fanCx;
        final float oy = fanCy;

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
        for (int i = 0; i < pointCount; i++) {
            int j = (i + 1) % pointCount;
            shapes.triangle(ox, oy, epx[i], epy[i], epx[j], epy[j]);
        }
        shapes.end();

        Gdx.gl.glColorMask(true, true, true, true);
        Gdx.gl.glStencilOp(GL20.GL_KEEP, GL20.GL_KEEP, GL20.GL_KEEP);
        Gdx.gl.glStencilMask(0x00);

        // Pass 2 – half-transparent dark brown over blocked (stencil == 0) areas.
        // Visible ground (stencil == 1) is deliberately left alone: the overlay
        // marks what is HIDDEN, so terrain the cursor can see must read exactly as
        // it does with ALT released.
        Gdx.gl.glStencilFunc(GL20.GL_EQUAL, 0, 0xFF);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(FILL_R, FILL_G, FILL_B, FILL_ALPHA);
        shapes.rect(0, 0, mapWidth, mapHeight);
        shapes.end();

        // Cleanup stencil state
        Gdx.gl.glStencilMask(0xFF);
        Gdx.gl.glDisable(GL20.GL_STENCIL_TEST);

        // Pass 4 – opaque light brown along the fan boundary.
        // Drawn with the stencil already off: the line straddles the boundary, so
        // masking it to either side would shave it in half lengthwise.
        float edgeWidth = EDGE_WIDTH_PX * worldPerPixel(camera);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(EDGE_R, EDGE_G, EDGE_B, 1f);
        for (int i = 0; i < pointCount; i++) {
            int j = (i + 1) % pointCount;
            shapes.rectLine(epx[i], epy[i], epx[j], epy[j], edgeWidth);
            // rectLine is a plain quad, so consecutive segments leave a notch
            // wherever the boundary turns — and at a silhouette it turns by nearly
            // 180°. A dot on the joint fills it.
            shapes.circle(epx[i], epy[i], edgeWidth * 0.5f, 8);
        }
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * World units covered by one screen pixel at the current zoom — the factor
     * that turns a thickness in pixels into one the world-space ShapeRenderer can
     * draw. {@code viewportWidth} is already in world units, so this is just how
     * many of them each pixel of the backbuffer is showing.
     */
    static float worldPerPixel(OrthographicCamera camera) {
        int screenW = Gdx.graphics.getWidth();
        if (camera == null || screenW <= 0) return 1f;
        return camera.viewportWidth * camera.zoom / screenW;
    }

    /**
     * Recomputes the cursor visibility fan into rayEndX/rayEndY.
     *
     * Skipped entirely while the cursor stays within OVERLAY_CACHE_EPS of the
     * position the fan was built for: the masks never change, so the same cursor
     * position always yields the same fan.
     */
    private void updateSightFan(float cx, float cy) {
        if (fanValid) {
            float ddx = cx - fanCx, ddy = cy - fanCy;
            if (ddx * ddx + ddy * ddy <= OVERLAY_CACHE_EPS * OVERLAY_CACHE_EPS) return;
        }

        buildCx      = cx;
        buildCy      = cy;
        buildHCursor = elevationHeightAt(cx, cy);   // observer ground height

        fanX.clear();
        fanY.clear();

        // Uniform sweep, refined wherever two neighbouring rays disagree sharply.
        // The end of one base segment is the start of the next, so each base ray
        // is cast once.
        float dPrev = castAt(0.0);
        float dFirst = dPrev;

        for (int i = 0; i < OVERLAY_BASE_RAYS; i++) {
            double a0 = 2.0 * Math.PI * i       / OVERLAY_BASE_RAYS;
            double a1 = 2.0 * Math.PI * (i + 1) / OVERLAY_BASE_RAYS;

            float d1 = (i == OVERLAY_BASE_RAYS - 1) ? dFirst : castAt(a1);

            emit(a0, dPrev);
            subdivide(a0, dPrev, a1, d1, SUBDIV_MAX_DEPTH);

            dPrev = d1;
        }

        fanCx    = cx;
        fanCy    = cy;
        fanValid = true;
    }

    /**
     * Bisects the angular gap between two rays while their endpoints disagree by
     * more than SUBDIV_THRESHOLD, emitting the interior points in increasing-angle
     * order (endpoints are emitted by the caller).
     *
     * <p>A large gap means the two rays landed on opposite sides of a silhouette —
     * one hit an obstacle, its neighbour slipped past. Left unrefined, the fan
     * spans that gap with a single triangle, producing the light "spike" that
     * stabs past obstacle corners; the artifact is worst for NEAR obstacles,
     * because one angular step there covers a much larger depth discontinuity.
     * Bisecting walks the boundary in until the gap closes.
     */
    private void subdivide(double a0, float d0, double a1, float d1, int depth) {
        if (depth == 0) return;
        if (Math.abs(d0 - d1) <= SUBDIV_THRESHOLD) return;
        if (fanX.size >= MAX_FAN_POINTS) return;

        double am = (a0 + a1) * 0.5;
        float  dm = castAt(am);

        subdivide(a0, d0, am, dm, depth - 1);
        emit(am, dm);
        subdivide(am, dm, a1, d1, depth - 1);
    }

    /**
     * Casts one ray at the given angle from the cursor; returns its free distance.
     *
     * <p>Дальність не обмежується ({@link Float#MAX_VALUE}) — свідомо. Оверлей
     * відповідає на питання «куди звідси взагалі можна дивитись», а не «що бачить
     * конкретний юніт»; радіус огляду показують коло туману й білі кільця
     * {@link SightRingRenderer}, і зводити два різні запитання в одну картинку
     * тут уже пробували й відкинули.
     */
    private float castAt(double angle) {
        return caster.castRay(buildCx, buildCy, buildHCursor,
                              (float) Math.cos(angle), (float) Math.sin(angle),
                              Float.MAX_VALUE);
    }

    /** Appends the fan boundary point at (angle, distance). */
    private void emit(double angle, float d) {
        fanX.add(buildCx + (float) Math.cos(angle) * d);
        fanY.add(buildCy + (float) Math.sin(angle) * d);
    }

    /*
     * castRay / walkPixels / maxSlope / minSlope / distanceToMapExit жили тут і
     * переїхали в SightCaster — без жодної зміни логіки. Причина: кільця огляду
     * (SightRingRenderer) крокують променем так само, і другий примірник того
     * самого коду розійшовся б із цим при першій же правці. Уся історія вимірів
     * (чому пропуск блоків саме такий, чому гребінь мусить триматись) — у
     * javadoc там.
     */

    /**
     * Fading rings from the inner clear zone up to the sight boundary,
     * so fog blends smoothly into visibility rather than hard-clipping.
     */
    private void drawSoftEdge(ShapeRenderer shapes, Unit observer) {
        float sight      = computeEffectiveSight(observer);
        float innerBound = Math.max(0f, sight - SOFT_ZONE);
        float cx         = observer.worldX();
        float cy         = observer.worldY();

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
        return caster.effectiveSight(observer);
    }

    /** Numeric terrain height, plains baseline when there is no terrain data. */
    private float elevationHeightAt(float x, float y) {
        return caster.heightAt(x, y);
    }
}
