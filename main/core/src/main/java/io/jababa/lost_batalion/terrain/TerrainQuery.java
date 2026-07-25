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
}
