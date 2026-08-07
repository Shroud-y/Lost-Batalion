package io.jababa.lost_batalion.sim;

import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.NetLog;
import io.jababa.lost_batalion.net.api.MatchTransport;
import io.jababa.lost_batalion.net.commands.GameCommand;
import io.jababa.lost_batalion.net.messages.DesyncAlert;
import io.jababa.lost_batalion.net.messages.PlayerDropped;
import io.jababa.lost_batalion.net.messages.ResumeMatch;
import io.jababa.lost_batalion.net.messages.ResyncAck;
import io.jababa.lost_batalion.net.messages.ResyncSnapshot;
import io.jababa.lost_batalion.net.messages.TickChecksum;
import io.jababa.lost_batalion.net.messages.TickCommands;

import java.util.ArrayList;
import java.util.List;

/**
 * Lockstep-цикл: годинник, буфер команд, симуляція і звірка хешів.
 *
 * <h3>Як це працює</h3>
 * Наказ, відданий під час тіку {@code N}, не виконується одразу. Він їде по
 * мережі з поміткою «виконати на тіку {@code N + INPUT_DELAY}», і рівно на
 * цьому тіку його виконують ВСІ — і автор теж. Автор чекає нарівні з іншими
 * навмисно: якби він застосував наказ у себе одразу, його гра пішла б на два
 * тіки попереду чужої, і жодна checksum потім не зійшлася б.
 *
 * <p>На практиці від кліку до руху проходить {@code INPUT_DELAY_TICKS + 1}
 * тіків, а не рівно {@code INPUT_DELAY_TICKS}: партія тіку, який щойно
 * виконався, вже закрита, тож ввід із проміжку між тіками потрапляє в наступну.
 * При 40 Гц це 75 мс — виміряно, не оцінено.
 *
 * <p>Тік не виконується, доки не прийшли повідомлення від усіх гравців. Це і є
 * «lock» у lockstep: швидший клієнт чекає повільнішого, а не тікає вперед.
 * Порожнє повідомлення теж рахується — воно означає «я цього тіку мовчу».
 *
 * <h3>Розігрів</h3>
 * Тіки {@code 1..INPUT_DELAY} нікому нема з чого заповнити: гра ще не почалась,
 * коли їхні накази мали б відправлятись. Тому кожен клієнт на старті надсилає
 * на ці тіки порожні повідомлення від себе — інакше матч завис би на першому ж
 * тіку, чекаючи наказів, яких ніхто не віддавав.
 *
 * <h3>Звірка</h3>
 * Раз на {@code NetConfig.getChecksumIntervalTicks()} тіків рахується хеш
 * повного стану і розсилається всім. Розбіжність означає, що далі грати немає
 * сенсу: симуляції вже різні й розходитимуться лише більше. Тому цикл
 * зупиняється тієї ж миті — краще завмерти з чесним повідомленням, ніж дати
 * двом гравцям ще годину бачити різні бої.
 *
 * <p>Про рендер клас не знає нічого: він віддає {@link #getRenderAlpha()} і на
 * цьому все. Викликати {@link #update(float)} треба раз на кадр.
 */
public class MatchRunner implements MatchTransport.Listener {

    private final GameSimulation sim;
    private final MatchTransport transport;
    private final LockstepClock  clock  = new LockstepClock();
    private final CommandBuffer  buffer = new CommandBuffer();
    private final ChecksumLedger ledger = new ChecksumLedger();

    private final int   localPlayerId;
    private final int[] playerIds;
    private final boolean host;

    /** Як часто рахувати хеш. Фіксується на старті — змінювати його посеред матчу не можна. */
    private final int checksumInterval;

    /** Буфер під компоненти хеша: рахується раз на секунду, алокувати щоразу нема потреби. */
    private final long[] checksumComponents = new long[StateChecksum.COMPONENTS];

    /**
     * Тіки, які ще треба виконати. Годинник наливає сюди, цикл вичерпує.
     *
     * <p>Окрема змінна, а не безпосереднє використання результату годинника,
     * саме через очікування: коли тік виконати не можна, борг мусить зберегтись
     * до наступного кадру. Інакше час, витрачений на чекання суперника, просто
     * зникав би, і після відновлення гра йшла б повільніше за реальну.
     */
    private int pendingTicks;

    /**
     * Від якої тривалості затримка потрапляє в лог. 80 мс — приблизно межа, з
     * якої око бачить ривок; дрібніші коливання мережі писати щосекунди немає
     * сенсу.
     */
    private static final float STALL_LOG_MILLIS = 80f;

