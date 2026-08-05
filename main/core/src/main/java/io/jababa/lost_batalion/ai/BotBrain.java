package io.jababa.lost_batalion.ai;

import io.jababa.lost_batalion.net.commands.GameCommand;

import java.util.List;

/**
 * Те, що бот вирішує зробити. Відокремлене від {@link BotPlayer} навмисно:
 * той відповідає за РИТМ (одне повідомлення на тік, хоч би й порожнє), а цей —
 * за зміст. Зламати ритм означає зупинити матч, зламати зміст — програти його,
 * і плутати ці дві відповідальності не варто.
 */
public interface BotBrain {

    /**
     * @param executeTick тік, на якому накази виконаються
     * @return накази або {@code null} / порожній список, якщо робити нічого
     */
    List<GameCommand> think(int executeTick);

    /**
     * Через скільки тіків питати знову.
     *
     * <p>Належить мозку, а не {@link BotPlayer}: це властивість рівня гри, а не
     * каналу. Коли період був константою в гравці, поле рівня
     * {@code thinkPeriodTicks} не читав ніхто — три різні рівні думали з
     * однаковою швидкістю, і різниці між ними не було видно.
     */
    default int decisionPeriodTicks() { return 4; }
}
