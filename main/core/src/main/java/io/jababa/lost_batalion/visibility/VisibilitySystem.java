package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.units.Unit;

/**
 * Updates visibleToPlayer for all enemy units each frame.
 *
 * Detection formula:
 *   effectiveSight   = observer.sightRange × sightMod(observer terrain)
 *   effectiveStealth = min(target.stealthRating × losForestMod × elevationMod, 0.9)
 *   detected if dist ≤ effectiveSight × (1 − effectiveStealth)
 *
 * Hard elevation occlusion (overrides everything):
 *   Terrain that rises above the straight line-of-sight between observer and
 *   target hides the target completely — you see a unit ON a hill, but not one
 *   BEHIND it. See {@link #isSightBlockedByElevation}.
 *
 * Two masks are used:
 *   forestMask    — forest tiles (isForestAt): movement/stealth/LOS-forest.
 *   elevationMask — topography tiles (getElevationAt): elevation tiers + rivers.
 * They are separate PNGs per scenario; the forest mask carries no elevation data
 * and vice-versa, so elevation queries MUST go to elevationMask.
 *
 * Forest LOS (losForestMod):
 *   target in forest           → × FOREST_STEALTH_BONUS  (1.8)  — target is hidden
 *   forest on path to target   → × FOREST_LOS_BLOCK_MOD  (4.0)  — sight is blocked
 *   (path check skips observer position, so observer's own forest handled by sightMod only)
 *
 * Observer terrain (sightMod):
 *   forest × 0.55 | lowlands × 0.80 | pre-lowlands × 0.92
 *   highlands × 1.30 | pre-highlands × 1.15
 *
 * Target elevation (elevationStealthMod):
 *   highlands ÷ 1.30 (exposed on skyline) | lowlands × 1.20 (cover in valley)
 */
public class VisibilitySystem {

    private static final float FOREST_SIGHT_PENALTY  = 0.55f;
    private static final float FOREST_STEALTH_BONUS  = 1.8f;   // target standing in forest
    private static final float FOREST_LOS_BLOCK_MOD  = 4.0f;   // forest on the LOS path
    private static final float MAX_EFFECTIVE_STEALTH  = 0.90f;

    /** World units between consecutive ray-march samples. */
    private static final float LOS_SAMPLE_STEP = 12f;

    /**
     * How far (in elevation tiers) terrain must rise above the sight line before
     * it blocks vision. Terrain higher than the interpolated observer→target
     * line by more than this margin occludes the target.
     *
     * With the integer tier heights below and margin 1.0:
     *   HIGHLANDS(5) blocks sight across PLAINS(3)   [diff 2 > 1 → blocked]
     *   PRE_HIGHLANDS(4) gentle rise across PLAINS   [diff 1, not > 1 → visible]
     *   a unit standing ON a hill stays visible (peak is the line endpoint).
     * Lower the margin to make gentler slopes occlude.
     */
    private static final float ELEVATION_BLOCK_MARGIN = 1.0f;

    private final TerrainMaskManager forestMask;
    private final TerrainMaskManager elevationMask;

    public VisibilitySystem(TerrainMaskManager forestMask, TerrainMaskManager elevationMask) {
        this.forestMask    = forestMask;
        this.elevationMask = elevationMask;
    }

