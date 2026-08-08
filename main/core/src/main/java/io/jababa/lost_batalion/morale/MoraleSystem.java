package io.jababa.lost_batalion.morale;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.capture.CaptureManager;
import io.jababa.lost_batalion.capture.CapturePoint;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.TickRate;
import io.jababa.lost_batalion.units.Unit;

/**
 * Мораль: чому юніт виходить з бою, ще будучи живим.
 *
 * <h3>Що це за шкала</h3>
 * Друге здоров'я, у тих самих одиницях і того самого типу ({@link Fixed}).
 * Витрачає його не свинець, а тиск: скільки стволів на тебе дивиться, як
 * боляче дісталось і чи довго це триває. Дійшло до нуля — юніт ЗЛАМАВСЯ:
 * на {@link #ROUT_TICKS} тіків він перестає слухати накази, перестає
 * стріляти й біжить від тих, хто його тиснув.
 *
 * <h3>Два канали втрати, і обидва потрібні</h3>
 * <ul>
 *   <li><b>Придушення</b> — щотіку, поки юніт стоїть у чиїйсь дальності.
 *       Саме цей канал відповідає за «чим більше на мене, тим швидше»:
 *       {@link #EXTRA_PER_ATTACKER} множить втрату на кожного зайвого ствола.
 *       Гармата з її {@code attackRange == 0} сюди не входить взагалі.</li>
 *   <li><b>Шок від удару</b> — у момент влучання, всередині
 *       {@code Unit.takeDamage}. Пропорційний тому, що ДІЙШЛО, і множиться на
 *       {@code Unit.moraleShock()} того, хто вдарив. Обстріл сюди потрапляє
 *       сам собою, окремої гілки для нього немає.</li>
 * </ul>
 * Одного каналу замало: тільки придушення — і влучання нічого не важить,
 * тільки шок — і оточення трьома стволами нічим не гірше за одного.
 *
 * <h3>Чому це окрема система, а не метод у бою</h3>
 * Мораль дивиться на ВСЮ картину навколо юніта — скільки ворогів, чи є свої
 * поруч, чи стоїть він на точці. Бій же дивиться на пару «атакуючий-ціль».
 * Змішати їх означало б рахувати оточення всередині циклу по пострілах.
 *
 * <p>Стан цілочисельний і входить у checksum ({@code C_MORALE}) та в знімок:
 * два клієнти з різною мораллю розійшлися б у той тік, коли в одного з них
 * рота побігла, а в другого ні.
 */
public class MoraleSystem {

    // ── Числа ─────────────────────────────────────────────────────────────
    //
    // Усе, що названо «за секунду», переводиться в за-тік ОДИН раз, тут.
    // Множити щотіку на непредставні 0.025 означало б накопичувати похибку —
    // та сама причина, з якої швидкість юніта зберігається за тік.

    /**
     * Втрата за секунду, коли на юніта працює рівно один ствол.
     *
     * <p>Було 6; усі джерела втрати поділено на 1.5 разом із подвоєнням
     * здоров'я. Причина в тому, що ці дві величини міряються ОДНА ПРОТИ ОДНОЇ:
     * мораль спадає за часом, а час бою задає запас здоров'я. Удвічі товщий
     * юніт стоїть під вогнем удвічі довше, тобто при старих числах ламався б
     * удвічі частіше, ніж задумано, — не змінивши в моралі жодної цифри.
     */
    private static final long BASE_DRAIN_PER_SEC = Fixed.fromInt(4);

    /**
     * Наскільки кожен ЗАЙВИЙ нападник додає до втрати.
     *
     * <p>Лінійно, а не квадратично: удвох страшніше, ніж самому, але вдесятьох
     * не в п'ять разів страшніше, ніж удвох — на певному етапі юніт уже й так
     * не бачить нічого, крім того, що по ньому б'ють. При 0.6 сам-на-сам
     * ламає за ~17 с, троє — за ~8, п'ятеро — за ~5.
     */
    private static final long EXTRA_PER_ATTACKER = Fixed.fromFloat(0.6f);

