package io.jababa.lost_batalion.ai;

import io.jababa.lost_batalion.net.commands.GameCommand;
import io.jababa.lost_batalion.net.messages.TickCommands;

import java.util.ArrayList;
import java.util.List;

/**
 * Супротивник-бот у вигляді ПІРА, а не коду симуляції.
 *
 * <h3>Чому саме так</h3>
 * {@code GameSimulation.tick()} цілочисельний і входить у checksum. Евристика,
 * написана всередині нього, мусила б уся лягти на {@code Fixed} і потрапити в
 * хеш — тобто кожне «а спробуймо ось так» коштувало б перевірки на десинк.
 * Натомість {@code CommandContext} і без того ЄДИНИЙ канал, яким стан узагалі
 * змінюється. Тому бот сидить іззовні й віддає ті самі накази, що й людина:
 * всередині себе він вільний (float, колекції, будь-що), бо реплікується не
 * його думка, а її результат.
 *
 * <p>Наслідок, який дістається задарма: мережевий матч проти бота колись
 * запрацює без переробок — хост володітиме ботом, і його {@link TickCommands}
 * розійдуться, як від будь-якого іншого учасника.
 *
 * <h3>Ритм</h3>
 * Повідомлення від бота народжується РАЗОМ із флешем локального гравця (див.
 * {@code LocalMatchTransport.sendCommands}) і на той самий тік. Це не деталь
 * реалізації, а те, на чому тримається весь підхід: {@code MatchRunner} чекає
 * наказів від КОЖНОГО учасника, і тік без повідомлення від бота зупинив би матч
 * намертво. Прив'язка до чужого флешу гарантує рівно одне повідомлення на тік,
 * без власного лічильника, який міг би збитись.
 *
 * <p>Думає бот при цьому рідше, ніж шле: {@link #DECISION_PERIOD_TICKS}. Решту
 * тіків повідомлення виходить порожнім — і це нормально, мовчання теж наказ.
 *
 * <h3>Checksum</h3>
 * Бот НЕ шле хешів стану, і це навмисно. Він не окрема симуляція, а джерело
 * наказів у тій самій; його «згода з собою» була б виставою.
 * {@code ChecksumLedger} порівнює лише коли зібрались усі учасники, тож
 * відсутній хеш просто не запускає звірку — хибного десинку не буде.
 */
public class BotPlayer {

    /** Бот завжди грає за другого. Перший — людина за цим комп'ютером. */
    public static final int PLAYER_ID = 1;

    /**
     * Запасний період, якщо мозок свого не називає.
     *
     * <p>Щотіку думати не має сенсу: наказ однаково доїде з
     * {@code INPUT_DELAY_TICKS} затримкою, а обстановка за 25 мс не міняється.
     * Справжній період питаємо в мозку — він залежить від рівня гри
     * ({@link BotBrain#decisionPeriodTicks()}).
     */
    public static final int DECISION_PERIOD_TICKS = 4;

    /**
     * Не {@code final}: налагоджувальна консоль міняє рівень супротивника
     * посеред матчу, а рівень — це властивість мозку. Підміна безпечна саме
     * тому, що ритм тримає гравець, а не мозок: новий мозок відповість на
     * наступний же запит, і жодного тіку без повідомлення не буде.
     */
    private BotBrain brain;

    public void setBrain(BotBrain next) { if (next != null) brain = next; }
    public BotBrain getBrain() { return brain; }

    /**
     * Тік останнього роздумування.
     *
     * <p>Стартове значення саме {@code -DECISION_PERIOD_TICKS}, а НЕ
     * {@code Integer.MIN_VALUE}: різниця {@code executeTick - lastDecisionTick}
     * при мінімумі переповнюється в мінус, умова не спрацьовує ніколи, і бот
     * мовчить увесь матч, не подавши жодної ознаки поламаності.
     */
    private int lastDecisionTick = -DECISION_PERIOD_TICKS;

    public BotPlayer(BotBrain brain) {
        this.brain = brain;
    }

    /**
     * Наказ бота на вказаний тік.
     *
     * <p>Ніколи не повертає {@code null}: відсутність повідомлення для
     * {@code MatchRunner} означає не «нічого не роблю», а «ще не надіслав», і
     * матч став би чекати.
     *
     * @param executeTick тік ВИКОНАННЯ — той самий, на який щойно відклався
     *                    наказ людини
     * @param generation  покоління буфера; чуже покоління буфер відкидає мовчки
     */
    public TickCommands commandsFor(int executeTick, int generation) {
        List<GameCommand> decided = null;

        if (executeTick - lastDecisionTick >= Math.max(1, brain.decisionPeriodTicks())) {
            lastDecisionTick = executeTick;
            decided = brain.think(executeTick);
        }

        // Кожне повідомлення отримує ВЛАСНИЙ список, навіть порожній: воно
        // лягає в чергу й живе довше за цей виклик, а спільний екземпляр
        // зв'язав би між собою накази різних тіків.
        ArrayList<GameCommand> batch = decided == null
                                     ? new ArrayList<>()
                                     : new ArrayList<>(decided);
        for (int i = 0; i < batch.size(); i++) batch.get(i).playerId = PLAYER_ID;

        return new TickCommands(executeTick, PLAYER_ID, batch, generation);
    }
}
