package io.jababa.lost_batalion.terrain;

import io.jababa.lost_batalion.math.Fixed;

/**
 * Єдина точка доступу до місцевості. Об'єднує ДВІ маски сценарію і знає,
 * що саме в якій лежить.
 *
 * <h3>Навіщо це потрібно</h3>
 * У сценарію дві різні PNG-маски, і дані в них розкидані НЕ так, як підказує
 * назва:
 * <ul>
 *   <li><b>маска лісу</b> ({@code *_mask.png}) — ліс <i>і РІЧКИ</i>;</li>
 *   <li><b>маска топографії</b> ({@code *_terrain_mask.png}) — тільки яруси
 *       висот (нізіни → височини). Річок у ній НЕМАЄ.</li>
 * </ul>
 * Кожен, хто питав маску безпосередньо, рано чи пізно брав не ту: штраф за
 * височини не працював у русі, штраф за річку зникав при спробі це виправити,
 * а бонус лісу до захисту в бою не спрацьовував жодного разу. Тому логіка
 * «яку маску питати» живе тут, в одному місці, а не в кожного споживача.
 *
 * <h3>Два набори методів</h3>
 * Методи з суфіксом {@code F} приймають координати у {@link Fixed} (Q47.16) —
 * це API для СИМУЛЯЦІЇ, і в ній немає жодного float. Методи без суфікса
 * приймають {@code float} і призначені рендеру (ALT-оверлей, туман). Обидва
 * набори читають ті самі дані й дають ті самі відповіді: float-версії просто
 * делегують, тому розійтись вони не можуть за побудовою.
 *
 * <p>Клас не володіє масками і не звільняє їх — це робить той, хто їх створив
 * (наразі {@code GameScreen}).
 */
public class TerrainQuery {

    /**
     * Наскільки (в ярусах висоти) місцевість має підніматися над ЛІНІЄЮ ЗОРУ,
     * щоб перекрити її.
     *
     * <h3>Лінія, а не стеля</h3>
     * Раніше поріг був сталий уздовж усього відрізка: {@code max(hA,hB) + 1},
     * тобто «гребінь мусить бути вищим за ОБИДВА кінці». Це прощало найгрубішу
     * помилку рельєфу: юніт у низині (ярус 1) дивився на рівнину (3) — і
     * перекрити його огляд могло тільки щось від 4-го ярусу. Обід улоговини,
     * через який він насправді нічого не бачить, за побудовою не рахувався.
     * На Жовтих Водах це давало 100% видимості з ями на височини й 57.7% на всі
     * цілі в радіусі огляду — з рівнини було 58.0%, тобто яма нічим не
     * відрізнялась від відкритого поля.
     *
     * <p>Тепер поріг іде вздовж прямої між висотами кінців:
     * {@code h(d) = hA + (hB − hA)·d/L}, а перекриває те, що піднімається над
     * НЕЮ на цілий ярус. Це звичайна геометрія прямої видимості: дивлячись із
     * ями вгору, ти впираєшся у власний обід, бо промінь проходить над ним
     * низько. Симетрія збережена — пряма та сама в обидва боки.
     *
     * <p>Заміряно на масках Жовтих Вод після зміни: з низин видно 19.1% цілей
     * (було 57.7%), з рівнин 51.8% (було 58.0%), з височин 80.6% (було 100%).
     *
     * <p>Ціле число, а не 0.5: яруси цілі, і «вище за лінію більш ніж на пів
     * ярусу» для них означало б те саме, що «не нижче за наступний ярус» лише
     * на пласкій лінії. На похилій лінія дробова, тож запас має бути чесним
     * ярусом.
     */
    public static final int ELEVATION_BLOCK_MARGIN_TIERS = 1;

    /** Базова висота рівнини — для NONE/FOREST, які не несуть даних про ярус. */
    public static final int   PLAINS_BASELINE_HEIGHT_INT = 3;
    public static final float PLAINS_BASELINE_HEIGHT     = PLAINS_BASELINE_HEIGHT_INT;

    /**
     * Крок вибірки вздовж відрізка в СИМУЛЯЦІЇ. 1 світова одиниця = 1 піксель
     * маски, тобто це межа роздільності даних — дрібніше немає чого шукати.
     *
     * <p>Рендер ALT-оверлея цим не користується: там промінь перетинає межі
     * пікселів аналітично (див. {@code FogOfWarRenderer.walkPixels}), бо йому
     * потрібна точна відстань до перешкоди, а не факт «щось є на відрізку».
     */
    public static final long  LOS_FINE_STEP_FIXED = Fixed.ONE;

