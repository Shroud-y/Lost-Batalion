package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.FloatArray;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
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

    // Must stay in sync with VisibilitySystem
    private static final float FOREST_SIGHT_PENALTY   = 0.55f;
    /** @see TerrainQuery#ELEVATION_BLOCK_MARGIN — single source of truth. */
    private static final float ELEVATION_BLOCK_MARGIN = TerrainQuery.ELEVATION_BLOCK_MARGIN;

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
    private static final float SUBDIV_THRESHOLD   = 6f;
    /**
     * Bisection depth at a silhouette. 5 levels split one base step 32 ways, so
     * edges resolve as if 360 × 32 = 11520 rays had been cast — but only there.
     */
    private static final int   SUBDIV_MAX_DEPTH   = 5;

    /*
     * These three were measured against a dense reference sweep on the Zhovti Vody
     * masks, not guessed. Mean error of the RENDERED boundary (the straight
     * segments between endpoints — i.e. what is actually visible on screen),
     * at ELEVATION_BLOCK_MARGIN = 0.5:
     *
     *   base  thresh  depth |  points   mean err   build
     *   ------------------- + ----------------------------
     *    360     6      5   |    719     1.02 px   1.22 ms   ← current
     *    360     6      8   |    902     1.02 px   1.36 ms
     *    360     4      8   |   1083     0.91 px   1.61 ms
     *    360     3      8   |   1266     0.84 px   2.86 ms
     *    480     4      8   |   1220     0.87 px   2.22 ms
     *
     * For reference the previous implementation (720 uniform rays, 6px fixed
     * march) measured 7.94 px mean on the same sweep — so this is ~8x tighter
     * for the same number of points.
     *
     * Depth past 5 buys nothing at this threshold. Tightening the threshold
     * trades a lot of time for fractions of a pixel, and 1 world unit = 1 mask
     * pixel is the resolution floor anyway, so there is nothing real below ~1 px.
     * Raising the base ray count is the wrong lever — it costs everywhere and
     * helps only where the terrain is flat.
     *
     * Re-measure these if ELEVATION_BLOCK_MARGIN changes: more occlusion means
     * more silhouette edges sharing the same subdivision budget.
     */
    /** Hard ceiling on fan vertices, so pathological terrain cannot stall a frame. */
    private static final int   MAX_FAN_POINTS     = 4096;
    /**
     * Sample spacing inside a block that might stop the ray. 1 world unit = 1 mask
     * pixel, so this is the finest the masks can resolve — hit points are exact.
     * Shared with VisibilitySystem so the overlay resolves detail at exactly the
     * same granularity as real detection.
     */
    private static final float FINE_STEP          = TerrainQuery.LOS_FINE_STEP;
    /**
     * Forest within this distance of the cursor does not stop a ray. Without it,
     * placing the cursor in a forest tile would collapse the whole fan to a point.
     * Same constant VisibilitySystem uses to exclude the observer's own tile.
     */
    private static final float ORIGIN_FOREST_SKIP = TerrainQuery.LOS_ORIGIN_FOREST_SKIP;
    /** Cursor movement (world units) that invalidates the cached fan. */
    private static final float OVERLAY_CACHE_EPS  = 2f;

    private final float mapWidth;
    private final float mapHeight;
    private final TerrainQuery terrain;

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
        this.terrain   = terrain;
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
     * Casts OVERLAY_RAY_COUNT rays from the cursor. Each ray marches in
     * OVERLAY_STEP-px steps and stops the first time it enters a forest pixel,
     * is occluded by rising terrain (a hill crest), or leaves the map — rays are
     * deliberately not range-limited, they must reach across the whole map. The
     * resulting "visibility fan" is stamped into the stencil buffer; then:
     *   - blocked areas (stencil == 0) receive a dark overlay
     *   - visible areas (stencil == 1) receive a faint green tint
     *
     * Elevation occlusion mirrors VisibilitySystem: a point is hidden only when a
     * crest already passed on the ray is taller than BOTH the cursor and that
     * point (by ELEVATION_BLOCK_MARGIN). Same-height or lower terrain never
     * blocks, so high ground sees across and down freely; only a ridge taller
     * than the observer hides what lies behind it.
     *
     * The fan is cached and only recomputed when the cursor moves more than
     * OVERLAY_CACHE_EPS — holding ALT with a still cursor costs nothing.
     *
     * Requires an 8-bit stencil buffer (set in Lwjgl3Launcher).
     */
    public void renderCursorSightOverlay(ShapeRenderer shapes, float cx, float cy) {
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
        shapes.circle(ox, oy, 5f, 16);
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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

    /** Casts one ray at the given angle from the cursor; returns its free distance. */
    private float castAt(double angle) {
        return castRay(buildCx, buildCy, buildHCursor,
                       (float) Math.cos(angle), (float) Math.sin(angle));
    }

    /** Appends the fan boundary point at (angle, distance). */
    private void emit(double angle, float d) {
        fanX.add(buildCx + (float) Math.cos(angle) * d);
        fanY.add(buildCy + (float) Math.sin(angle) * d);
    }

    /**
     * Marches one ray and returns how far it travels before something stops it:
     * forest, an occluding crest, or the map edge. Rays are never range-limited —
     * the overlay analyses terrain, it does not model a unit's sight radius.
     *
     * <h3>Two-level march</h3>
     * Stepping every pixel across a 1440-px map would cost ~2800 samples per ray.
     * Instead the ray walks BLOCK_SIZE-sized blocks (a DDA traversal, so every
     * block on the line is visited exactly once, in order) and consults each
     * block's precomputed summary. A block is skipped whole when it provably
     * cannot stop the ray; only blocks that might are re-walked pixel by pixel.
     *
     * <h3>Why the skip test is exact</h3>
     * A block is skipped only when walking it could change nothing:
     * <ol>
     *   <li>no forest in it — nothing to stop the ray;</li>
     *   <li>{@code blockMax <= crest} — every pixel in it is at or below the
     *       tallest ground already passed, so the fine walk could not raise the
     *       crest either. The crest therefore stays exact and is left untouched;</li>
     *   <li>{@code crest <= max(hCursor, blockMin) + margin} — even against the
     *       LOWEST ground in the block, the crest cannot breach the occlusion
     *       ceiling, so no pixel here can break the ray.</li>
     * </ol>
     * Note the crest is deliberately NOT raised to {@code blockMax} on a skip:
     * {@code blockMax} covers the whole block, including pixels this ray never
     * touches, and adopting it would over-estimate the crest and occlude later
     * ground that is really visible. Condition 2 is what makes leaving it alone
     * correct. (An earlier version did raise it, and diverged from a brute-force
     * march on 0.74% of rays.)
     *
     * <p>Consequence: only blocks that RISE above everything seen so far get
     * walked finely — typically ~10% of them — and hit points land on the exact
     * blocking pixel instead of up to a step short of it.
     */
    private float castRay(float cx, float cy, float hCursor, float dx, float dy) {
        final int B = terrain != null ? terrain.blockSize() : 8;

        // Exact distance at which the ray leaves the map rectangle. Clipping here
        // (rather than breaking on an out-of-bounds sample) also puts the map-edge
        // endpoint exactly on the border.
        float maxDist = distanceToMapExit(cx, cy, dx, dy);
        if (terrain == null || maxDist <= 0f) return Math.max(maxDist, 0f);

        final int blocksX = terrain.blocksX();
        final int blocksY = terrain.blocksY();

        int bx = (int) Math.floor(cx / B);
        int by = (int) Math.floor(cy / B);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);

        // Distance along the ray to cross one whole block, and to reach the first
        // block boundary in each axis.
        float tDeltaX = dx != 0f ? Math.abs(B / dx) : Float.MAX_VALUE;
        float tDeltaY = dy != 0f ? Math.abs(B / dy) : Float.MAX_VALUE;
        float tMaxX = dx > 0 ? ((bx + 1) * B - cx) / dx
                    : dx < 0 ? (bx * B - cx) / dx
                    : Float.MAX_VALUE;
        float tMaxY = dy > 0 ? ((by + 1) * B - cy) / dy
                    : dy < 0 ? (by * B - cy) / dy
                    : Float.MAX_VALUE;

        float crest   = -Float.MAX_VALUE;   // tallest ground passed so far
        float dEnter  = 0f;

        while (dEnter < maxDist) {
            if (bx < 0 || by < 0 || bx >= blocksX || by >= blocksY) return dEnter;

            float dExit = Math.min(Math.min(tMaxX, tMaxY), maxDist);

            int blockMin = terrain.blockMinHeight(bx, by);
            int blockMax = terrain.blockMaxHeight(bx, by);
            boolean canSkip = !terrain.blockHasForest(bx, by)
                && blockMax <= crest
                && crest <= Math.max(hCursor, blockMin) + ELEVATION_BLOCK_MARGIN;

            if (!canSkip) {
                // Walk this block a pixel at a time to find the exact stopping point.
                // Samples are snapped to a global multiple-of-FINE_STEP lattice, so
                // where a block boundary happens to fall cannot shift them — the
                // result is identical to marching the whole ray finely.
                float dStart = Math.max(dEnter, FINE_STEP);
                dStart = (float) Math.ceil(dStart / FINE_STEP) * FINE_STEP;

                for (float d = dStart; d < dExit; d += FINE_STEP) {
                    float wx = cx + dx * d;
                    float wy = cy + dy * d;

                    // Forest right under the cursor must not blind it: mirrors
                    // VisibilitySystem, where the observer's own tile is excluded
                    // from the LOS forest check and handled by sightMod instead.
                    if (d >= ORIGIN_FOREST_SKIP && terrain.isForest(wx, wy)) return d;

                    float hGround = terrain.height(wx, wy);
                    if (crest > Math.max(hCursor, hGround) + ELEVATION_BLOCK_MARGIN) return d;
                    if (hGround > crest) crest = hGround;
                }
            }

            // Advance to the next block along the ray.
            if (tMaxX < tMaxY) { dEnter = tMaxX; tMaxX += tDeltaX; bx += stepX; }
            else               { dEnter = tMaxY; tMaxY += tDeltaY; by += stepY; }
        }

        return maxDist;
    }

    /**
     * Distance from (cx,cy) along (dx,dy) to where the ray leaves the map
     * rectangle. Standard slab test; the direction is a unit vector, so the
     * parameter is already a distance.
     */
    private float distanceToMapExit(float cx, float cy, float dx, float dy) {
        float tx = dx > 0 ? (mapWidth  - cx) / dx : dx < 0 ? -cx / dx : Float.MAX_VALUE;
        float ty = dy > 0 ? (mapHeight - cy) / dy : dy < 0 ? -cy / dy : Float.MAX_VALUE;
        return Math.min(tx, ty);
    }

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
        // sightRange живе у fixed-point (це стан гри) — на межі рендеру
        // переводимо його у float рівно один раз, тут.
        float x = observer.worldX();
        float y = observer.worldY();

        float mod = isForestAt(x, y) ? FOREST_SIGHT_PENALTY : 1f;

        if (terrain != null) {
            switch (terrain.elevation(x, y)) {
                case HIGHLANDS:     mod *= 1.30f; break;
                case PRE_HIGHLANDS: mod *= 1.15f; break;
                case PRE_LOWLANDS:  mod *= 0.92f; break;
                case LOWLANDS:      mod *= 0.80f; break;
                default: break;
            }
        }

        return Fixed.toFloat(observer.sightRange) * mod;
    }

    private boolean isForestAt(float x, float y) {
        return terrain != null && terrain.isForest(x, y);
    }

    /** Numeric terrain height, plains baseline when there is no terrain data. */
    private float elevationHeightAt(float x, float y) {
        return terrain != null ? terrain.height(x, y) : TerrainQuery.PLAINS_BASELINE_HEIGHT;
    }
}
