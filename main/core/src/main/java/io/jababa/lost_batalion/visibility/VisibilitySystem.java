package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.terrain.TerrainQuery;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.units.Unit;

/**
 * Updates per-side visibility flags (see {@link Unit#isVisibleTo}) every tick,
 * for both sides. All arithmetic is fixed-point ({@link Fixed}) — visibility
 * feeds back into the simulation (an attack order is dropped the moment its
 * target leaves sight), so a one-ulp difference between two clients would end
 * the match differently.
 *
 * Detection formula:
 *   effectiveSight   = observer.sightRange × sightMod(observer terrain)
 *   effectiveStealth = min(target.stealthRating × losForestMod × elevationMod, 0.9)
 *   detected if dist ≤ effectiveSight × (1 − effectiveStealth)
 *
 * Distances are compared squared: the square root is not needed for an
 * inequality, and skipping it removes the single most expensive call from the
 * hottest loop in the tick.
 *
 * Hard elevation occlusion (overrides everything):
 *   Terrain that rises above the straight line-of-sight between observer and
 *   target hides the target completely — you see a unit ON a hill, but not one
 *   BEHIND it. See {@link #isSightBlockedByElevation}.
 *
 * All terrain access goes through {@link TerrainQuery}, which knows which of the
 * scenario's two masks holds what (rivers live in the forest mask, height tiers
 * in the topography mask).
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

    private static final long FOREST_SIGHT_PENALTY = Fixed.fromFloat(0.55f);
    private static final long FOREST_STEALTH_BONUS = Fixed.fromFloat(1.8f);   // target standing in forest
    private static final long FOREST_LOS_BLOCK_MOD = Fixed.fromFloat(4.0f);   // forest on the LOS path
    private static final long MAX_EFFECTIVE_STEALTH = Fixed.fromFloat(0.90f);

    private static final long SIGHT_HIGHLANDS     = Fixed.fromFloat(1.30f);
    private static final long SIGHT_PRE_HIGHLANDS = Fixed.fromFloat(1.15f);
    private static final long SIGHT_PRE_LOWLANDS  = Fixed.fromFloat(0.92f);
    private static final long SIGHT_LOWLANDS      = Fixed.fromFloat(0.80f);

    private static final long STEALTH_PRE_LOWLANDS = Fixed.fromFloat(1.08f);
    private static final long STEALTH_LOWLANDS     = Fixed.fromFloat(1.20f);

    private final TerrainQuery terrain;

    public VisibilitySystem(TerrainQuery terrain) {
        this.terrain = terrain;
    }

    /**
     * Один крок видимості — рахується для ОБОХ сторін.
     *
     * <p>Не «для локального гравця»: видимість впливає на симуляцію, тож якби
     * кожен клієнт рахував лише свій бік, накази знімались би в різні моменти
     * і матч розсинхронізувався б. Обидві сторони — частина стану і колись
     * підуть у checksum.
     *
     * <p>Порядок обходу — індекси масиву, однаковий скрізь.
     */
    public void update(Array<Unit> allUnits) {
        updateForViewer(allUnits, Team.PLAYER);
        updateForViewer(allUnits, Team.ENEMY);
    }

    private void updateForViewer(Array<Unit> allUnits, Team viewer) {
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u.team != viewer) u.setVisibleTo(viewer, false);
        }

        for (int i = 0; i < allUnits.size; i++) {
            Unit observer = allUnits.get(i);
            if (observer.team != viewer || !observer.alive) continue;

            long effectiveSight = Fixed.mul(observer.sightRange, sightMod(observer));
            long sightSq        = Fixed.mul(effectiveSight, effectiveSight);

            for (int j = 0; j < allUnits.size; j++) {
                Unit target = allUnits.get(j);
                if (target.team == viewer || !target.alive) continue;
                if (target.isVisibleTo(viewer)) continue;

                long distSq = Fixed.dstSq(observer.x, observer.y, target.x, target.y);
                if (distSq > sightSq) continue;   // outside sight range entirely

                // Hard occlusion: a hill between observer and target blocks sight
                // completely, regardless of range or stealth.
                if (isSightBlockedByElevation(observer, target)) continue;

                long stealthMul = Fixed.mul(losForestMod(observer, target),
                                            elevationStealthMod(target));
                long effectiveStealth = Fixed.min(Fixed.mul(target.stealthRating, stealthMul),
                                                  MAX_EFFECTIVE_STEALTH);
                long detectionRange = Fixed.mul(effectiveSight, Fixed.ONE - effectiveStealth);

                if (distSq <= Fixed.mul(detectionRange, detectionRange))
                    target.setVisibleTo(viewer, true);
            }
        }
    }

    /** Whether a world point is lit by any unit of {@code viewer} (fog tile checks). */
    public boolean isPointVisible(long rawX, long rawY, Array<Unit> allUnits, Team viewer) {
        for (int i = 0; i < allUnits.size; i++) {
            Unit observer = allUnits.get(i);
            if (observer.team != viewer || !observer.alive) continue;
            long effectiveSight = Fixed.mul(observer.sightRange, sightMod(observer));
            if (Fixed.dstSq(observer.x, observer.y, rawX, rawY)
                    <= Fixed.mul(effectiveSight, effectiveSight)) return true;
        }
        return false;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private long sightMod(Unit observer) {
        long mod = isForest(observer.x, observer.y) ? FOREST_SIGHT_PENALTY : Fixed.ONE;

        switch (elevationAt(observer.x, observer.y)) {
            case HIGHLANDS:     mod = Fixed.mul(mod, SIGHT_HIGHLANDS);     break;
            case PRE_HIGHLANDS: mod = Fixed.mul(mod, SIGHT_PRE_HIGHLANDS); break;
            case PRE_LOWLANDS:  mod = Fixed.mul(mod, SIGHT_PRE_LOWLANDS);  break;
            case LOWLANDS:      mod = Fixed.mul(mod, SIGHT_LOWLANDS);      break;
            default: break;
        }
        return mod;
    }

    /**
     * Forest concealment multiplier for stealthRating.
     * Checks whether the target is in forest, and — separately — whether any
     * forest lies on the path between the observer and the target.
     * The path check starts LOS_ORIGIN_FOREST_SKIP away from the observer so the
     * observer's own forest position is handled solely by sightMod, not doubled.
     */
    private long losForestMod(Unit observer, Unit target) {
        if (terrain == null) return Fixed.ONE;

        if (terrain.isForestF(target.x, target.y)) return FOREST_STEALTH_BONUS;

        if (terrain.hasForestOnSegmentF(observer.x, observer.y, target.x, target.y,
                                        TerrainQuery.LOS_ORIGIN_FOREST_SKIP_FIXED))
            return FOREST_LOS_BLOCK_MOD;

        return Fixed.ONE;
    }

    /** Target elevation: high ground exposes a unit, low ground gives cover. */
    private long elevationStealthMod(Unit target) {
        switch (elevationAt(target.x, target.y)) {
            case HIGHLANDS:     return Fixed.div(Fixed.ONE, SIGHT_HIGHLANDS);
            case PRE_HIGHLANDS: return Fixed.div(Fixed.ONE, SIGHT_PRE_HIGHLANDS);
            case PRE_LOWLANDS:  return STEALTH_PRE_LOWLANDS;
            case LOWLANDS:      return STEALTH_LOWLANDS;
            default:            return Fixed.ONE;
        }
    }

    // ── Elevation line-of-sight ──────────────────────────────────────────────

    /**
     * True if a hill between observer and target blocks vision.
     *
     * A tile occludes only when it is taller than BOTH endpoints — i.e. a genuine
     * crest poking above the pair. Ground at or below either endpoint never
     * blocks, so:
     *   - high ground sees across and down onto lower terrain freely,
     *   - a unit ON a hill is visible from lower ground (its own slope ≤ itself),
     *   - only a ridge taller than observer and target hides what's behind it.
     *
     * Heights are integer tiers, so "taller than both by more than half a tier"
     * is exactly "at least one tier above the taller endpoint" — see
     * {@link TerrainQuery#blockingHeightFor}. Sampling skips both endpoints' own
     * tiles so standing on a peak never self-occludes.
     */
    private boolean isSightBlockedByElevation(Unit observer, Unit target) {
        if (terrain == null) return false;

        int hObs = terrain.heightF(observer.x, observer.y);
        int hTgt = terrain.heightF(target.x, target.y);

        return terrain.hasGroundAboveOnSegmentF(observer.x, observer.y, target.x, target.y,
                                                TerrainQuery.blockingHeightFor(hObs, hTgt));
    }

    private boolean isForest(long rawX, long rawY) {
        return terrain != null && terrain.isForestF(rawX, rawY);
    }

    private TerrainType elevationAt(long rawX, long rawY) {
        return terrain != null ? terrain.elevationF(rawX, rawY) : TerrainType.NONE;
    }
}