    /**
     * Ліс ближче за цю відстань до спостерігача не перекриває йому огляд.
     * Інакше юніт, що стоїть у лісі, осліп би повністю: власний кущ рахувався б
     * як перешкода. Вплив свого лісу враховує окремо sightMod (×0.55).
     *
     * Спільна константа для VisibilitySystem і ALT-оверлея — щоб оверлей
     * показував рівно те, що система видимості реально рахує.
     */
    public static final float LOS_ORIGIN_FOREST_SKIP       = 12f;
    public static final long  LOS_ORIGIN_FOREST_SKIP_FIXED = Fixed.fromInt(12);

    /**
     * Мінімальна довжина (у світових одиницях) суцільного підйому, щоб він
     * рахувався за гребінь і перекривав огляд.
     *
     * <p>Межі ярусів у масці — піксельні «сходинки». Промінь, що йде майже по
     * дотичній до такої межі, зачіпає один кутовий піксель і обривався на ньому,
     * тоді як сусідній промінь той самий піксель проминав. На екрані це давало
     * тонкі «штрихи» — смуги тіні завширшки в один промінь. Один піксель — не
     * хребет, тому підйом має протриматись хоча б стільки, щоб перекрити огляд.
     *
     * <p>Заміряно на масках Жовтих Вод: 3 одиниці прибирають ~80% таких штрихів,
     * а середня довжина променя зростає менш ніж на 1% — тіні від справжніх
     * хребтів лишаються на місці. Без цього правила дрібніший крок вибірки лише
     * ПОГІРШУВАВ картинку (крок 0.25 давав удвічі-втричі більше штрихів, бо
     * переставав випадково перестрибувати кутові пікселі).
     */
    public static final float LOS_CREST_MIN_RUN       = 3f;
    public static final long  LOS_CREST_MIN_RUN_FIXED = Fixed.fromInt(3);

    /** Заглушка «нескінченно далеко» для DDA. З запасом на додавання без переповнення. */
    private static final long FAR = Long.MAX_VALUE >> 2;

    private final TerrainMaskManager forestMask;
    private final TerrainMaskManager elevationMask;

    /**
     * @param forestMask    маска лісу ({@code *_mask.png}) — ліс + річки
     * @param elevationMask маска топографії ({@code *_terrain_mask.png}) — яруси висот
     */
    public TerrainQuery(TerrainMaskManager forestMask, TerrainMaskManager elevationMask) {
        this.forestMask    = forestMask;
        this.elevationMask = elevationMask;
    }

    // ── Точкові запити: симуляція (Q47.16) ────────────────────────────────
    //
    // Координата переводиться в індекс пікселя відкиданням дробової частини.
    // Це точна цілочисельна операція (арифметичний зсув), тож два клієнти з
    // однаковим станом завжди читають той самий піксель.

    public boolean isForestF(long rawX, long rawY) {
        return forestMask != null
            && forestMask.isForestAtPixel(Fixed.toIntFloor(rawX), Fixed.toIntFloor(rawY));
    }

    /**
     * Тип місцевості за висотою.
     *
     * Річка перевіряється ПЕРШОЮ і саме в масці лісу — вона намальована там,
     * а не в топографічній масці.
     */
    public TerrainType elevationF(long rawX, long rawY) {
        int px = Fixed.toIntFloor(rawX), py = Fixed.toIntFloor(rawY);
        if (forestMask != null && forestMask.getElevationAtPixel(px, py) == TerrainType.RIVER)
            return TerrainType.RIVER;
        return elevationMask != null ? elevationMask.getElevationAtPixel(px, py) : TerrainType.NONE;
    }

    /**
     * Ярус висоти в точці — ціле число.
     *
     * Висоту дає ВИКЛЮЧНО топографічна маска: вона єдиний авторитет по рельєфу.
     * Річка — це поверхнева деталь, а не яма: вона впливає на рух і бій, але не
     * змінює висоту (у топографічній масці під річками порожньо → NONE → 3).
     */
    public int heightF(long rawX, long rawY) {
        return elevationMask != null
            ? heightOf(elevationMask.getElevationAtPixel(Fixed.toIntFloor(rawX),
                                                         Fixed.toIntFloor(rawY)))
            : PLAINS_BASELINE_HEIGHT_INT;
    }

    /** Множник швидкості руху в цій точці (Q47.16). */
    public long movementMultiplierF(long rawX, long rawY) {
        return TerrainMovementModifier.getMultiplier(elevationF(rawX, rawY), isForestF(rawX, rawY));
    }

