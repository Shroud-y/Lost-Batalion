package io.jababa.lost_batalion.net.messages;

import io.jababa.lost_batalion.net.commands.GameCommand;

import java.util.ArrayList;

/**
 * Команди одного гравця на один конкретний тік — основне повідомлення матчу.
 *
 * <p>Надсилається КОЖЕН тік, навіть коли гравець нічого не робив: порожній
 * список — це підтвердження «я на цьому тіку мовчу». Без нього решта не змогла
 * б відрізнити бездіяльність від лагу, і симуляція чекала б вічно.
 *
 * <p>Маршрут: клієнт → хост → усі (включно з автором, щоб він виконував наказ
 * рівно тоді ж, коли інші, а не раніше).
 */
public class TickCommands {

    /** Тік, НА ЯКОМУ ці команди виконуються (вже з урахуванням input delay). */
    public int tick;
    public int playerId;

    /**
     * Покоління симуляції: зростає на одиницю після кожного ресинку.
     *
     * <p>Без нього ресинк ламався б найпідступнішим чином. У момент зупинки на
     * десинхроні в дорозі вже летять накази на кілька наступних тіків. Після
     * відновлення ті самі тіки заповнюються заново — і клієнт, до якого старе
     * повідомлення долетіло раніше, лишив би старе, а другий — нове. Обидва
     * впевнені, що все гаразд, а стан знову різний. Тому повідомлення з чужого
     * покоління просто відкидаються.
     */
    public int generation;

    public ArrayList<GameCommand> commands = new ArrayList<>();

    public TickCommands() {}

    public TickCommands(int tick, int playerId, ArrayList<GameCommand> commands) {
        this(tick, playerId, commands, 0);
    }

    public TickCommands(int tick, int playerId, ArrayList<GameCommand> commands, int generation) {
        this.tick       = tick;
        this.playerId   = playerId;
        this.commands   = commands;
        this.generation = generation;
    }

    public boolean isEmpty() { return commands == null || commands.isEmpty(); }
}