    /** Чи цього кадру довелось зупинитись через відсутні команди. */
    private boolean waiting;
    /** Номер гравця, чиїх наказів бракує; -1 коли все гаразд. */
    private int waitingForPlayer = -1;
    /** Скільки кадрів поспіль триває очікування — щоб не блимати підказкою. */
    private int waitingFrames;
    /**
     * Скільки МІЛІСЕКУНД триває очікування. Кадри для цього не годяться:
     * на 30 FPS і на 144 та сама пауза дала б різні числа, а поріг «гравець
     * гальмує» — величина в реальному часі.
     */
    private float waitingMillis;

    private boolean disconnected;
    private String  disconnectReason;

    private float renderAlpha;

    public MatchRunner(GameSimulation sim, MatchTransport transport) {
        this.sim       = sim;
        this.transport = transport;
        this.localPlayerId = transport.getLocalPlayerId();
        this.playerIds     = transport.getPlayerIds().clone();
        this.host          = transport.isHost();
        this.checksumInterval = Math.max(1, NetConfig.getChecksumIntervalTicks());

        // Затримка вводу тепер різна для різних мереж, тож вона мусить бути
        // видимою: з неї починається розбір будь-якої скарги на «гру ривками».
        NetLog.info("Матч: гравець " + localPlayerId + " з " + playerIds.length
            + ", затримка вводу " + NetConfig.getInputDelayTicks() + " тіків ("
            + (NetConfig.getInputDelayTicks() * TickRate.TICK_MILLIS) + " мс)");

        sendWarmup();
    }

    /**
     * Порожні повідомлення на тіки розігріву. Надсилає кожен клієнт за себе —
     * підставити їх локально за всіх не можна, бо тоді гравець, який зайшов у
     * матч на секунду пізніше, отримав би тіки, яких у нього не було.
     */
    private void sendWarmup() {
        for (int tick = 1; tick <= NetConfig.getInputDelayTicks(); tick++) {
            transport.sendCommands(
                new TickCommands(tick, localPlayerId, new ArrayList<>(), generation));
        }
    }

    // ── Кадр ──────────────────────────────────────────────────────────────

    /**
     * @param delta час кадру в секундах
     * @return скільки тіків виконано цього кадру
     */
    public int update(float delta) {
        transport.pump(this);

        pendingTicks += clock.advance(delta);
        // Борг понад ліміт наздоганяння накопичувати немає сенсу: він усе одно
        // не буде відпрацьований, а після довгого очікування перетворився б на
        // хвилину прискореної гри.
        if (pendingTicks > TickRate.MAX_CATCHUP_TICKS) pendingTicks = TickRate.MAX_CATCHUP_TICKS;

        int executed = 0;
        waiting = false;

        // Під час ресинку симуляція стоїть. Інакше вона встигає піти вперед
        // від тіку, на якому знято знімок, і дозвіл продовжити приходить із
        // номером, який уже в минулому — розігрів лягає на виконані тіки, а
        // матч завмирає назавжди. У звичайному ході це не вилазило, бо ресинк
        // трапляється лише після десинхрону, коли цикл і так зупинений.
        while (pendingTicks > 0 && !disconnected && !isDesynced()
               && resyncPhase == ResyncPhase.IDLE) {
            int next = sim.getTickNumber() + 1;

            if (!buffer.isReady(next, playerIds)) {
                waiting = true;
                waitingForPlayer = buffer.firstMissingPlayer(next, playerIds);
                break;
            }

            // Вибуття армії — теж «перед кроком» і рівно на призначеному тіку.
            applyPendingRemovals(next);

            // Накази — перед кроком: віддані на цей тік, вони мусять вплинути
            // вже на рух цього ж тіку, і однаково в усіх.
            List<GameCommand> commands = buffer.take(next, playerIds);
            for (int i = 0; i < commands.size(); i++) commands.get(i).execute(sim);

            sim.tick();
            pendingTicks--;
            executed++;

            publishChecksumIfDue(next);

            // Свій ввід за цей кадр їде на тік із затримкою. Відправляється
            // щотіку, навіть порожнім: мовчання теж треба підтвердити, інакше
            // решта не відрізнить його від лагу.
            transport.sendCommands(
                buffer.flushLocal(next + NetConfig.getInputDelayTicks(), localPlayerId));
        }

        if (waiting) {
            waitingFrames++;
            waitingMillis += delta * 1000f;
        } else {
            // Затримка щойно скінчилась. Помітну — записати: саме з таких
            // рядків видно, чи ривок був поодиноким (загублена датаграма) чи
            // рівним (не вистачає затримки вводу для цього з'єднання).
            if (waitingMillis >= STALL_LOG_MILLIS) {
                NetLog.info(String.format(
                    "Затримка %.0f мс на тіку %d, чекали гравця %d",
                    waitingMillis, sim.getTickNumber() + 1, waitingForPlayer));
            }
            waitingFrames = 0;
            waitingMillis = 0f;
            waitingForPlayer = -1;
        }

        // Поки борг не вичерпаний, ми позаду реального часу — інтерполювати
        // нічого, наступний тік має статись негайно.
        renderAlpha = pendingTicks > 0 ? 1f : clock.getAlpha();
        return executed;
    }

