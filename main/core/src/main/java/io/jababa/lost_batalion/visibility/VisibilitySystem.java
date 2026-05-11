package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.units.Unit;

/**
 * Updates visibleToPlayer for all enemy units each frame.
 *
 * Detection formula:
 *   effectiveSight   = observer.sightRange × sightMod(observer terrain)
 *   effectiveStealth = min(target.stealthRating × losForestMod × elevationMod, 0.9)
 *   detected if dist ≤ effectiveSight × (1 − effectiveStealth)
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

    private final TerrainMaskManager terrain;

    public VisibilitySystem(TerrainMaskManager terrain) {
        this.terrain = terrain;
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
        if (terrain == null) return 1f;
        float x = observer.position.x, y = observer.position.y;

        float mod = terrain.isForestAt(x, y) ? FOREST_SIGHT_PENALTY : 1f;

        switch (terrain.getElevationAt(x, y)) {
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
        if (terrain == null) return 1f;

        float tx = target.position.x, ty = target.position.y;

        if (terrain.isForestAt(tx, ty)) return FOREST_STEALTH_BONUS;

        if (hasForestOnPath(observer.position.x, observer.position.y, tx, ty))
            return FOREST_LOS_BLOCK_MOD;

        return 1f;
    }

    /** Target elevation: high ground exposes a unit, low ground gives cover. */
    private float elevationStealthMod(Unit target) {
        if (terrain == null) return 1f;
        switch (terrain.getElevationAt(target.position.x, target.position.y)) {
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
        float dx   = x2 - x1;
        float dy   = y2 - y1;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);

        if (dist <= LOS_SAMPLE_STEP) return false;

        float stepT = LOS_SAMPLE_STEP / dist;
        for (float t = stepT; t < 1.0f; t += stepT) {
            if (terrain.isForestAt(x1 + dx * t, y1 + dy * t)) return true;
        }
        return false;
    }
}