    /**
     * Наскільки страшніше, коли б'ють з РІЗНИХ боків, а не з одного.
     *
     * <p>Кількість стволів — це ще не оточення. Троє в лоб і троє з трьох
     * сторін для арифметики однакові, а для шеренги — ні: у першому випадку
     * вона стоїть фронтом, у другому фронту немає взагалі. Саме це відрізняє
     * позицію від чисельності, тобто рівно те, на чому стоїть уся гра.
     *
     * <p>Це МНОЖНИК, а не джерело втрати, тому загального ділення на 1.5 він
     * не отримав: сповільнили те, ЯК ШВИДКО згоряє мораль, а не те, наскільки
     * обхід страшніший за лобову атаку.
     *
     * <p>Множник іде ПОВЕРХ кількості, а не замість неї: обхід має винагороджувати
     * того, хто його зробив, а не знецінювати перевагу в головах.
     */
    private static final long FLANK_BONUS = Fixed.fromFloat(0.6f);

    /** Відновлення за секунду, коли юніт уже заспокоївся. */
    private static final long REGEN_PER_SEC = Fixed.fromInt(8);

    /**
     * Скільки треба простояти без втрат, перш ніж мораль піде вгору.
     *
     * <p>15 секунд — довше за будь-який бій «наскочив і відскочив». Коротша
     * пауза означала б, що юніта досить на мить вивести з-під вогню, і тиск
     * перестав би накопичуватись узагалі.
     */
    private static final int CALM_TICKS_TO_REGEN = 15 * TickRate.TICKS_PER_SECOND;

    /**
     * У скільки разів швидше мораль відновлюється на точці захоплення.
     *
     * <p>Село — це дах, вода й відчуття, що ти кудись прийшов. Заразом це
     * робить точку вартою утримання не лише через очки: рота, яку відвели в
     * село, повертається в стрій удвічі швидше.
     */
    private static final long REGEN_ON_POINT = Fixed.fromInt(2);

    /** Скільки триває втеча. */
    public static final int ROUT_TICKS = 5 * TickRate.TICKS_PER_SECOND;

    /** Наскільки далеко юніт тікає від тих, хто його зламав (Q47.16). */
    private static final long ROUT_DISTANCE = Fixed.fromInt(150);

    /**
     * З якою мораллю юніт повертається в стрій.
     *
     * <p><b>Без цього механіка не працює зовсім.</b> Юніт, що вийшов із втечі
     * з нулем, ламається наступного ж тіку — і так довіку: замість однієї
     * втечі виходить юніт, який тікає завжди. Третина бару — це «отямився,
     * але ще не в порядку».
     */
    private static final long RECOVERY_FRACTION = Fixed.fromFloat(0.35f);

    /**
     * Скільки моралі забирає у сусіда чужа паніка.
     *
     * <p>Одноразово, у мить зламу, і навмисно небагато: втеча сусіда мусить
     * бути помітною подією, а не детонатором. З великим числом фронт осипався
     * б ланцюгом від одного невдалого юніта — виглядає ефектно рівно один раз,
     * після чого бій перестає залежати від гравця.
     */
    private static final long PANIC_SPREAD = Fixed.fromFloat(6.7f);   // було 10, /1.5

    /** У якому радіусі чути чужу паніку (Q47.16). */
    private static final long PANIC_RADIUS = Fixed.fromInt(90);

    // ── Похідні від них величини за тік ───────────────────────────────────

    private static final long BASE_DRAIN_PER_TICK =
        Fixed.divInt(BASE_DRAIN_PER_SEC, TickRate.TICKS_PER_SECOND);
    private static final long REGEN_PER_TICK =
        Fixed.divInt(REGEN_PER_SEC, TickRate.TICKS_PER_SECOND);

    // ── Стан ──────────────────────────────────────────────────────────────

    private final long mapW, mapH;

