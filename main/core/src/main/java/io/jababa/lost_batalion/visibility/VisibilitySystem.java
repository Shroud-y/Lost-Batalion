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
 *   Terrain that rises a full tier above the straight sight LINE between the two
 *   units hides the target completely — you see a unit ON a hill, but not one
 *   BEHIND it, and from inside a hollow you see little but the hollow. The line
 *   runs between the endpoints' own ground heights, so the rule is symmetric.
 *   See {@link #isSightBlockedByElevation}.
 *
 * All terrain access goes through {@link TerrainQuery}, which knows which of the
 * scenario's two masks holds what (rivers live in the forest mask, height tiers
 * in the topography mask).
 *
 * Forest LOS (losForestMod) — the two multiply, they are not alternatives:
 *   target in forest           → × FOREST_STEALTH_BONUS  (1.8)  — target is hidden
 *   forest on path to target   → × FOREST_LOS_BLOCK_MOD  (4.0)  — sight is blocked
 *   (path check has a blind zone at BOTH ends: each unit's own trees are already
 *    priced in — the observer's by sightMod, the target's by the 1.8 above)
 *
 *   So depth into forest matters: on the treeline 1.8 → seen at 0.64 × sight;
 *   deep inside 1.8 × 4.0 = 7.2 → stealth clamps to 0.9 → seen at 0.1 × sight.
 *
 * Observer terrain (sightMod):
 *   forest × 0.55 | lowlands × 0.40 | pre-lowlands × 0.65
 *   highlands × 1.30 | pre-highlands × 1.15
 *
 * Target elevation (elevationStealthMod):
 *   highlands ÷ 1.30 (exposed on skyline) | lowlands × 1.20 (cover in valley)
 */
public class VisibilitySystem {

    /*
     * Sight multipliers by observer terrain — PUBLIC and float, because the fog
     * renderer draws the sight circle from the same numbers. It used to keep its
     * own copy of this table with a "must stay in sync" comment above it, which
     * is exactly the arrangement that drifts: the circle on screen would promise
     * a range detection no longer granted.
     *
     * The simulation still works in fixed-point; these floats are converted once,
     * at class load, and never enter the tick.
     */
    public static final float SIGHT_MOD_FOREST         = 0.55f;
    public static final float SIGHT_MOD_HIGHLANDS      = 1.30f;
    public static final float SIGHT_MOD_PRE_HIGHLANDS  = 1.15f;
    /**
     * Перед-низини. Було 0.92 — тобто фактично нічого.
     *
     * <p>Причина зниження суто картографічна: справжніх {@code LOWLANDS} на
     * Жовтих Водах лише 2.06% карти, п'ять плям радіусом 37–66 одиниць. Штраф,
     * що діє тільки на них, гравець просто не зустрічає — улоговини, які видно
     * оком, намальовані саме перед-низинами (6.4%). Разом з 0.40 у самих
     * низинах виходить сходинка 0.40 → 0.65 → 1.0, а не обрив на 2% карти.
     */
    public static final float SIGHT_MOD_PRE_LOWLANDS   = 0.65f;
    /**
     * Нізини. Було 0.80 — разом із виправленням перекриття огляду рельєфом
     * виявилось, що цього замало: юніт у ямі втрачав напрямки, але не дальність,
     * і далі розвідував майже на повну. Половина від колишнього значення робить
     * низину тим, чим вона є, — місцем, де ти не бачиш нічого й тебе не бачать.
     */
    public static final float SIGHT_MOD_LOWLANDS       = 0.40f;

    private static final long FOREST_SIGHT_PENALTY = Fixed.fromFloat(SIGHT_MOD_FOREST);
    private static final long FOREST_STEALTH_BONUS = Fixed.fromFloat(1.8f);   // target standing in forest
    private static final long FOREST_LOS_BLOCK_MOD = Fixed.fromFloat(4.0f);   // forest on the LOS path
    private static final long MAX_EFFECTIVE_STEALTH = Fixed.fromFloat(0.90f);

    private static final long SIGHT_HIGHLANDS     = Fixed.fromFloat(SIGHT_MOD_HIGHLANDS);
    private static final long SIGHT_PRE_HIGHLANDS = Fixed.fromFloat(SIGHT_MOD_PRE_HIGHLANDS);
    private static final long SIGHT_PRE_LOWLANDS  = Fixed.fromFloat(SIGHT_MOD_PRE_LOWLANDS);
    private static final long SIGHT_LOWLANDS      = Fixed.fromFloat(SIGHT_MOD_LOWLANDS);

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

