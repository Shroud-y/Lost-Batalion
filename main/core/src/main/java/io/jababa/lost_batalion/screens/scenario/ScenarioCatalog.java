package io.jababa.lost_batalion.screens.scenario;

import com.badlogic.gdx.utils.Array;

/**
 * Єдиний перелік сценаріїв.
 *
 * <p>Винесений з {@link ScenarioScreen} тому, що карту тепер обирає не лише
 * одиночна гра: хост задає її при створенні лоббі, а клієнт мусить знайти той
 * самий сценарій за id, який прийшов по мережі. Два незалежні списки в двох
 * місцях рано чи пізно розійшлися б, і клієнт завантажив би іншу карту —
 * з іншими масками місцевості, тобто з іншою симуляцією.
 */
public final class ScenarioCatalog {

    private ScenarioCatalog() {}

    /** Порядок фіксований: індекс використовується для «наступна карта» в лоббі. */
    public static Array<ScenarioCard> all() {
        Array<ScenarioCard> list = new Array<>();
        list.add(new ScenarioCard(
            "zhovti_vody",
            "Zhovti Vody",
            "Coming soon...",
            "scenarios/Zhovty_Vodu.png",
            "scenarios/Zhovty_Vodu_mask.png",        // маска лісу
            "scenarios/Zhovty_Vodu_terrain_mask.png" // маска топографії
        ));
        return list;
    }

    /** @return сценарій за id або перший зі списку, якщо id невідомий */
    public static ScenarioCard byId(String id) {
        Array<ScenarioCard> list = all();
        if (id != null) {
            for (int i = 0; i < list.size; i++) {
                if (id.equals(list.get(i).id)) return list.get(i);
            }
        }
        return list.first();
    }

    /** Наступний сценарій по колу — для перемикача карти в лоббі. */
    public static ScenarioCard next(String currentId) {
        Array<ScenarioCard> list = all();
        for (int i = 0; i < list.size; i++) {
            if (list.get(i).id.equals(currentId)) return list.get((i + 1) % list.size);
        }
        return list.first();
    }
}
