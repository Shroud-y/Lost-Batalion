package io.jababa.lost_batalion.visibility;

import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.units.Unit;

/**
 * Ray caster for line-of-sight overlays — the shared engine behind the ALT
 * cursor fan ({@link FogOfWarRenderer}) and the per-unit sight rings
 * ({@link SightRingRenderer}).
 *
 * <p>Витягнуто з {@code FogOfWarRenderer} без жодної зміни логіки: два
 * споживачі ставлять різні запитання («докуди звідси видно» проти «чи дістає
 * промінь до межі огляду»), але ХОДА променя в них одна й та сама, і роздвоїти
 * її означало б розсинхронити дві картинки, які обидві мусять збігатися з
 * {@link VisibilitySystem}.
 *
 * <p>Це рендерна математика: {@code float}, не {@link io.jababa.lost_batalion.math.Fixed}.
 * Нічого звідси не входить у симуляцію і в checksum.
 *
 * <p><b>Не потокобезпечний</b> — скретч-стан променя живе в полях (див.
 * {@link #raySlope}), тож один екземпляр обслуговує один промінь за раз.
 */
final class SightCaster {

    /** @see TerrainQuery#ELEVATION_BLOCK_MARGIN_TIERS — single source of truth. */
    private static final float ELEVATION_BLOCK_MARGIN = TerrainQuery.ELEVATION_BLOCK_MARGIN_TIERS;
    /**
     * How long a rise must persist along the ray before it counts as a crest that
     * blocks sight. Shared with VisibilitySystem via TerrainQuery, so the overlay
     * shows exactly what detection computes.
     * @see TerrainQuery#LOS_CREST_MIN_RUN
     */
    private static final float CREST_MIN_RUN      = TerrainQuery.LOS_CREST_MIN_RUN;
    /**
     * Nudge used to decide which pixel a boundary-landing distance belongs to.
     * Far below one mask pixel, far above float noise at map scale.
     */
    private static final float PIXEL_EPS          = 1e-3f;
    /**
     * Forest within this distance of the origin does not stop a ray. Without it,
     * an observer standing in a forest tile would collapse the whole fan to a
     * point. Same constant VisibilitySystem uses to exclude the observer's own
     * tile.
     */
    private static final float ORIGIN_FOREST_SKIP = TerrainQuery.LOS_ORIGIN_FOREST_SKIP;

    private final float mapWidth;
    private final float mapHeight;
    private final TerrainQuery terrain;

    /*
     * Scratch state for one ray's pixel walk, so it survives across the blocks the
     * ray crosses (castRay drives the blocks, walkPixels the pixels inside them).
     *
     * The ray tracks a SLOPE, not a height. VisibilitySystem occludes when ground
     * rises a tier above the straight sight line between two known endpoints; a
     * ray has no endpoint, so the same rule is carried in the equivalent form:
     *
     *   crest at distance s, height h  →  slope (h − hOrigin − MARGIN) / s
     *   point at distance d, height g  →  hidden when (g − hOrigin) / d ≤ raySlope
     *
     * The two are the same inequality rearranged, so the overlay keeps showing
     * exactly what detection computes.
     */
    private float raySlope;     // steepest CONFIRMED crest slope so far
    private float rayCand;      // height of the rise currently being measured
    private float rayCandLen;   // how far that rise has persisted, world units

    SightCaster(float mapWidth, float mapHeight, TerrainQuery terrain) {
        this.mapWidth  = mapWidth;
        this.mapHeight = mapHeight;
        this.terrain   = terrain;
    }

    /** Numeric terrain height, plains baseline when there is no terrain data. */
    float heightAt(float x, float y) {
        return terrain != null ? terrain.height(x, y) : TerrainQuery.PLAINS_BASELINE_HEIGHT;
    }

    /**
     * Observer's effective sight radius including terrain modifiers.
     * Mirrors {@code VisibilitySystem.sightMod} so every surface that draws a
     * sight radius — the fog circle, the white sight ring — promises exactly the
     * range detection grants.
     */
    float effectiveSight(Unit observer) {
        return effectiveSightAt(observer, observer.worldX(), observer.worldY());
    }

    /**
     * Те саме, але для довільної точки: «яку дальність мав би цей юніт, якби
     * стояв ОСЬ ТУТ». Потрібно кільцям огляду, які центруються на курсорі, а не
     * на самому юніті — множник місцевості мусить читатись із тайла під курсором,
     * інакше кільце обіцяло б дальність, якої на тому місці не буде.
     */
    float effectiveSightAt(Unit observer, float x, float y) {
        // sightRange живе у fixed-point (це стан гри) — на межі рендеру
        // переводимо його у float рівно один раз, тут.
        return Fixed.toFloat(observer.sightRange) * sightModAt(x, y);
    }