    /** Буфери напрямку, центроїда загрози й напрямку на нападника. */
    private final long[] dir    = new long[2];
    private final long[] threat = new long[2];
    private final long[] press  = new long[2];

    /**
     * Кого зламало ЦЬОГО тіку — щоб паніка розійшлась після того, як
     * порахувались усі, а не в середині обходу.
     *
     * <p>Це не оптимізація, а детермінізм: якби зламаний одразу бив по
     * сусідах, результат залежав би від того, кого раніше зустрів цикл, і
     * ланцюг зламів пішов би в порядку індексів масиву.
     */
    private final Array<Unit> brokeThisTick = new Array<>();

    public MoraleSystem(long mapW, long mapH) {
        this.mapW = mapW;
        this.mapH = mapH;
    }

    /**
     * Один крок.
     *
     * <p>Кличеться ПІСЛЯ бою: урон цього тіку вже нанесений, тобто шок від
     * влучань уже списаний, і лишається порахувати тиск та наслідки.
     *
     * @param points точки захоплення; {@code null} — карта без них
     */
    public void tick(Array<Unit> units, CaptureManager points) {
        brokeThisTick.clear();

        // Чи є на полі хоч один юніт з аурою. Прохід лінійний, а економить
        // цілий O(n²): поки офіцерів не існує, шукати їх біля кожного юніта
        // сорок разів на секунду немає сенсу.
        boolean auras = false;
        for (int i = 0; i < units.size && !auras; i++) {
            Unit s = units.get(i);
            auras = s.alive && s.moraleAuraRadius() > 0;
        }

        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            if (!u.alive) continue;

            if (u.isBroken()) { tickRout(u); continue; }

            long drain = drainFor(u, units);
            if (drain > 0) {
                u.loseMorale(drain);
            } else {
                // Спокій рахується тільки поза чужою дальністю. Юніт, який
                // стоїть під стволами і просто ще не отримав кулю, у спокої
                // не перебуває.
                u.calmTicks++;
                regen(u, points);
            }

            if (auras) applyAura(u, units);

            if (u.morale <= 0) breakUnit(u, units);
        }