    /**
     * Найдрібніша пляма села, яку ще беремо за село. Плями на Жовтих Водах —
     * від 1500 пікселів, тож поріг відсіює лише поодинокі пікселі на межах
     * заливки маски.
     */
    private static final int VILLAGE_MIN_PIXELS = 200;

    /**
     * Центри сіл у світових пікселях: пари {@code x, y} підряд.
     *
     * <p>Села намальовані в масці ЛІСУ (колір #F0FF00) — як і річки, всупереч
     * назві файлу. Ще одна причина, чому ця логіка живе тут, а не в кожного
     * споживача.
     */
    public int[] villageCenters() {
        return forestMask != null
            ? forestMask.findClusterCenters(TerrainType.VILLAGE, VILLAGE_MIN_PIXELS)
            : new int[0];
    }

    // ── Точкові запити: рендер (float) ────────────────────────────────────

    public boolean isForest(float x, float y) {
        return isForestF(Fixed.fromFloat(x), Fixed.fromFloat(y));
    }

    public TerrainType elevation(float x, float y) {
        return elevationF(Fixed.fromFloat(x), Fixed.fromFloat(y));
    }

    public float height(float x, float y) {
        return heightF(Fixed.fromFloat(x), Fixed.fromFloat(y));
    }

    /**
     * Числова висота ярусу. Єдине джерело правди для LOS-математики.
     *
     * RIVER має ту саму висоту, що й рівнина: юніт у воді не сидить у каньйоні —
     * він не сліпне і не ховається. Раніше тут стояв 0, але це був МЕРТВИЙ код:
     * усі запити висоти йшли в топографічну маску, де річок немає взагалі.
     * Щойно річки під'єднали правильно, 0 ожив і зробив із кожної річки ущелину.
     */
    public static int heightOf(TerrainType t) {
        switch (t) {
            case RIVER:         return PLAINS_BASELINE_HEIGHT_INT;
            case LOWLANDS:      return 1;
            case PRE_LOWLANDS:  return 2;
            case PLAINS:
            case PLAINS_ALT:    return 3;
            case PRE_HIGHLANDS: return 4;
            case HIGHLANDS:     return 5;
            default:            return PLAINS_BASELINE_HEIGHT_INT;   // NONE/FOREST
        }
    }

    /**
     * Поріг перекриття на відстані {@code d} від початку відрізка довжиною
     * {@code dist}, у Q47.16.
     *
     * <p>Пряма між висотами кінців плюс запас у цілий ярус — див.
     * {@link #ELEVATION_BLOCK_MARGIN_TIERS}. Нахил передається готовим, бо в
     * гарячому циклі він сталий, а ділення — найдорожча операція fixed-point.
     *
     * @param hStartFixed висота початку відрізка (Q47.16)
     * @param slope       {@code (hEnd − hStart) / dist}, теж Q47.16
     */
    private static long occlusionCeiling(long hStartFixed, long slope, long d) {
        return hStartFixed + Fixed.mul(slope, d)
             + Fixed.fromInt(ELEVATION_BLOCK_MARGIN_TIERS);
    }

    // ── Блокове зведення: прискорення ray-marching ────────────────────────────
    //
    // Промінь іде блоками BLOCK_SIZE×BLOCK_SIZE і перестрибує блок цілком, якщо
    // той гарантовано не може його зупинити. Межі нижче — КОНСЕРВАТИВНІ
    // (min занижений, max завищений), тому пропуск точний, а не наближений:
    // хибно піти в детальний прохід можна, хибно пропустити перешкоду — ні.

    public int blockSize()  { return TerrainMaskManager.BLOCK_SIZE; }

    public int blocksX() { return forestMask != null ? forestMask.getBlocksX()
                                : (elevationMask != null ? elevationMask.getBlocksX() : 0); }
    public int blocksY() { return forestMask != null ? forestMask.getBlocksY()
                                : (elevationMask != null ? elevationMask.getBlocksY() : 0); }

    /** Чи може блок містити ліс. */
    public boolean blockHasForest(int bx, int by) {
        return forestMask != null && forestMask.blockHasForest(bx, by);
    }

    /**
     * Межі висоти в блоці — тільки з топографічної маски, бо {@link #heightF}
     * читає теж лише її. Межі й точкові значення беруться з одного джерела, тому
     * вони узгоджені за побудовою.
     */
    public int blockMinHeight(int bx, int by) {
        return elevationMask != null ? elevationMask.blockMinHeight(bx, by)
                                     : PLAINS_BASELINE_HEIGHT_INT;
    }