    /** Множник дальності від місцевості. Дзеркало {@code VisibilitySystem.sightMod}. */
    private float sightModAt(float x, float y) {
        float mod = isForest(x, y) ? VisibilitySystem.SIGHT_MOD_FOREST : 1f;

        if (terrain != null) {
            switch (terrain.elevation(x, y)) {
                case HIGHLANDS:     mod *= VisibilitySystem.SIGHT_MOD_HIGHLANDS;     break;
                case PRE_HIGHLANDS: mod *= VisibilitySystem.SIGHT_MOD_PRE_HIGHLANDS; break;
                case PRE_LOWLANDS:  mod *= VisibilitySystem.SIGHT_MOD_PRE_LOWLANDS;  break;
                case LOWLANDS:      mod *= VisibilitySystem.SIGHT_MOD_LOWLANDS;      break;
                default: break;
            }
        }
        return mod;
    }

    private boolean isForest(float x, float y) {
        return terrain != null && terrain.isForest(x, y);
    }

    /**
     * Marches one ray and returns how far it travels before something stops it:
     * forest, an occluding crest, the map edge, or {@code rangeLimit}.
     *
     * <p>{@code rangeLimit} is the ONLY difference between the two callers. The
     * cursor fan passes {@link Float#MAX_VALUE}: it analyses TERRAIN — «куди
     * звідси взагалі можна дивитись» — and a unit's sight radius has no place in
     * that picture (this is a decision, not an oversight; capping the fan was
     * tried and rejected). The sight ring passes the unit's effective sight, and
     * reads the returned distance as a yes/no: reaching the limit means the ring
     * is drawn at that angle, stopping short means a gap.
     *
     * <h3>Two-level march</h3>
     * Stepping every pixel across a 1440-px map would cost ~2800 samples per ray.
     * Instead the ray walks BLOCK_SIZE-sized blocks (a DDA traversal, so every
     * block on the line is visited exactly once, in order) and consults each
     * block's precomputed summary. A block is skipped whole when it provably
     * cannot stop the ray; only blocks that might are re-walked pixel by pixel
     * (see {@link #walkPixels}).
     *
     * <h3>Why the skip test is exact</h3>
     * A block is skipped only when walking it could change nothing:
     * <ol>
     *   <li>no forest in it — nothing to stop the ray;</li>
     *   <li>the steepest slope the block could contribute, {@code (blockMax −
     *       hOrigin − margin) / s}, does not beat the confirmed slope anywhere
     *       inside it. The slope therefore stays exact and is left untouched;</li>
     *   <li>the shallowest slope the block could OFFER as a target,
     *       {@code (blockMin − hOrigin) / s}, still clears the confirmed slope,
     *       so no pixel here can be occluded.</li>
     * </ol>
     * Both bounds vary with distance, so each is evaluated at the two ends of the
     * block's span along the ray and taken at its worst — the numerator is
     * constant, so a monotone quotient cannot hide an extremum in between.
     *
     * <p>Note the slope is deliberately NOT raised to {@code blockMax} on a skip:
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
    float castRay(float cx, float cy, float hOrigin, float dx, float dy, float rangeLimit) {
        final int B = terrain != null ? terrain.blockSize() : 8;

        // Exact distance at which the ray leaves the map rectangle. Clipping here
        // (rather than breaking on an out-of-bounds sample) also puts the map-edge
        // endpoint exactly on the border. The range limit is folded in the same
        // way, so a ray that runs out of sight range is indistinguishable from one
        // that ran off the map — both simply stop, with no crest state to unwind.
        float maxDist = Math.min(distanceToMapExit(cx, cy, dx, dy), rangeLimit);
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

        raySlope   = -Float.MAX_VALUE;   // steepest slope confirmed as a crest
        rayCand    = -Float.MAX_VALUE;
        rayCandLen = 0f;
        float dEnter = 0f;

        while (dEnter < maxDist) {
            if (bx < 0 || by < 0 || bx >= blocksX || by >= blocksY) return dEnter;

            float dExit = Math.min(Math.min(tMaxX, tMaxY), maxDist);

            int blockMin = terrain.blockMinHeight(bx, by);
            int blockMax = terrain.blockMaxHeight(bx, by);

            // The block starts at the origin: every slope through it is unbounded,
            // so there is nothing to compare against — walk it.
            boolean canSkip = dEnter > 0f
                && !terrain.blockHasForest(bx, by)
                && maxSlope(blockMax - hOrigin - ELEVATION_BLOCK_MARGIN, dEnter, dExit) <= raySlope
                && minSlope(blockMin - hOrigin, dEnter, dExit) > raySlope;

            if (canSkip) {
                // Nothing here reaches above the confirmed crest, so any rise that
                // was being measured provably breaks off inside this block.
                rayCand    = -Float.MAX_VALUE;
                rayCandLen = 0f;
            } else {
                float hit = walkPixels(cx, cy, dx, dy, dEnter, dExit, hOrigin);
                if (hit >= 0f) return hit;
            }

            // Advance to the next block along the ray.
            if (tMaxX < tMaxY) { dEnter = tMaxX; tMaxX += tDeltaX; bx += stepX; }
            else               { dEnter = tMaxY; tMaxY += tDeltaY; by += stepY; }
        }

        return maxDist;
    }

    /**
     * Walks the ray pixel by pixel between two distances, returning the exact
     * distance at which something stops it, or -1 when nothing does.
     *
     * <h3>Why a DDA rather than fixed-step sampling</h3>
     * This used to sample every FINE_STEP (= 1 world unit) along the ray. Two
     * costs: the reported hit distance was quantised to that lattice, which shows
     * up as stair-stepping along the fan boundary once the camera is zoomed in;
     * and a diagonal ray advances only 0.71 units per axis per step, so it could
     * step straight over pixels it actually passes through. Crossing pixel
     * boundaries analytically fixes both — every pixel the ray touches is visited
     * exactly once, and the returned distance is the true edge of the blocking
     * pixel. It is not more expensive: the same pixels, visited once each.
     *
     * <h3>Why a crest must persist</h3>
     * Height tiers are pixel data with jagged, staircase boundaries. A ray running
     * nearly tangent to one clips a single corner pixel while its neighbour misses
     * it, and under the old rule that one pixel raised the crest permanently and
     * cut the rest of the ray — a one-ray-wide stripe of shadow reaching across the
     * map. Requiring the rise to hold for CREST_MIN_RUN removes those without
     * touching real ridges, which persist far longer. Measured on the Zhovti Vody
     * masks: ~80% fewer such stripes, mean ray length within 1%.
     *
     * <p>Run length is accumulated as traversed distance, not as a sample count,
     * so it means the same thing at every ray angle — a corner clipped at a shallow
     * angle contributes the sliver it really covers.
     */
    private float walkPixels(float cx, float cy, float dx, float dy,
                             float dStart, float dEnd, float hOrigin) {
        if (dEnd <= dStart) return -1f;

        // Probe just inside the interval, so a dStart sitting exactly on a pixel
        // boundary resolves to the pixel being entered, not the one being left.
        float probe = dStart + PIXEL_EPS;
        int px = (int) Math.floor(cx + dx * probe);
        int py = (int) Math.floor(cy + dy * probe);

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);

