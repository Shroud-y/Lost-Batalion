package io.jababa.lost_batalion.visibility;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainMaskManager;
import io.jababa.lost_batalion.terrain.TerrainType;
import io.jababa.lost_batalion.units.Unit;

/**
 * Система видимості / туман війни.
 *
 * Алгоритм (виконується кожен кадр):
 *  1. Для кожного ВОРОЖОГО юніта обнуляємо visibleToPlayer = false.
 *  2. Для кожного ГРАВЦЕВОГО юніта перебираємо ворогів:
 *       effectiveSight = observer.sightRange * terrainSightMod(observer)
 *       effectiveStealth = target.stealthRating * terrainStealthMod(target)
 *       видимий якщо dist < effectiveSight * (1 - effectiveStealth)
 *  3. Якщо хоча б один ГРАВЦЕВИЙ юніт бачить ворога → visibleToPlayer = true.
 *
 * Модифікатори місцевості (ліс):
 *   - Спостерігач у лісі: його дальність зору × FOREST_SIGHT_PENALTY   (0.55)
 *   - Ціль у лісі:        її скритність    × FOREST_STEALTH_BONUS      (1.8)
 */
public class VisibilitySystem {

    /** Штраф до зору СПОСТЕРІГАЧА, якщо він сам у лісі */
    private static final float FOREST_SIGHT_PENALTY  = 0.55f;

    /** Множник скритності ЦІЛІ, якщо вона у лісі */
    private static final float FOREST_STEALTH_BONUS  = 1.8f;

    /** Максимум скритності після всіх модифікаторів (не дозволяємо стати абсолютно невидимим) */
    private static final float MAX_EFFECTIVE_STEALTH = 0.90f;

    private final TerrainMaskManager terrain;

    public VisibilitySystem(TerrainMaskManager terrain) {
        this.terrain = terrain;
    }

    /**
     * Оновлює поле visibleToPlayer для всіх ворогів.
     * Викликати один раз на кадр перед рендером.
     *
     * @param allUnits усі юніти гри (і свої, і ворожі)
     */
    public void update(Array<Unit> allUnits) {
        // 1. Скидаємо видимість усіх ворогів
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u.team != Team.PLAYER) {
                u.visibleToPlayer = false;
            }
        }

        // 2. Кожен юніт гравця «освітлює» ворогів у своєму радіусі
        for (int i = 0; i < allUnits.size; i++) {
            Unit observer = allUnits.get(i);
            if (observer.team != Team.PLAYER || !observer.alive) continue;

            float effectiveSight = observer.sightRange * sightMod(observer);

            for (int j = 0; j < allUnits.size; j++) {
                Unit target = allUnits.get(j);
                if (target.team == Team.PLAYER || !target.alive) continue;
                if (target.visibleToPlayer) continue; // вже видимий — пропускаємо

                float effectiveStealth = Math.min(
                    target.stealthRating * stealthMod(target),
                    MAX_EFFECTIVE_STEALTH
                );

                float detectionRange = effectiveSight * (1f - effectiveStealth);

                float dist = observer.position.dst(target.position);
                if (dist <= detectionRange) {
                    target.visibleToPlayer = true;
                }
            }
        }
    }

    /**
     * Чи видна позиція world-координат хоча б одним ГРАВЦЕВИМ юнітом.
     * Використовується FogOfWarRenderer для перевірки конкретної клітинки.
     *
     * @param wx, wy  world-координати точки
     * @param allUnits усі юніти
     * @return true якщо точка в зоні зору гравця
     */
    public boolean isPointVisible(float wx, float wy, Array<Unit> allUnits) {
        for (int i = 0; i < allUnits.size; i++) {
            Unit observer = allUnits.get(i);
            if (observer.team != Team.PLAYER || !observer.alive) continue;

            float effectiveSight = observer.sightRange * sightMod(observer);
            float dist = observer.position.dst(wx, wy);
            if (dist <= effectiveSight) return true;
        }
        return false;
    }

    // ── Приватні методи ────────────────────────────────────────────────────

    /** Модифікатор зору для спостерігача залежно від його місцевості */
    /** Модифікатор зору для спостерігача: ліс заважає, висота допомагає */
    private float sightMod(Unit observer) {
        if (terrain == null) return 1f;

        float x = observer.position.x;
        float y = observer.position.y;

        // 1. Штраф за ліс (якщо юніт у гущавині, він бачить гірше)
        float penalty = terrain.isForestAt(x, y) ? FOREST_SIGHT_PENALTY : 1f;

        // 2. Бонус за висоту (з пагорба видно набагато далі)
        float elevationBonus = 1f;
        TerrainType elevation = terrain.getElevationAt(x, y);

        if (elevation == TerrainType.HIGHLANDS) {
            elevationBonus = 1.35f; // +35% до дальності огляду
        } else if (elevation == TerrainType.LOWLANDS) {
            elevationBonus = 0.85f; // -15% у низині
        }

        return penalty * elevationBonus;
    }

    /** Модифікатор скритності для цілі: ліс ховає, пагорб демаскує */
    private float stealthMod(Unit target) {
        if (terrain == null) return 1f;

        float x = target.position.x;
        float y = target.position.y;

        // 1. Бонус від лісу (головний фактор маскування)
        float forestBonus = terrain.isForestAt(x, y) ? FOREST_STEALTH_BONUS : 1f;

        // 2. Штраф за висоту (юніта на пагорбі легше помітити здалеку)
        float elevationPenalty = 1f;
        TerrainType elevation = terrain.getElevationAt(x, y);

        if (elevation == TerrainType.HIGHLANDS) {
            elevationPenalty = 1.25f; // На пагорбі юніт на 25% помітніший
        }

        // Повертаємо підсумковий множник
        // (Чим вище значення, тим важче помітити юніта у твоїй формулі VisibilitySystem)
        return forestBonus / elevationPenalty;
    }
}