    public int blockMaxHeight(int bx, int by) {
        return elevationMask != null ? elevationMask.blockMaxHeight(bx, by)
                                     : PLAINS_BASELINE_HEIGHT_INT;
    }

    // ── Запити вздовж відрізка (лінія зору між двома юнітами) ─────────────────

    /**
     * Чи перетинає відрізок ліс. Координати у Q47.16.
     *
     * <p>Обидва кінці мають власну «сліпу зону»: ліс упритул до спостерігача не
     * закриває йому огляд, і так само ліс упритул до цілі не ховає її додатково —
     * те, що ціль стоїть у лісі, вже враховано окремим множником. Без другого
     * скіпу будь-яка ціль у лісі автоматично рахувалась би за перекриту, бо
     * промінь неминуче зачепить її власні дерева.
     *
     * @param skipNearStart перші стільки одиниць від початку ігноруються
     * @param skipNearEnd   останні стільки одиниць перед кінцем ігноруються
     *                      (див. {@link #LOS_ORIGIN_FOREST_SKIP_FIXED})
     */
    public boolean hasForestOnSegmentF(long x1, long y1, long x2, long y2,
                                       long skipNearStart, long skipNearEnd) {
        return segmentHits(x1, y1, x2, y2, true, 0, 0, skipNearStart, skipNearEnd);
    }

    /**
     * Чи перекриває гребінь лінію зору між двома точками. Обидва кінці
     * виключені: юніт не перекриває сам себе.
     *
     * <p>Поріг НЕ сталий — він іде вздовж прямої між висотами кінців, див.
     * {@link #ELEVATION_BLOCK_MARGIN_TIERS}. Висоти передаються аргументами, а
     * не читаються тут, бо той, хто питає, вже їх знає: {@code VisibilitySystem}
     * бере їх для обох юнітів на початку перевірки.
     *
     * <p>Одного пікселя замало — підйом має протриматись
     * {@link #LOS_CREST_MIN_RUN} одиниць поспіль, інакше зубчаста межа ярусу
     * рвала б видимість від кожного кутового пікселя (та сама причина, що й
     * «штрихи» в ALT-оверлеї).
     *
     * @param hStart ярус висоти в точці {@code (x1,y1)}
     * @param hEnd   ярус висоти в точці {@code (x2,y2)}
     */
    public boolean hasGroundAboveOnSegmentF(long x1, long y1, long x2, long y2,
                                            int hStart, int hEnd) {
        return segmentHits(x1, y1, x2, y2, false, hStart, hEnd, 0, 0);
    }

    /** Float-обгортка для рендеру. */
    public boolean hasForestOnSegment(float x1, float y1, float x2, float y2,
                                      float skipNearStart, float skipNearEnd) {
        return hasForestOnSegmentF(Fixed.fromFloat(x1), Fixed.fromFloat(y1),
                                   Fixed.fromFloat(x2), Fixed.fromFloat(y2),
                                   Fixed.fromFloat(skipNearStart),
                                   Fixed.fromFloat(skipNearEnd));
    }

