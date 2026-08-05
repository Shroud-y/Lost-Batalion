package io.jababa.lost_batalion.debug;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.units.Unit;

/**
 * Прапорці налагоджувального ПОГЛЯДУ на матч. Вмикає {@link DevConsole}.
 *
 * <h3>Головне правило цього класу</h3>
 * <b>Нічого звідси не читає симуляція.</b> Усе тут — про те, що МАЛЮЄТЬСЯ, і
 * лише {@link #timeScale} про те, як швидко крутиться годинник. Якби
 * {@link #revealAll} читав {@code CombatManager}, спостереження за матчем
 * змінювало б сам матч: бот почав би стріляти по тому, чого не бачить, і все
 * побачене було б про іншу гру. Гейти видимості в грі стоять у двох різних
 * місцях — рендер ({@code UnitRenderer}, {@code Minimap},
 * {@code TerrainIndicatorRenderer}) і симуляція ({@code CombatManager},
 * {@code StateChecksum}); чіпати можна ТІЛЬКИ перші.
 *
 * <h3>Чому статичні поля</h3>
 * Читачі — окремі рендерери, які створюються в різних місцях і нічого не знають
 * одне про одного; тягнути посилання на консоль через п'ять конструкторів
 * заради двох прапорців було б гірше за глобальний стан. Стан налагоджувальний,
 * у знімок не входить і на матч не впливає.
 */
public final class DevView {

    private DevView() {}

    /** Показувати всіх юнітів, зокрема тих, кого гравець не бачить. */
    public static boolean revealAll;

    /** Малювати наміри ботів: ціль, збірний пункт, стан. */
    public static boolean showAiIntent;

    /**
     * Множник темпу симуляції.
     *
     * <p>Множить лише {@code delta}, що йде в {@code MatchRunner.update} — сам
     * крок тіку лишається 25 мс, тобто симуляція від цього не міняється, просто
     * тіків за секунду виходить більше. Дозволено ТІЛЬКИ в одиночній грі: у
     * мережевому матчі другий бік крутить свій годинник у реальному часі.
     */
    public static float timeScale = 1f;

    /**
     * Чи малювати цього юніта.
     *
     * <p>Єдина точка, через яку рендер питає видимість, коли ввімкнено
     * спостереження. Свої юніти видно завжди — це вже враховано в
     * {@link Unit#isVisibleTo(Team)}.
     */
    public static boolean visible(Unit unit, Team viewer) {
        return revealAll || unit.isVisibleTo(viewer);
    }

    /** Скинути все — матч скінчився або гра вийшла в меню. */
    public static void reset() {
        revealAll    = false;
        showAiIntent = false;
        timeScale    = 1f;
    }
}
