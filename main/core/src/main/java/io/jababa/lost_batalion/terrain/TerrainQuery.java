package io.jababa.lost_batalion.terrain;

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
     * Раніше константа була продубльована у VisibilitySystem і FogOfWarRenderer;
     * якщо копії розходились, ALT-оверлей починав брехати про реальну видимість.
     */
    public static final float ELEVATION_BLOCK_MARGIN = 0.5f;

    /** Базова висота рівнини — для NONE/FOREST, які не несуть даних про ярус. */
    public static final float PLAINS_BASELINE_HEIGHT = 3f;

    /**
     * Крок вибірки вздовж променя/відрізка. 1 світова одиниця = 1 піксель маски,
     * тобто це межа роздільності даних — дрібніше немає чого шукати.
     */
    public static final float LOS_FINE_STEP = 1f;

    /**
     * Ліс ближче за цю відстань до спостерігача не перекриває йому огляд.
     * Інакше юніт, що стоїть у лісі, осліп би повністю: власний кущ рахувався б
     * як перешкода. Вплив свого лісу враховує окремо sightMod (×0.55).
     *
     * Спільна константа для VisibilitySystem і ALT-оверлея — щоб оверлей
     * показував рівно те, що система видимості реально рахує.
     */
    public static final float LOS_ORIGIN_FOREST_SKIP = 12f;

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

    /** Чи є ліс у цій точці. */
    public boolean isForest(float x, float y) {
        return forestMask != null && forestMask.isForestAt(x, y);
    }

    /**
     * Тип місцевості за висотою.
     *
     * Річка перевіряється ПЕРШОЮ і саме в масці лісу — вона намальована там,
     * а не в топографічній масці.
     */
    public TerrainType elevation(float x, float y) {
        if (forestMask != null && forestMask.getElevationAt(x, y) == TerrainType.RIVER)
            return TerrainType.RIVER;

        return elevationMask != null ? elevationMask.getElevationAt(x, y) : TerrainType.NONE;
    }

    /**
     * Числова висота в точці — для математики перекриття лінії зору.
     *
     * Висоту дає ВИКЛЮЧНО топографічна маска: вона єдиний авторитет по рельєфу.
     * Річка — це поверхнева деталь, а не яма: вона впливає на рух і бій, але не
     * змінює висоту (у топографічній масці під річками порожньо → NONE → 3).
     */
    public float height(float x, float y) {
        return elevationMask != null
            ? heightOf(elevationMask.getElevationAt(x, y))
            : PLAINS_BASELINE_HEIGHT;
    }

    /**
     * Числова висота ярусу. Єдине джерело правди для LOS-математики.
     *
     * RIVER має ту саму висоту, що й рівнина: юніт у воді не сидить у каньйоні —
     * він не сліпне і не ховається. Раніше тут стояв 0, але це був МЕРТВИЙ код:
     * усі запити висоти йшли в топографічну маску, де річок немає взагалі.
     * Щойно річки під'єднали правильно, 0 ожив і зробив із кожної річки ущелину.
     */
    public static float heightOf(TerrainType t) {
        switch (t) {
            case RIVER:         return PLAINS_BASELINE_HEIGHT;
            case LOWLANDS:      return 1f;
            case PRE_LOWLANDS:  return 2f;
            case PLAINS:
            case PLAINS_ALT:    return 3f;
            case PRE_HIGHLANDS: return 4f;
            case HIGHLANDS:     return 5f;
            default:            return PLAINS_BASELINE_HEIGHT;   // NONE/FOREST
        }
    }

    /** Множник швидкості руху в цій точці (ліс × річка/височини). */
    public float movementMultiplier(float x, float y) {
        return TerrainMovementModifier.getMultiplier(elevation(x, y), isForest(x, y));
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
     * Межі висоти в блоці — тільки з топографічної маски, бо {@link #height}
     * читає теж лише її. Межі й точкові значення беруться з одного джерела, тому
     * вони узгоджені за побудовою.
     */
    public int blockMinHeight(int bx, int by) {
        return elevationMask != null ? elevationMask.blockMinHeight(bx, by)
                                     : (int) PLAINS_BASELINE_HEIGHT;
    }

    public int blockMaxHeight(int bx, int by) {
        return elevationMask != null ? elevationMask.blockMaxHeight(bx, by)
                                     : (int) PLAINS_BASELINE_HEIGHT;
    }

    // ── Запити вздовж відрізка (лінія зору між двома юнітами) ─────────────────

    /**
     * Чи перетинає відрізок ліс.
     *
     * @param skipNearStart перші стільки одиниць від початку ігноруються
     *                      (див. {@link #LOS_ORIGIN_FOREST_SKIP})
     */
    public boolean hasForestOnSegment(float x1, float y1, float x2, float y2,
                                      float skipNearStart) {
        return segmentHits(x1, y1, x2, y2, true, 0f, skipNearStart);
    }

    /**
     * Чи є на відрізку земля вища за {@code ceiling} — тобто гребінь, що
     * перекриває лінію зору. Обидва кінці виключені: юніт не перекриває сам себе.
     */
    public boolean hasGroundAboveOnSegment(float x1, float y1, float x2, float y2,
                                           float ceiling) {
        return segmentHits(x1, y1, x2, y2, false, ceiling, 0f);
    }

    /**
     * Спільний обхід відрізка блоками (DDA) з детальним доходженням лише там,
     * де це потрібно.
     *
     * <p>Тут стеля СТАЛА вздовж усього відрізка (вона рахується з висот обох
     * кінців, які відомі наперед), тому пропуск блоку тривіально точний:
     * {@code blockMax <= ceiling} означає, що жоден піксель у блоці стелю не
     * пробиває. Для лісу так само: немає лісу в блоці — немає що шукати.
     * Це простіше за промінь ALT-оверлея, де стеля залежить від поточної точки,
     * а гребінь накопичується.
     *
     * @param forestMode true → шукаємо ліс; false → шукаємо землю вище стелі
     */
    private boolean segmentHits(float x1, float y1, float x2, float y2,
                                boolean forestMode, float ceiling, float skipNearStart) {
        float dx = x2 - x1, dy = y2 - y1;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist <= LOS_FINE_STEP) return false;

        float ux = dx / dist, uy = dy / dist;

        final int B = TerrainMaskManager.BLOCK_SIZE;
        int bx = (int) Math.floor(x1 / B);
        int by = (int) Math.floor(y1 / B);

        int stepX = ux > 0 ? 1 : (ux < 0 ? -1 : 0);
        int stepY = uy > 0 ? 1 : (uy < 0 ? -1 : 0);

        float tDeltaX = ux != 0f ? Math.abs(B / ux) : Float.MAX_VALUE;
        float tDeltaY = uy != 0f ? Math.abs(B / uy) : Float.MAX_VALUE;
        float tMaxX = ux > 0 ? ((bx + 1) * B - x1) / ux
                    : ux < 0 ? (bx * B - x1) / ux : Float.MAX_VALUE;
        float tMaxY = uy > 0 ? ((by + 1) * B - y1) / uy
                    : uy < 0 ? (by * B - y1) / uy : Float.MAX_VALUE;

        final int nBx = blocksX(), nBy = blocksY();
        float dEnter = 0f;

        while (dEnter < dist) {
            float dExit = Math.min(Math.min(tMaxX, tMaxY), dist);

            boolean inRange = bx >= 0 && by >= 0 && bx < nBx && by < nBy;
            boolean mustWalk = !inRange
                || (forestMode ? blockHasForest(bx, by)
                               : blockMaxHeight(bx, by) > ceiling);

            if (mustWalk) {
                // Вибірки прив'язані до глобальної сітки кроку, щоб межі блоків
                // не зсували їх — результат такий самий, як суцільний прохід.
                float d = Math.max(dEnter, Math.max(skipNearStart, LOS_FINE_STEP));
                d = (float) Math.ceil(d / LOS_FINE_STEP) * LOS_FINE_STEP;

                for (; d < dExit; d += LOS_FINE_STEP) {
                    float wx = x1 + ux * d, wy = y1 + uy * d;
                    if (forestMode) {
                        if (isForest(wx, wy)) return true;
                    } else if (height(wx, wy) > ceiling) {
                        return true;
                    }
                }
            }

            if (tMaxX < tMaxY) { dEnter = tMaxX; tMaxX += tDeltaX; bx += stepX; }
            else               { dEnter = tMaxY; tMaxY += tDeltaY; by += stepY; }
        }
        return false;
    }
}