            for (int j = 0; j < allUnits.size; j++) {
                Unit target = allUnits.get(j);
                if (target.team == viewer || !target.alive) continue;
                if (target.isVisibleTo(viewer)) continue;

                if (canSee(observer, target)) target.setVisibleTo(viewer, true);
            }
        }
    }

    /**
     * Чи бачить КОНКРЕТНИЙ юніт конкретну ціль.
     *
     * <p>Сторонова видимість ({@link Unit#isVisibleTo}) відповідає на інше
     * питання — «чи бачить це хоч хтось із армії». Артилерії цього замало:
     * гармата не повинна класти снаряди по цілі, яку розвідав піхотинець на
     * іншому фланзі, — вона стріляє лише по тому, що бачить сама.
     *
     * <p>Та сама формула, що й у пообхідному оновленні, тому результати двох
     * методів не можуть розійтись: обхід нею ж і рахує.
     */
    public boolean canSee(Unit observer, Unit target) {
        if (observer == null || target == null) return false;
        if (!observer.alive || !target.alive) return false;
        if (observer.team == target.team) return true;

        long effectiveSight = Fixed.mul(observer.sightRange, sightMod(observer));
        long distSq = Fixed.dstSq(observer.x, observer.y, target.x, target.y);
        if (distSq > Fixed.mul(effectiveSight, effectiveSight)) return false;

        // Hard occlusion: a hill between observer and target blocks sight
        // completely, regardless of range or stealth.
        if (isSightBlockedByElevation(observer, target)) return false;

        long stealthMul = Fixed.mul(losForestMod(observer, target),
                                    elevationStealthMod(target));
        long effectiveStealth = Fixed.min(Fixed.mul(target.stealthRating, stealthMul),
                                          MAX_EFFECTIVE_STEALTH);
        long detectionRange = Fixed.mul(effectiveSight, Fixed.ONE - effectiveStealth);

        return distSq <= Fixed.mul(detectionRange, detectionRange);
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
     *
     * <p>Two independent effects, MULTIPLIED — they are not alternatives:
     * <ul>
     *   <li>the target stands in forest → ×1.8 (its own cover);</li>
     *   <li>forest lies on the path between the two → ×4.0 (sight is blocked).</li>
     * </ul>
     *
     * <p>This used to return early on the first check, so a target in forest never
     * ran the second one — and depth into the forest changed nothing: a unit on the
     * treeline and a unit 300 units deep were both spotted at 0.64 × sight. The
     * opposite direction (observer in forest, target outside) had no early return
     * and therefore behaved correctly, which is exactly the asymmetry that showed
     * up in play.
     *
     * <p>The path check has a blind zone of LOS_ORIGIN_FOREST_SKIP at BOTH ends:
     * the observer's own trees are already priced into sightMod, and the target's
     * own trees are already priced into the ×1.8 above. Without the second skip
     * every forest target would count as path-blocked, since the ray cannot help
     * hitting the trees it is standing in.
     */
    private long losForestMod(Unit observer, Unit target) {
        if (terrain == null) return Fixed.ONE;

        long mod = terrain.isForestF(target.x, target.y) ? FOREST_STEALTH_BONUS : Fixed.ONE;

        if (terrain.hasForestOnSegmentF(observer.x, observer.y, target.x, target.y,
                                        TerrainQuery.LOS_ORIGIN_FOREST_SKIP_FIXED,
                                        TerrainQuery.LOS_ORIGIN_FOREST_SKIP_FIXED))
            mod = Fixed.mul(mod, FOREST_LOS_BLOCK_MOD);

        return mod;
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
     * True if terrain between observer and target blocks vision.
     *
     * Ground occludes when it rises a full tier above the straight SIGHT LINE
     * between the two units — the line running from the observer's ground height
     * to the target's, not a flat ceiling over both. Consequences:
     *   - high ground still sees across and down onto lower terrain freely,
     *   - a unit ON a hill is still visible from lower ground (its own slope
     *     lies on the line, not above it),
     *   - a unit in a hollow is now hemmed in by the hollow's own rim, because
     *     the line out of it passes low over that rim.
     *
     * The old rule used a constant ceiling of max(hObs,hTgt)+1, which asked the
     * terrain to out-top BOTH ends. Nothing between a valley (tier 1) and the
     * plain above it (tier 3) could ever reach tier 4, so a unit in a hollow saw
     * out of it as freely as one standing in the open — measured on Zhovti Vody:
     * 57.7% of targets in sight range visible from lowlands vs 58.0% from plains,
     * and 100% of highland targets. With the sight line those become 19.1% and
     * 28.6%. See {@link TerrainQuery#ELEVATION_BLOCK_MARGIN_TIERS}.
     *
     * Sampling skips both endpoints' own tiles so standing on a peak never
     * self-occludes. The rule is symmetric: the same line serves both directions.
     */
    private boolean isSightBlockedByElevation(Unit observer, Unit target) {
        if (terrain == null) return false;

        int hObs = terrain.heightF(observer.x, observer.y);
        int hTgt = terrain.heightF(target.x, target.y);

        return terrain.hasGroundAboveOnSegmentF(observer.x, observer.y,
                                                target.x, target.y, hObs, hTgt);
    }

    private boolean isForest(long rawX, long rawY) {
        return terrain != null && terrain.isForestF(rawX, rawY);
    }

    private TerrainType elevationAt(long rawX, long rawY) {
        return terrain != null ? terrain.elevationF(rawX, rawY) : TerrainType.NONE;
    }
}