        // Паніка розходиться останньою і одним пакетом — див. brokeThisTick.
        for (int i = 0; i < brokeThisTick.size; i++) spreadPanic(brokeThisTick.get(i), units);
    }

    /**
     * Втрата моралі за цей тік від тиску: скільки стволів і з яких боків.
     *
     * <p>«Дістає», а не «стріляє»: юнітом, на якого наведено п'ять стволів,
     * тиснуть п'ятеро, навіть якщо цього тіку вистрелив один. Той самий O(n²),
     * що й у {@code UnitSeparation}, і з тієї ж причини — питання про КОЖНУ
     * пару, а сітка сусідства знадобиться їм обом разом.
     *
     * <p>Зламані не тиснуть: юніт, що біжить повз, нікого не лякає.
     * Видимість тут ні до чого — страшно від того, хто по тобі б'є, а не від
     * того, кого ти роздивився.
     *
     * <h3>Як міряється «з різних боків»</h3>
     * Складаються ОДИНИЧНІ вектори на кожного нападника; довжина суми,
     * поділена на їхню кількість, дає число від {@link Fixed#ONE} (усі в
     * одному напрямку — юніт стоїть фронтом) до нуля (рівномірно навколо —
     * фронту немає). Це кругова дисперсія, і вона не потребує ні сортування
     * кутів, ні {@code atan2}: один корінь на нападника, та й той рахується
     * лише для тих, хто вже пройшов перевірку дальності по КВАДРАТАХ, тобто
     * для одиниць, а не для всього поля.
     *
     * @return втрата за тік; {@code 0} означає «на юніта ніхто не працює»
     */
    private long drainFor(Unit u, Array<Unit> units) {
        int  n    = 0;
        long sumX = 0, sumY = 0;

        for (int i = 0; i < units.size; i++) {
            Unit e = units.get(i);
            if (!e.alive || e.team == u.team || e.isBroken()) continue;
            if (e.attackRange <= 0) continue;   // гармата тисне лише влучанням
            if (Fixed.dstSq(e.x, e.y, u.x, u.y) > Fixed.mul(e.attackRange, e.attackRange)) continue;

            n++;
            // Довжина не потрібна — потрібен лише напрямок: нападник за крок
            // від тебе і нападник на межі дальності стоять з одного боку
            // однаково.
            if (Fixed.normalize(e.x - u.x, e.y - u.y, press) != 0) {
                sumX += press[0];
                sumY += press[1];
            }
        }
        if (n == 0) return 0;

        long crowd = Fixed.ONE + Fixed.mul(EXTRA_PER_ATTACKER, Fixed.fromInt(n - 1));

        // Зімкнутість: ONE — усі з одного боку, 0 — звідусіль. Ділиться на
        // кількість, бо кожен доданок був одиничним.
        long focus = Fixed.divInt(Fixed.length(sumX, sumY), n);
        long flank = Fixed.ONE + Fixed.mul(FLANK_BONUS, Fixed.ONE - focus);

        return Fixed.mul(Fixed.mul(BASE_DRAIN_PER_TICK, crowd), flank);
    }

    private void regen(Unit u, CaptureManager points) {
        if (u.calmTicks < CALM_TICKS_TO_REGEN || u.morale >= u.maxMorale) return;

        long gain = REGEN_PER_TICK;
        if (onCapturePoint(u, points)) gain = Fixed.mul(gain, REGEN_ON_POINT);

        u.morale = Fixed.min(u.maxMorale, u.morale + gain);
    }

    /**
     * Чи стоїть юніт у якійсь зоні захоплення.
     *
     * <p>У БУДЬ-ЯКІЙ, не лише у своїй: відпочивають у селі, а не в кольорі
     * прапора над ним. Чужу точку ще й тримати треба, тобто задарма це не
     * дістається.
     */
    private static boolean onCapturePoint(Unit u, CaptureManager points) {
        if (points == null) return false;
        Array<CapturePoint> all = points.getPoints();
        for (int i = 0; i < all.size; i++) {
            if (all.get(i).contains(u.x, u.y)) return true;
        }
        return false;
    }

    /**
     * Підтримка своїх.
     *
     * <p>Поки що не робить нічого: жоден юніт не повертає ненульового
     * {@code moraleAuraRadius()}. Код стоїть тут не «про запас», а тому що це
     * єдине місце, куди офіцер із барабанщиком мали б вписатись, — і коли
     * вони з'являться, система моралі не зміниться жодним рядком.
     */
    private static void applyAura(Unit u, Array<Unit> units) {
        if (u.morale >= u.maxMorale) return;

        long total = 0;
        for (int i = 0; i < units.size; i++) {
            Unit s = units.get(i);
            if (!s.alive || s.team != u.team || s == u || s.isBroken()) continue;
            long radius = s.moraleAuraRadius();
            if (radius <= 0) continue;
            if (Fixed.dstSq(s.x, s.y, u.x, u.y) > Fixed.mul(radius, radius)) continue;
            total += s.moraleAuraBonus();
        }
        if (total <= 0) return;

        // Аура задається за секунду, як і решта; ділиться тут, бо це єдине
        // місце, яке знає про тіки.
        u.morale = Fixed.min(u.maxMorale,
                             u.morale + Fixed.divInt(total, TickRate.TICKS_PER_SECOND));
    }

    // ── Злам ──────────────────────────────────────────────────────────────

    /**
     * Юніт зламався: віддати йому останній наказ у житті — тікати.
     *
     * <p>Накази атаки знімає {@code CombatManager}, а не ця система: групи й
     * ордери — його стан, і лізти в них ззовні означало б завести друге
     * місце, яке про них знає.
     */
    private void breakUnit(Unit u, Array<Unit> units) {
        u.routTicks = ROUT_TICKS;
        u.calmTicks = 0;
        // Виділення тут НЕ чіпається, хоч і кортить: воно живе парою
        // «прапорець на юніті + список у UnitManager», і погасити лише
        // прапорець означає лишити юніта в списку. Цим займається сам
        // UnitManager у своєму тіку — там, де він уже прибирає вбитих.

        threatCentroid(u, units, threat);
        long len = Fixed.normalize(u.x - threat[0], u.y - threat[1], dir);
        if (len == 0) {
            // Ніхто поруч (мораль догоріла від артилерії з-за пагорба) —
            // тікаємо в тил своєї сторони, тобто до свого кута карти.
            dir[0] = 0;
            dir[1] = u.team.index() == 0 ? -Fixed.ONE : Fixed.ONE;
        }

        long tx = Fixed.clamp(u.x + Fixed.mul(dir[0], ROUT_DISTANCE), 0, mapW);
        long ty = Fixed.clamp(u.y + Fixed.mul(dir[1], ROUT_DISTANCE), 0, mapH);
        u.moveTo(tx, ty);

        brokeThisTick.add(u);
    }

    /**
     * Центр маси ворогів поблизу — від нього юніт і тікає.
     *
     * <p>Не «від найближчого»: оточеному втеча від одного означала б біг
     * просто на другого. Центроїд дає єдиний бік, у якому справді менше
     * ворогів.
     */
    private static void threatCentroid(Unit u, Array<Unit> units, long[] out) {
        long sumX = 0, sumY = 0;
        int  n    = 0;
        long radiusSq = Fixed.mul(THREAT_RADIUS, THREAT_RADIUS);
        for (int i = 0; i < units.size; i++) {
            Unit e = units.get(i);
            if (!e.alive || e.team == u.team) continue;
            if (Fixed.dstSq(e.x, e.y, u.x, u.y) > radiusSq) continue;
            sumX += e.x;
            sumY += e.y;
            n++;
        }
        // Ділення цілих, тобто однакове на всіх клієнтах. Центроїд «на собі»
        // означає, що ворогів поблизу немає, — це ловить викликач.
        out[0] = n == 0 ? u.x : sumX / n;
        out[1] = n == 0 ? u.y : sumY / n;
    }

    /**
     * У якому радіусі шукати тих, від кого тікати.
     *
     * <p>Помітно більший за дальність піхоти (90): ламає юніта саме та юрба,
     * що насувається, і половина її ще може бути на підході.
     */
    private static final long THREAT_RADIUS = Fixed.fromInt(200);

    /** Просунути втечу; на останньому тіку юніт отямлюється. */
    private static void tickRout(Unit u) {
        u.routTicks--;
        if (u.routTicks > 0) return;
        u.morale    = Fixed.mul(u.maxMorale, RECOVERY_FRACTION);
        u.calmTicks = 0;
        u.stopMoving();
    }

    /** Чужа паніка поруч. Одноразово, у мить зламу. */
    private static void spreadPanic(Unit broken, Array<Unit> units) {
        long radiusSq = Fixed.mul(PANIC_RADIUS, PANIC_RADIUS);
        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            if (!u.alive || u.team != broken.team || u == broken || u.isBroken()) continue;
            if (Fixed.dstSq(u.x, u.y, broken.x, broken.y) > radiusSq) continue;
            u.loseMorale(PANIC_SPREAD);
            // Ланцюга тут навмисно немає: юніт, зламаний чужою панікою, свою
            // рознесе вже НАСТУПНОГО тіку, пройшовши звичайну перевірку. Так
            // обвал фронту лишається можливим, але коштує часу, а не одного
            // проходу циклу.
        }
    }

    // ── Checksum ──────────────────────────────────────────────────────────
    //
    // Знімок і хеш самих полів робить Unit — вони живуть на юніті. У цієї
    // системи власного стану, який переживає тік, немає взагалі:
    // brokeThisTick чиститься на початку кожного кроку.
}