    /**
     * Спільний обхід відрізка блоками (DDA) з детальним доходженням лише там,
     * де це потрібно. Уся математика — цілочисельна.
     *
     * <p>Поріг перекриття вздовж відрізка НЕ сталий: він іде прямою між
     * висотами кінців. Пропуск блоку від цього не перестає бути точним, бо
     * пряма монотонна — найнижчий поріг на ділянці блоку неодмінно припадає на
     * один із її кінців. Якщо навіть найвищий піксель блоку не сягає цього
     * мінімуму, пропустити блок безпечно. Для лісу все як було: немає лісу в
     * блоці — немає що шукати.
     *
     * <p>У режимі висот блокує не окремий піксель, а лише суцільний підйом
     * завдовжки {@link #LOS_CREST_MIN_RUN} — лічильник {@code crestRun}. Він
     * обнуляється і на пікселі нижче порога, і на пропущеному блоці: пропуск
     * дозволений лише тоді, коли в блоці гарантовано немає жодного пікселя від
     * порога, тобто підйом там точно уривається.
     *
     * @param forestMode true → шукаємо ліс; false → шукаємо гребінь над лінією
     * @param hStart     висота початку відрізка (значуще лише для висот)
     * @param hEnd       висота кінця відрізка
     */
    private boolean segmentHits(long x1, long y1, long x2, long y2,
                                boolean forestMode, int hStart, int hEnd,
                                long skipNearStart, long skipNearEnd) {
        long dx = x2 - x1, dy = y2 - y1;
        long dist = Fixed.length(dx, dy);

        // Сліпа зона біля цілі: обхід просто закінчується раніше.
        long limit = dist - skipNearEnd;
        if (limit <= LOS_FINE_STEP_FIXED) return false;

        long ux = Fixed.div(dx, dist), uy = Fixed.div(dy, dist);

        // Нахил лінії зору рахується РАЗ: у циклі він сталий, а ділення —
        // найдорожча операція fixed-point.
        final long hStartFixed = Fixed.fromInt(hStart);
        final long slope = forestMode ? 0
                : Fixed.div(Fixed.fromInt(hEnd - hStart), dist);

        final int  B  = TerrainMaskManager.BLOCK_SIZE;
        final long BF = Fixed.fromInt(B);

        int bx = Math.floorDiv(Fixed.toIntFloor(x1), B);
        int by = Math.floorDiv(Fixed.toIntFloor(y1), B);

        int stepX = ux > 0 ? 1 : (ux < 0 ? -1 : 0);
        int stepY = uy > 0 ? 1 : (uy < 0 ? -1 : 0);

        long tDeltaX = ux != 0 ? Fixed.abs(Fixed.div(BF, ux)) : FAR;
        long tDeltaY = uy != 0 ? Fixed.abs(Fixed.div(BF, uy)) : FAR;
        long tMaxX = ux > 0 ? Fixed.div(Fixed.fromInt((bx + 1) * B) - x1, ux)
                   : ux < 0 ? Fixed.div(Fixed.fromInt(bx * B) - x1, ux) : FAR;
        long tMaxY = uy > 0 ? Fixed.div(Fixed.fromInt((by + 1) * B) - y1, uy)
                   : uy < 0 ? Fixed.div(Fixed.fromInt(by * B) - y1, uy) : FAR;

        final int nBx = blocksX(), nBy = blocksY();
        long dEnter = 0;
        long crestRun = 0;      // довжина поточного суцільного підйому (режим висот)

        while (dEnter < limit) {
            long dExit = Fixed.min(Fixed.min(tMaxX, tMaxY), limit);

            boolean inRange = bx >= 0 && by >= 0 && bx < nBx && by < nBy;
            boolean mustWalk;
            if (!inRange) {
                mustWalk = true;
            } else if (forestMode) {
                mustWalk = blockHasForest(bx, by);
            } else {
                // Поріг монотонний по d, тож його мінімум на ділянці блоку — на
                // одному з її кінців. Беремо менший і звіряємо з найвищим
                // пікселем блоку: не дотягує — у блоці нема чого шукати.
                long ceilMin = Fixed.min(occlusionCeiling(hStartFixed, slope, dEnter),
                                         occlusionCeiling(hStartFixed, slope, dExit));
                mustWalk = Fixed.fromInt(blockMaxHeight(bx, by)) >= ceilMin;
            }

            if (mustWalk) {
                // Вибірки прив'язані до глобальної сітки кроку, щоб межі блоків
                // не зсували їх — результат такий самий, як суцільний прохід.
                long d = Fixed.max(dEnter, Fixed.max(skipNearStart, LOS_FINE_STEP_FIXED));
                d = ceilToStep(d);

                for (; d < dExit; d += LOS_FINE_STEP_FIXED) {
                    long wx = x1 + Fixed.mul(ux, d), wy = y1 + Fixed.mul(uy, d);
                    if (forestMode) {
                        if (isForestF(wx, wy)) return true;
                    } else if (Fixed.fromInt(heightF(wx, wy))
                                   >= occlusionCeiling(hStartFixed, slope, d)) {
                        crestRun += LOS_FINE_STEP_FIXED;
                        if (crestRun >= LOS_CREST_MIN_RUN_FIXED) return true;
                    } else {
                        crestRun = 0;
                    }
                }
            } else if (!forestMode) {
                // Блок пропущено, бо в ньому НЕМАЄ пікселів від порога — отже
                // підйом на ньому гарантовано уривається.
                crestRun = 0;
            }

            if (tMaxX < tMaxY) { dEnter = tMaxX; tMaxX += tDeltaX; bx += stepX; }
            else               { dEnter = tMaxY; tMaxY += tDeltaY; by += stepY; }
        }
        return false;
    }

    /** Округлення вгору до кратного {@link #LOS_FINE_STEP_FIXED} (крок = 1.0). */
    private static long ceilToStep(long raw) {
        long frac = Fixed.frac(raw);
        return frac == 0 ? raw : raw - frac + Fixed.ONE;
    }
}