        // Distances measured from the ray ORIGIN, not from dStart: recomputing them
        // per block would let rounding drift the lattice between blocks.
        float tDeltaX = dx != 0f ? Math.abs(1f / dx) : Float.MAX_VALUE;
        float tDeltaY = dy != 0f ? Math.abs(1f / dy) : Float.MAX_VALUE;
        float tMaxX = dx > 0 ? (px + 1 - cx) / dx : dx < 0 ? (px - cx) / dx : Float.MAX_VALUE;
        float tMaxY = dy > 0 ? (py + 1 - cy) / dy : dy < 0 ? (py - cy) / dy : Float.MAX_VALUE;

        float d = dStart;
        while (d < dEnd) {
            float dNext = Math.min(Math.min(tMaxX, tMaxY), dEnd);

            // Pixel centre: exact, and independent of where inside the pixel the
            // ray happens to run.
            float sx = px + 0.5f;
            float sy = py + 0.5f;

            // Forest right under the observer must not blind it: mirrors
            // VisibilitySystem, where the observer's own tile is excluded from the
            // LOS forest check and handled by sightMod instead.
            if (dNext > ORIGIN_FOREST_SKIP && terrain.isForest(sx, sy))
                return Math.max(d, ORIGIN_FOREST_SKIP);

            float hGround = terrain.height(sx, sy);

            // Occlusion first: a pixel never occludes itself. The sight line to
            // THIS point is shallower than a crest already passed → it is hidden.
            float dMid = 0.5f * (d + dNext);
            if (dMid > 0f && (hGround - hOrigin) / dMid <= raySlope) return d;

            // The rise is still tracked by HEIGHT, not slope: the crest-run rule
            // exists to reject single stray pixels of a staircase boundary, and
            // that is a question about the ground, not about the angle. The slope
            // is taken at the far end of the run — the conservative end, since
            // the same height yields a shallower slope the further out it sits.
            float cand = (hGround - hOrigin - ELEVATION_BLOCK_MARGIN) / dMid;
            if (dMid > 0f && cand > raySlope) {
                if (hGround == rayCand) rayCandLen += dNext - d;
                else { rayCand = hGround; rayCandLen = dNext - d; }
                if (rayCandLen >= CREST_MIN_RUN) raySlope = cand;
            } else {
                rayCand    = -Float.MAX_VALUE;
                rayCandLen = 0f;
            }

            if (tMaxX < tMaxY) { tMaxX += tDeltaX; px += stepX; }
            else               { tMaxY += tDeltaY; py += stepY; }
            d = dNext;
        }
        return -1f;
    }

    /**
     * Largest value of {@code rise / d} over {@code d ∈ [dNear, dFar]}.
     *
     * <p>The numerator is fixed and the interval starts above zero, so the
     * quotient is monotone: the extremum is always at one end. Which end flips
     * with the sign of the rise, hence both are evaluated rather than reasoned
     * about at the call site.
     */
    private static float maxSlope(float rise, float dNear, float dFar) {
        return Math.max(rise / dNear, rise / dFar);
    }

    /** Smallest value of {@code rise / d} over the same interval. */
    private static float minSlope(float rise, float dNear, float dFar) {
        return Math.min(rise / dNear, rise / dFar);
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
}
