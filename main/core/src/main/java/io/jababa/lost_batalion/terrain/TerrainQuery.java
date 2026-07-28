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
     * Наскільки (в ярусах висоти) місцевість має підніматися над лінією зору,
     * щоб перекрити її.
     *
     * Усі яруси — цілі числа, тому запас працює як поріг «на скільки ярусів
     * гребінь має підніматися над ОБОМА кінцями лінії»:
     *   запас 0.5 → досить 1 ярусу;
     *   запас 1.0 → потрібно 2 яруси.
     *
     * При 0.5:
     *   HIGHLANDS(5) перекриває огляд через PLAINS(3)              → перекрито
     *   PRE_HIGHLANDS(4) перекриває огляд через PLAINS(3)          → перекрито
     *   юніт, що стоїть НА підйомі(4), видно з рівнини(3):
     *       стеля = max(3,4)+0.5 = 4.5, гребінь 4, 4 > 4.5 хибно   → видно
     *   юніт на плато HIGHLANDS дивиться вздовж свого ж плато:
     *       стеля = 5.5, гребінь 5                                 → видно
     *
     * Було 1.0, і тоді PRE_HIGHLANDS (18.5% карти!) не перекривав узагалі
     * нічого, окрім нізин — висоти виглядали декоративними.
     *
     * <p>Симуляція цю константу не використовує: для ЦІЛИХ висот «вище за
     * max+0.5» тотожне «>= max+1», і саме так це записано в
     * {@link #blockingHeightFor(int, int)}. Половинка лишається тільки в
     * рендері оверлея, де математика йде по-старому у float.
     */
    public static final float ELEVATION_BLOCK_MARGIN = 0.5f;

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
     * Найменший ярус, що вже перекриває огляд між двома точками з висотами
     * {@code hA} і {@code hB}.
     *
     * <p>Раніше це записувалось як {@code max(hA,hB) + ELEVATION_BLOCK_MARGIN}
     * і порівнювалось у float. Для цілих ярусів «вище за max+0.5» — це рівно
     * «не менше за max+1», тож половинки не потрібні, а разом із ними зникає
     * останній float із гарячого шляху видимості.
     */
    public static int blockingHeightFor(int hA, int hB) {
        return Math.max(hA, hB) + 1;
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
        return segmentHits(x1, y1, x2, y2, true, 0, skipNearStart, skipNearEnd);
    }

    /**
     * Чи є на відрізку гребінь з ярусом не нижчим за {@code blockingHeight}, що
     * перекриває лінію зору. Обидва кінці виключені: юніт не перекриває сам себе.
     *
     * <p>Одного пікселя замало — підйом має протриматись
     * {@link #LOS_CREST_MIN_RUN} одиниць поспіль, інакше зубчаста межа ярусу
     * рвала б видимість від кожного кутового пікселя (та сама причина, що й
     * «штрихи» в ALT-оверлеї).
     */
    public boolean hasGroundAboveOnSegmentF(long x1, long y1, long x2, long y2,
                                            int blockingHeight) {
        return segmentHits(x1, y1, x2, y2, false, blockingHeight, 0, 0);
    }

    /** Float-обгортки для рендеру. */
    public boolean hasForestOnSegment(float x1, float y1, float x2, float y2,
                                      float skipNearStart, float skipNearEnd) {
        return hasForestOnSegmentF(Fixed.fromFloat(x1), Fixed.fromFloat(y1),
                                   Fixed.fromFloat(x2), Fixed.fromFloat(y2),
                                   Fixed.fromFloat(skipNearStart),
                                   Fixed.fromFloat(skipNearEnd));
    }

    public boolean hasGroundAboveOnSegment(float x1, float y1, float x2, float y2,
                                           float ceiling) {
        // Стеля в рендері — дробова (max + 0.5); у цілих ярусах перекриває
        // все, що не нижче за наступний ярус.
        return hasGroundAboveOnSegmentF(Fixed.fromFloat(x1), Fixed.fromFloat(y1),
                                        Fixed.fromFloat(x2), Fixed.fromFloat(y2),
                                        (int) Math.floor(ceiling) + 1);
    }

    /**
     * Спільний обхід відрізка блоками (DDA) з детальним доходженням лише там,
     * де це потрібно. Уся математика — цілочисельна.
     *
     * <p>Тут поріг перекриття СТАЛИЙ уздовж усього відрізка (він рахується з
     * висот обох кінців, які відомі наперед), тому пропуск блоку тривіально
     * точний: {@code blockMax < blockingHeight} означає, що жоден піксель у
     * блоці порога не сягає. Для лісу так само: немає лісу в блоці — немає що
     * шукати. Це простіше за промінь ALT-оверлея, де поріг залежить від
     * поточної точки, а гребінь накопичується.
     *
     * <p>У режимі висот блокує не окремий піксель, а лише суцільний підйом
     * завдовжки {@link #LOS_CREST_MIN_RUN} — лічильник {@code crestRun}. Він
     * обнуляється і на пікселі нижче порога, і на пропущеному блоці: пропуск
     * дозволений лише тоді, коли в блоці гарантовано немає жодного пікселя від
     * порога, тобто підйом там точно уривається.
     *
     * @param forestMode true → шукаємо ліс; false → шукаємо землю від порога
     */
    private boolean segmentHits(long x1, long y1, long x2, long y2,
                                boolean forestMode, int blockingHeight,
                                long skipNearStart, long skipNearEnd) {
        long dx = x2 - x1, dy = y2 - y1;
        long dist = Fixed.length(dx, dy);

        // Сліпа зона біля цілі: обхід просто закінчується раніше.
        long limit = dist - skipNearEnd;
        if (limit <= LOS_FINE_STEP_FIXED) return false;

        long ux = Fixed.div(dx, dist), uy = Fixed.div(dy, dist);

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
            boolean mustWalk = !inRange
                || (forestMode ? blockHasForest(bx, by)
                               : blockMaxHeight(bx, by) >= blockingHeight);

            if (mustWalk) {
                // Вибірки прив'язані до глобальної сітки кроку, щоб межі блоків
                // не зсували їх — результат такий самий, як суцільний прохід.
                long d = Fixed.max(dEnter, Fixed.max(skipNearStart, LOS_FINE_STEP_FIXED));
                d = ceilToStep(d);

                for (; d < dExit; d += LOS_FINE_STEP_FIXED) {
                    long wx = x1 + Fixed.mul(ux, d), wy = y1 + Fixed.mul(uy, d);
                    if (forestMode) {
                        if (isForestF(wx, wy)) return true;
                    } else if (heightF(wx, wy) >= blockingHeight) {
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