    /**
     * Порахувати й розіслати хеш, якщо цей тік контрольний.
     *
     * <p>Хеш береться ПІСЛЯ кроку симуляції: він описує стан на кінець тіку,
     * і саме так його трактує приймач. Порахувати до кроку означало б, що двоє
     * порівнюють стани, зняті в різні моменти.
     */
    private void publishChecksumIfDue(int tick) {
        if (tick % checksumInterval != 0) return;

        long checksum = StateChecksum.compute(sim, checksumComponents);
        // Копія масиву: буфер перевикористовується наступного разу, а
        // повідомлення живе в черзі й у книзі хешів довше за цей виклик.
        long[] components = checksumComponents.clone();

        // Свій хеш записується в книгу ОДРАЗУ, а не чекає повернення з мережі.
        // Покладатись на відлуння означало б, що втрачена ретрансляція робить
        // нас сліпими до власного стану — саме тоді, коли звірка потрібна
        // найбільше. Повторний запис із мережі книга проігнорує.
        recordChecksum(tick, localPlayerId, checksum, components);

        transport.sendChecksum(new TickChecksum(tick, localPlayerId, checksum, components));
    }

    /** Спільний шлях для свого й чужого хеша: записати, звірити, оголосити. */
    private void recordChecksum(int tick, int playerId, long checksum, long[] components) {
        ChecksumLedger.Mismatch found =
            ledger.record(tick, playerId, checksum, components, localPlayerId, playerIds);
        if (found == null) return;

        NetLog.error(found + " (мій хеш " + Long.toHexString(found.localChecksum) + ")");

        // Оголошує хост: він арбітр і єдиний, чий вердикт однозначний. Гість
        // здебільшого вже зупинився сам, побачивши ту саму розбіжність, — але
        // якщо чужий хеш до нього не дійшов, оголошення лишається єдиним
        // способом дізнатись, що далі грати немає сенсу.
        if (host) {
            int[] ids = new int[found.disagreeingPlayers.size()];
            String[] nicks = new String[ids.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i]   = found.disagreeingPlayers.get(i);
                nicks[i] = "гравець #" + ids[i];
            }
            transport.sendDesyncAlert(new DesyncAlert(found.tick, ids, nicks, found.localChecksum));
        }
    }

    /** Наказ від локального гравця. Виконається через {@link TickRate#INPUT_DELAY_TICKS} тіків. */
    public void issue(GameCommand command) {
        if (disconnected || isDesynced()) return;
        command.playerId = localPlayerId;
        buffer.addLocal(command);
    }

    // ── MatchTransport.Listener ───────────────────────────────────────────

    @Override
    public void onTickCommands(TickCommands commands) {
        buffer.receive(commands);
    }

    @Override
    public void onChecksum(TickChecksum checksum) {
        recordChecksum(checksum.tick, checksum.playerId, checksum.checksum, checksum.components);
    }

    @Override
    public void onDesyncAlert(DesyncAlert alert) {
        ledger.adoptExternal(alert.tick, alert.desyncedPlayerIds, alert.hostChecksum);
        NetLog.error("Хост оголосив десинхрон на тіку " + alert.tick);
    }

    @Override
    public void onDisconnected(String reason) {
        disconnected     = true;
        disconnectReason = reason;
    }

    // ── Вибуття гравця ────────────────────────────────────────────────────

    /**
     * Запас тіків між оголошенням про вибуття і моментом, коли воно діє.
     *
     * <p>Повідомлення мусить встигнути доїхати до всіх РАНІШЕ за тік, на якому
     * спрацює. Двох тіків input delay мало б вистачити, але один зайвий тік
     * коштує 25 мс і рятує від гонки на повільному з'єднанні.
     */
    private static int dropAnnounceMargin() { return NetConfig.getInputDelayTicks() + 2; }

    /** Хто вибув: playerId → нік. Порядок не важливий, це для UI. */
    private final java.util.Map<Integer, String> dropped = new java.util.LinkedHashMap<>();

    /** Скільки гравців ще в матчі, включно з нами. */
    public int getActivePlayerCount() { return playerIds.length - dropped.size(); }

    public boolean isPlayerDropped(int playerId) { return dropped.containsKey(playerId); }

    /** Ніки вибулих — для повідомлення на екрані. */
    public java.util.Collection<String> getDroppedNicks() { return dropped.values(); }

    /**
     * Чи матч ЗАЛИШИВСЯ без суперників.
     *
     * <p>Саме «залишився», а не «їх немає»: в одиночній грі гравець теж один,
     * але це нормальний стан, а не привід показувати «суперник вийшов».
     */
    public boolean isAlone() {
        return playerIds.length > 1 && getActivePlayerCount() <= 1;
    }

    @Override
    public void onPlayerLost(int playerId) {
        if (!host || dropped.containsKey(playerId)) return;

        // Перепідключення в грі немає, тож обірване з'єднання — це остаточно.
        // Тримати матч у паузі «про всяк випадок» означало б змусити того, хто
        // лишився, дивитись у застиглий екран без жодного шансу на повернення.
        dropPlayer(playerId, "гравець #" + playerId, false);
    }

    /**
     * Хост: виключити гравця з матчу.
     *
     * <p>Тік вибуття береться з двох умов одразу: він мусить бути попереду
     * поточного (щоб оголошення встигло доїхати) і НЕ раніше за останній тік,
     * на який від цього гравця вже є накази (щоб не викинути те, що інші вже
     * прийняли й виконають).
     *
     * @param removeUnits чи прибирати його армію з поля; {@code false} лишає
     *                    її стояти, і той, хто лишився, може її добити
     */
    public boolean dropPlayer(int playerId, String nick, boolean removeUnits) {
        if (!host || dropped.containsKey(playerId)) return false;

        int effectiveTick = Math.max(sim.getTickNumber() + dropAnnounceMargin(),
                                     buffer.lastTickFor(playerId) + 1);

        PlayerDropped message = new PlayerDropped(playerId, nick, effectiveTick, removeUnits);
        transport.sendPlayerDropped(message);
        return true;
    }

    @Override
    public void onPlayerDropped(PlayerDropped message) {
        if (dropped.containsKey(message.playerId)) return;

        dropped.put(message.playerId,
                    message.nick == null ? "гравець #" + message.playerId : message.nick);
        buffer.dropPlayer(message.playerId, message.effectiveTick);

        // Якщо він вибув посеред ресинку — його підтвердження вже не буде.
        if (host && resyncPhase == ResyncPhase.AWAITING_ACKS) {
            acks.add(message.playerId);
            tryFinishResync();
        }

        if (message.removeUnits) pendingUnitRemoval.put(message.effectiveTick, message.playerId);

        NetLog.error("Гравець " + message.playerId + " вибуває з тіку "
                   + message.effectiveTick + (message.removeUnits ? " разом з армією" : ""));
    }

    /**
     * Тік вибуття → чию армію на ньому прибрати.
     *
     * <p>Прибирання армії — зміна СТАНУ, тож воно не може статись у момент
     * прибуття повідомлення: у різних клієнтів це різні тіки. Воно чекає рівно
     * до {@code effectiveTick} і виконується там разом з усіма — того самого
     * тіку, з якого наказів цього гравця вже не чекають.
     */
    private final java.util.Map<Integer, Integer> pendingUnitRemoval = new java.util.HashMap<>();

    private void applyPendingRemovals(int tick) {
        Integer playerId = pendingUnitRemoval.remove(tick);
        if (playerId == null) return;
        sim.removeArmy(io.jababa.lost_batalion.Team.forPlayer(playerId));
    }

    // ── Ресинк ────────────────────────────────────────────────────────────

    /** Стадія відновлення після десинхрону. */
    public enum ResyncPhase {
        /** Ресинк не йде. */
        IDLE,
        /** Хост зняв знімок і чекає підтверджень. */
        AWAITING_ACKS,
        /** Клієнт застосував знімок і чекає дозволу продовжити. */
        AWAITING_RESUME,
        /** Знімок не застосувався або хтось не підтвердив. */
        FAILED
    }

    private ResyncPhase resyncPhase = ResyncPhase.IDLE;
    private String  resyncError;
    private int     resyncTick;
    private int     generation;
    /** Хто вже підтвердив застосування знімка. Тільки в хоста. */
    private final java.util.Set<Integer> acks = new java.util.HashSet<>();

    public ResyncPhase getResyncPhase() { return resyncPhase; }
    public String getResyncError()      { return resyncError; }
    public boolean isHost()             { return host; }

    /** Скільки підтверджень уже є і скільки треба — для індикатора. */
    public int getAckCount()    { return acks.size(); }
    public int getAckExpected() { return playerIds.length; }

    /**
     * Хост: роздати свій стан як еталонний.
     *
     * <p>Чому знімок іде ВСІМ, а не лише тому, чий хеш розійшовся: після
     * розбіжності вже не самоочевидно, що стан «здорових» клієнтів справді
     * ідентичний хостовому — вони могли розійтись раніше й непомітно. Роздати
     * всім дорожче на кілька десятків кілобайт, зате гарантує спільний старт.
     */
    public boolean beginResync() {
        if (!host || resyncPhase == ResyncPhase.AWAITING_ACKS) return false;

        byte[] state = SimulationSnapshot.capture(sim);
        if (state == null) {
            resyncPhase = ResyncPhase.FAILED;
            resyncError = "Не вдалось зняти знімок стану.";
            return false;
        }

        resyncTick  = sim.getTickNumber();
        long checksum = StateChecksum.compute(sim, checksumComponents);

        acks.clear();
        acks.add(localPlayerId);          // власний стан підтверджувати нема чого
        // Вибулих не питаємо: вони не дадуть відповіді ніколи, і хост чекав би
        // підтвердження від того, кого сам щойно виключив.
        acks.addAll(dropped.keySet());
        resyncPhase = ResyncPhase.AWAITING_ACKS;
        resyncError = null;

        NetLog.error("Ресинк: роздаю стан на тіку " + resyncTick
                   + " (" + state.length + " байт)");
        transport.sendResyncSnapshot(new ResyncSnapshot(resyncTick, state, checksum));

        tryFinishResync();
        return true;
    }

    @Override
    public void onResyncSnapshot(ResyncSnapshot snapshot) {
        // Хост джерело знімка — застосовувати його собі означало б зайвий
        // круг серіалізації там, де стан і так правильний.
        if (host) return;

        boolean ok = SimulationSnapshot.restore(sim, snapshot.state);
        if (ok) {
            long actual = StateChecksum.compute(sim, checksumComponents);
            if (actual != snapshot.checksum) {
                ok = false;
                NetLog.error("Знімок розпакувався, але хеш не зійшовся: очікували "
                           + Long.toHexString(snapshot.checksum)
                           + ", вийшло " + Long.toHexString(actual));
            }
        }

        if (ok) {
            resyncTick  = snapshot.tick;
            resyncPhase = ResyncPhase.AWAITING_RESUME;
            resyncError = null;
        } else {
            resyncPhase = ResyncPhase.FAILED;
            resyncError = "Знімок стану не застосувався.";
        }

        transport.sendResyncAck(new ResyncAck(localPlayerId, snapshot.tick, ok));
    }

    @Override
    public void onResyncAck(ResyncAck ack) {
        if (!host || resyncPhase != ResyncPhase.AWAITING_ACKS) return;

        if (!ack.success) {
            resyncPhase = ResyncPhase.FAILED;
            resyncError = "Гравець #" + ack.playerId + " не зміг застосувати знімок.";
            return;
        }
        if (ack.tick != resyncTick) return;   // підтвердження від старої спроби

        acks.add(ack.playerId);
        tryFinishResync();
    }

    /** Хост: коли підтвердили всі, хто ще в матчі — дозволити продовжувати. */
    private void tryFinishResync() {
        for (int i = 0; i < playerIds.length; i++) {
            if (dropped.containsKey(playerIds[i])) continue;
            if (!acks.contains(playerIds[i])) return;
        }
        transport.sendResumeMatch(new ResumeMatch(resyncTick, generation + 1));
    }

    @Override
    public void onResumeMatch(ResumeMatch resume) {
        applyResume(resume.resumeTick, resume.generation);
    }

    /**
     * Спільний фінал ресинку: нове покоління, чистий буфер, новий розігрів.
     *
     * <p>Розігрів обов'язковий рівно з тієї ж причини, що й на старті матчу:
     * тіки {@code resumeTick+1 .. resumeTick+INPUT_DELAY} нікому нема з чого
     * заповнити, бо в момент, коли їхні накази мали б відправлятись, гра
     * стояла. Без нього матч завис би одразу після відновлення.
     */
    private void applyResume(int resumeTick, int newGeneration) {
        generation = newGeneration;
        buffer.beginGeneration(newGeneration);
        ledger.clear();

        resyncPhase = ResyncPhase.IDLE;
        resyncError = null;
        acks.clear();

        clock.reset();
        pendingTicks = 0;
        waiting = false;
        waitingFrames = 0;
        waitingForPlayer = -1;

        for (int tick = resumeTick + 1; tick <= resumeTick + NetConfig.getInputDelayTicks(); tick++) {
            transport.sendCommands(
                new TickCommands(tick, localPlayerId, new ArrayList<>(), newGeneration));
        }

        NetLog.error("Ресинк завершено: продовжуємо з тіку " + (resumeTick + 1)
                   + ", покоління " + newGeneration);
    }

    // ── Стан ──────────────────────────────────────────────────────────────

    public GameSimulation getSimulation() { return sim; }
    public float getRenderAlpha()         { return renderAlpha; }
    public int   getLocalPlayerId()       { return localPlayerId; }

    /** Чи цього кадру гра стоїть і чекає на чужі накази. */
    public boolean isWaiting()            { return waiting; }
    public int  getWaitingForPlayer()     { return waitingForPlayer; }
    public int   getWaitingFrames()       { return waitingFrames; }
    public float getWaitingMillis()       { return waitingMillis; }

    /**
     * Чи очікування триває вже настільки довго, що це варто показати.
     * З'єднання ще живе — просто хтось не встигає.
     */
    public boolean isLagWarning() {
        return waiting && waitingMillis >= NetConfig.LAG_WARNING_MILLIS;
    }

    public boolean isDisconnected()       { return disconnected; }
    public String  getDisconnectReason()  { return disconnectReason; }

    /** Чи стан розійшовся. Після цього цикл більше не робить жодного тіку. */
    public boolean isDesynced()           { return ledger.hasMismatch(); }

    public ChecksumLedger.Mismatch getMismatch() { return ledger.getMismatch(); }

    /** Готовий рядок для UI: що сталось і на якому тіку. */
    public String getDesyncSummary() {
        ChecksumLedger.Mismatch m = ledger.getMismatch();
        return m == null ? null : m.toString();
    }

    public int getChecksumInterval()      { return checksumInterval; }

    /** Скільки тіків команд є в запасі. Нуль означає, що ми на межі очікування. */
    public int getBufferedTicksAhead()    { return buffer.bufferedTicksAhead(sim.getTickNumber()); }

    /** Скільки тіків машина не потягнула — у матчі це «ця сторона гальмує всіх». */
    public int getDroppedTicks()          { return clock.getDroppedTicks(); }

    /**
     * Після паузи чи завантаження: скинути накопичений реальний час.
     * Буфер команд НЕ чіпається — накази суперника, що прийшли за цей час,
     * лишаються чинними, їх просто виконають наздоганяючи.
     */
    public void resetClock() {
        clock.reset();
        pendingTicks = 0;
    }

    /**
     * Приймати мережу, не рухаючи симуляцію. Для екрана завантаження.
     *
     * <p>{@link #update(float)} тут не годиться: він налив би тіків у борг за
     * час, поки гравець дивиться на смугу, і матч почався б із надолуження.
     */
    public void pumpTransport() {
        transport.pump(this);
        clock.reset();
    }

    /**
     * Чи можна починати: накази на перший тік є ВІД УСІХ.
     *
     * <p>Це і є синхронний фініш завантаження, і окремого рукостискання для
     * нього не треба. Кожен клієнт на старті шле порожні накази на тіки
     * розігріву ({@link #sendWarmup}) — тобто сам факт їх прибуття означає, що
     * той бік уже дійшов до створення матчу. Хто завантажився швидше, стоїть
     * на екрані завантаження, а не в бою: раніше він стояв так само, тільки це
     * виглядало як зависла гра й напис «гравець гальмує».
     */
    public boolean isSynced() {
        return buffer.isReady(sim.getTickNumber() + 1, playerIds);
    }

    public void close() {
        transport.close();
        buffer.clear();
        ledger.clear();
    }
}
