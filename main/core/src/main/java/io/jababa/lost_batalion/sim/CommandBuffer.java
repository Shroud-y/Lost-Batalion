package io.jababa.lost_batalion.sim;

import io.jababa.lost_batalion.net.commands.GameCommand;
import io.jababa.lost_batalion.net.messages.TickCommands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Накази, розкладені по тіках виконання.
 *
 * <p>Дві половини однієї механіки input delay:
 * <ul>
 *   <li>{@link #addLocal(GameCommand)} збирає те, що гравець натиснув ЗАРАЗ;
 *       воно поїде на тік {@code поточний + INPUT_DELAY};</li>
 *   <li>{@link #receive(TickCommands)} складає те, що прийшло по мережі, у
 *       комірку відповідного тіку.</li>
 * </ul>
 *
 * <p>Тік виконується лише коли {@link #isReady(int, int[])} каже, що для нього
 * є повідомлення від УСІХ гравців. Порожнє повідомлення теж рахується — воно
 * означає «я на цьому тіку мовчу». Без цієї умови клієнт із швидшим з'єднанням
 * тікав би вперед і бачив би іншу гру.
 *
 * <p>Клас однопотоковий: усе відбувається в потоці рендеру, куди мережеві
 * події доставляє {@code MatchTransport.pump}.
 */
public class CommandBuffer {

    /** tick → (playerId → накази цього гравця на цей тік). */
    private final Map<Integer, Map<Integer, List<GameCommand>>> byTick = new HashMap<>();

    /** Накази, віддані локально й ще не відправлені. */
    private final ArrayList<GameCommand> pendingLocal = new ArrayList<>();

    /**
     * Поточне покоління симуляції. Зростає після кожного ресинку; повідомлення
     * з іншим поколінням — це відлуння того, що летіло в момент розбіжності,
     * і брати його не можна.
     */
    private int generation;

    /**
     * Найбільший тік, який уже виконано. Усе, що приходить на нього або
     * раніше, — запізніла копія.
     *
     * <p>Потрібне відколи транспорт шле накази НАДЛИШКОВО (кілька копій на
     * різних тіках, щоб одна втрачена датаграма не спиняла матч). Без цієї
     * межі копія, що прийшла після виконання свого тіку, знову створювала б
     * комірку в {@code byTick} — і мапа росла б до кінця матчу, бо забрати
     * ту комірку вже нікому.
     */
    private int lastExecutedTick = -1;

    public int getGeneration() { return generation; }

    /**
     * Почати нове покоління: старі накази й буфер більше не чинні.
     * Викликається рівно після застосування знімка.
     */
    public void beginGeneration(int newGeneration) {
        generation = newGeneration;
        byTick.clear();
        pendingLocal.clear();
        // Нове покоління починає нумерацію тіків заново заповнюватись — межа
        // старого покоління до неї не застосовна.
        lastExecutedTick = -1;
        // Вибулі гравці НЕ забуваються: ресинк лікує розбіжність стану, а не
        // повертає в матч того, хто вийшов.
    }

    // ── Локальний ввід ────────────────────────────────────────────────────

    public void addLocal(GameCommand command) {
        pendingLocal.add(command);
    }

    public boolean hasPendingLocal() { return !pendingLocal.isEmpty(); }

    /**
     * Забрати накопичений ввід і оформити його як повідомлення на тік
     * {@code executeTick}. Викликається кожен тік, навіть коли накопичилось
     * порожньо — мовчання теж треба підтвердити.
     */
    public TickCommands flushLocal(int executeTick, int localPlayerId) {
        ArrayList<GameCommand> batch = new ArrayList<>(pendingLocal);
        pendingLocal.clear();
        return new TickCommands(executeTick, localPlayerId, batch, generation);
    }

    // ── Прийом ────────────────────────────────────────────────────────────

    /**
     * Покласти прийняте повідомлення в комірку його тіку.
     *
     * <p>Повторне повідомлення від того самого гравця на той самий тік
     * ігнорується: воно означає збій ретрансляції, і взяти обидва — значить
     * виконати наказ двічі лише в того, кому дубль долетів.
     */
    public void receive(TickCommands message) {
        if (message == null) return;
        // Запізніла надлишкова копія вже виконаного тіку. Мовчки викидаємо:
        // на стан вона вплинути не може, а комірку створила б назавжди.
        if (message.tick <= lastExecutedTick) return;
        // Відлуння минулого покоління: ті самі тіки вже заповнюються заново
        // після ресинку, і взяти старе означало б розійтись знову.
        if (message.generation != generation) return;
        Map<Integer, List<GameCommand>> slot =
            byTick.computeIfAbsent(message.tick, t -> new HashMap<>());
        if (slot.containsKey(message.playerId)) return;
        slot.put(message.playerId,
                 message.commands == null ? new ArrayList<>() : message.commands);
    }

    // ── Вибулі гравці ─────────────────────────────────────────────────────

    /**
     * playerId → тік, з якого його наказів більше не чекають.
     *
     * <p>Тік призначає хост і розсилає всім. Виключити гравця «прямо зараз»
     * не можна: якщо один клієнт перестане чекати на тіку 500, а другий на 503,
     * вони виконають різну кількість тіків із різним набором наказів. Спільне
     * число — єдиний спосіб зробити це однаково.
     */
    private final Map<Integer, Integer> dropEffectiveTick = new HashMap<>();

    public void dropPlayer(int playerId, int effectiveTick) {
        dropEffectiveTick.put(playerId, effectiveTick);
    }

    public boolean isDropped(int playerId) { return dropEffectiveTick.containsKey(playerId); }

    /** Найбільший тік, на який є накази цього гравця; -1 якщо немає жодного. */
    public int lastTickFor(int playerId) {
        int max = -1;
        for (Map.Entry<Integer, Map<Integer, List<GameCommand>>> e : byTick.entrySet()) {
            if (e.getValue().containsKey(playerId) && e.getKey() > max) max = e.getKey();
        }
        return max;
    }

    /**
     * Чи гравець зобов'язаний прислати накази на цей тік.
     *
     * <p>Після {@code effectiveTick} — ні, його взагалі більше немає. ДО нього —
     * теж ні, якщо накази так і не долетіли: гравець зник, і чекати більше
     * нема на що, прогалина трактується як мовчання. Це безпечно саме тому, що
     * все йде через хоста по TCP: усе, що хост устиг ретранслювати, доїхало до
     * всіх РАНІШЕ за повідомлення про вибуття, тож прогалини в усіх однакові.
     */
    private boolean stillExpected(int playerId, int tick, Map<Integer, List<GameCommand>> slot) {
        Integer effective = dropEffectiveTick.get(playerId);
        if (effective == null) return true;
        if (tick >= effective) return false;
        return slot != null && slot.containsKey(playerId);
    }

    /** Чи прийшли повідомлення від усіх, кого ще чекають, на цей тік. */
    public boolean isReady(int tick, int[] playerIds) {
        Map<Integer, List<GameCommand>> slot = byTick.get(tick);
        for (int i = 0; i < playerIds.length; i++) {
            int pid = playerIds[i];
            if (dropEffectiveTick.containsKey(pid)) {
                if (!stillExpected(pid, tick, slot)) continue;   // вибулий — не чекаємо
            }
            if (slot == null || !slot.containsKey(pid)) return false;
        }
        return true;
    }

    /** Хто саме ще мовчить — для індикатора «чекаємо на гравця N». */
    public int firstMissingPlayer(int tick, int[] playerIds) {
        Map<Integer, List<GameCommand>> slot = byTick.get(tick);
        for (int i = 0; i < playerIds.length; i++) {
            int pid = playerIds[i];
            if (dropEffectiveTick.containsKey(pid) && !stillExpected(pid, tick, slot)) continue;
            if (slot == null || !slot.containsKey(pid)) return pid;
        }
        return -1;
    }

    /**
     * Накази на цей тік у порядку зростання playerId і забрати комірку.
     *
     * <p>Порядок фіксований саме тут: якби команди виконувались у порядку
     * прибуття, два накази на одного юніта лягли б у різній послідовності
     * у хоста і в гостя — і це десинхрон.
     */
    public List<GameCommand> take(int tick, int[] playerIds) {
        if (tick > lastExecutedTick) lastExecutedTick = tick;
        Map<Integer, List<GameCommand>> slot = byTick.remove(tick);
        List<GameCommand> ordered = new ArrayList<>();
        if (slot == null) return ordered;

        int[] sorted = playerIds.clone();
        java.util.Arrays.sort(sorted);
        for (int i = 0; i < sorted.length; i++) {
            List<GameCommand> forPlayer = slot.get(sorted[i]);
            if (forPlayer != null) ordered.addAll(forPlayer);
        }
        return ordered;
    }

    /** Скільки тіків уперед уже забуферизовано — груба міра запасу проти лагу. */
    public int bufferedTicksAhead(int currentTick) {
        int max = currentTick;
        for (Integer tick : byTick.keySet()) if (tick > max) max = tick;
        return max - currentTick;
    }

    public void clear() {
        byTick.clear();
        pendingLocal.clear();
        dropEffectiveTick.clear();
        lastExecutedTick = -1;
    }
}