    /** Call once per frame before rendering. */
    public void update(Array<Unit> allUnits) {
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u.team != Team.PLAYER) u.visibleToPlayer = false;
        }

        for (int i = 0; i < allUnits.size; i++) {
            Unit observer = allUnits.get(i);
            if (observer.team != Team.PLAYER || !observer.alive) continue;

            float effectiveSight = observer.sightRange * sightMod(observer);

            for (int j = 0; j < allUnits.size; j++) {
                Unit target = allUnits.get(j);
                if (target.team == Team.PLAYER || !target.alive) continue;
                if (target.visibleToPlayer) continue;

                float dist = observer.position.dst(target.position);
                if (dist > effectiveSight) continue;   // outside sight range entirely

                // Hard occlusion: a hill between observer and target blocks sight
                // completely, regardless of range or stealth.
                if (isSightBlockedByElevation(observer, target)) continue;

                float stealthMul = losForestMod(observer, target)
                                 * elevationStealthMod(target);
                float effectiveStealth = Math.min(target.stealthRating * stealthMul, MAX_EFFECTIVE_STEALTH);
                float detectionRange   = effectiveSight * (1f - effectiveStealth);

                if (dist <= detectionRange) target.visibleToPlayer = true;
            }
        }
    }

    /** Whether a world point is lit by any player unit (used for fog tile checks). */
    public boolean isPointVisible(float wx, float wy, Array<Unit> allUnits) {
        for (int i = 0; i < allUnits.size; i++) {
            Unit observer = allUnits.get(i);
            if (observer.team != Team.PLAYER || !observer.alive) continue;
            float effectiveSight = observer.sightRange * sightMod(observer);
            if (observer.position.dst(wx, wy) <= effectiveSight) return true;
        }
        return false;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private float sightMod(Unit observer) {
        float x = observer.position.x, y = observer.position.y;

        float mod = (forestMask != null && forestMask.isForestAt(x, y))
            ? FOREST_SIGHT_PENALTY : 1f;

        switch (elevationAt(x, y)) {
            case HIGHLANDS:     mod *= 1.30f; break;
            case PRE_HIGHLANDS: mod *= 1.15f; break;
            case PRE_LOWLANDS:  mod *= 0.92f; break;
            case LOWLANDS:      mod *= 0.80f; break;
            default: break;
        }
        return mod;
    }

    /**
     * Forest concealment multiplier for stealthRating.
     * Checks whether the target is in forest, and — separately — whether any
     * forest lies on the path between the observer and the target.
     * The path check starts one sample-step away from the observer so the
     * observer's own forest position is handled solely by sightMod, not doubled.
     */
    private float losForestMod(Unit observer, Unit target) {
        if (forestMask == null) return 1f;

        float tx = target.position.x, ty = target.position.y;

        if (forestMask.isForestAt(tx, ty)) return FOREST_STEALTH_BONUS;

        if (hasForestOnPath(observer.position.x, observer.position.y, tx, ty))
            return FOREST_LOS_BLOCK_MOD;

        return 1f;
    }

    /** Target elevation: high ground exposes a unit, low ground gives cover. */
    private float elevationStealthMod(Unit target) {
        switch (elevationAt(target.position.x, target.position.y)) {
            case HIGHLANDS:     return 1f / 1.30f;
            case PRE_HIGHLANDS: return 1f / 1.15f;
            case PRE_LOWLANDS:  return 1.08f;
            case LOWLANDS:      return 1.20f;
            default:            return 1f;
        }
    }

    /**
     * Returns true if any sample point on the straight-line path from (x1,y1)
     * to (x2,y2) falls inside forest terrain.
     *
     * Sampling starts one LOS_SAMPLE_STEP away from the origin so the origin's
     * own terrain doesn't contribute (already handled by sightMod). Sampling
     * stops before the destination (its terrain is checked separately by
     * losForestMod via isForestAt).
     */
    private boolean hasForestOnPath(float x1, float y1, float x2, float y2) {
        if (forestMask == null) return false;

        float dx   = x2 - x1;
        float dy   = y2 - y1;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        if (dist <= LOS_SAMPLE_STEP) return false;

        float stepT = LOS_SAMPLE_STEP / dist;
        for (float t = stepT; t < 1.0f; t += stepT) {
            if (forestMask.isForestAt(x1 + dx * t, y1 + dy * t)) return true;
        }
        return false;
    }

    // ── Elevation line-of-sight ──────────────────────────────────────────────

    /**
     * True if a hill between observer and target blocks vision.
     *
     * A tile occludes only when it is taller than BOTH endpoints by more than
     * ELEVATION_BLOCK_MARGIN — i.e. a genuine crest poking above the pair. Ground
     * at or below either endpoint never blocks, so:
     *   - high ground sees across and down onto lower terrain freely,
     *   - a unit ON a hill is visible from lower ground (its own slope ≤ itself),
     *   - only a ridge taller than observer and target hides what's behind it.
     *
     * Sampling skips both endpoints' own tiles (starts one step out, stops before
     * the target) so standing on a peak never self-occludes.
     */
    private boolean isSightBlockedByElevation(Unit observer, Unit target) {
        if (elevationMask == null) return false;

        float x1 = observer.position.x, y1 = observer.position.y;
        float x2 = target.position.x,   y2 = target.position.y;

        float dx   = x2 - x1;
        float dy   = y2 - y1;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist <= LOS_SAMPLE_STEP) return false;

        float hObs = elevationHeight(elevationAt(x1, y1));
        float hTgt = elevationHeight(elevationAt(x2, y2));
        float ceiling = Math.max(hObs, hTgt) + ELEVATION_BLOCK_MARGIN;

        float stepT = LOS_SAMPLE_STEP / dist;
        for (float t = stepT; t < 1.0f; t += stepT) {
            float groundH = elevationHeight(elevationAt(x1 + dx * t, y1 + dy * t));
            if (groundH > ceiling) return true;
        }
        return false;
    }

    /** Numeric height per elevation tier (used only for LOS occlusion math). */
    private float elevationHeight(TerrainType t) {
        switch (t) {
            case RIVER:         return 0f;
            case LOWLANDS:      return 1f;
            case PRE_LOWLANDS:  return 2f;
            case PLAINS:
            case PLAINS_ALT:    return 3f;
            case PRE_HIGHLANDS: return 4f;
            case HIGHLANDS:     return 5f;
            default:            return 3f;   // NONE/FOREST → plains-level baseline
        }
    }

    private TerrainType elevationAt(float x, float y) {
        return elevationMask != null ? elevationMask.getElevationAt(x, y) : TerrainType.NONE;
    }
}
