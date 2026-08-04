package io.jababa.lost_batalion.screens.scenario;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.capture.CaptureZone;

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
            "Жовті Води",
            "Балки, переправи й лісосмуги. Хто тримає висоти — бачить далі "
                + "і б'є сильніше.",
            "scenarios/Zhovty_Vodu.png",
            "scenarios/Zhovty_Vodu_mask.png",        // маска лісу
            "scenarios/Zhovty_Vodu_terrain_mask.png", // маска топографії

            // Зони захоплення. Вершини у світових пікселях (Y вгору), обхід за
            // годинниковою; чотирикутник має лишатись ОПУКЛИМ — перевірка
            // входження працює за знаком векторного добутку.
            //
            // Форми НЕ прямокутні навмисно: межа села — це околиця, а не
            // ділянка під забудову, і рівні кути читались як службова рамка.
            // Кожна зона перевірена на опуклість, на те, що накриває пляму
            // VILLAGE цілком, і на відсутність перетинів із сусідніми.

            // Хутір біля південної переправи. Лежить упритул до нижнього краю
            // карти — село доходить до y=1439 при висоті 1440, тому нижня межа
            // стоїть рівно на 1440.
            // Сторони 151/90/149/73, кути 75/100/73/112.
            new CaptureZone("A",
                1230, 1440,
                1079, 1440,
                1063, 1351,
                1211, 1369),

            // Найбільше село, у розвилці доріг на схід від центру.
            // Сторони 214/118/209/110, кути 107/71/107/75.
            new CaptureZone("B",
                 974,  567,
                1123,  720,
                1017,  771,
                 876,  617),

            // Західне село під лісосмугою.
            // Сторони 175/120/138/71, кути 103/61/103/93.
            new CaptureZone("C",
                 493,  734,
                 318,  735,
                 375,  629,
                 508,  665)
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
