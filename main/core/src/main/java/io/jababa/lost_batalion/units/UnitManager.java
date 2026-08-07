package io.jababa.lost_batalion.units;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.IntMap;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.terrain.TerrainQuery;

public class UnitManager {

    private final Array<Unit> allUnits      = new Array<>();
    private final Array<Unit> selectedUnits = new Array<>();

    /**
     * Пошук за id. Команди по мережі несуть числа, а не посилання, тож цей
     * перехід робиться на кожен наказ — лінійний обхід масиву там був би зайвим.
     */
    private final IntMap<Unit> byId = new IntMap<>();

    /**
     * Лічильник id. Зростає в порядку створення юнітів; розстановка детермінована,
     * тож на всіх клієнтах ті самі юніти отримують ті самі номери.
     */
    private int nextId = 0;

    public void addUnit(Unit unit) {
        unit.id = nextId++;
        allUnits.add(unit);
        byId.put(unit.id, unit);
    }

    /** @return юніт із таким id або null, якщо його ніколи не було */
    public Unit findById(int id) { return byId.get(id); }

    /**
     * Живі юніти команди {@code owner} із переліку id.
     *
     * <p>Мовчки пропускає невідомі та мертві id (юніт загинув між віддачею
     * наказу і тіком його виконання — звичайна гонка при input delay) і чужих
     * юнітів: інакше підроблене повідомлення дало б командувати ворожою армією.
     *
     * <p>Тут же відсіюються ЗЛАМАНІ — це і є вся реалізація некерованості.
     * Місце вибране не випадково: через цей метод проходять УСІ команди без
     * винятку ({@code GameSimulation.own}), тож новий наказ отримує правило
     * задарма і забути про нього неможливо. Перевірка в кожному з десятка
     * методів контексту рано чи пізно десь би не з'явилась — і саме тому
     * наказом, який пролазить до втікача, виявився б найрідший.
     */
    public Array<Unit> collectOwned(int[] ids, Team owner, Array<Unit> out) {
        out.clear();
        if (ids == null) return out;
        for (int i = 0; i < ids.length; i++) {
            Unit u = byId.get(ids[i]);
            if (u == null || !u.alive || u.team != owner || u.isBroken()) continue;
            out.add(u);
        }
        return out;
    }

    /** Відстань між сусідами у відділенні (Q47.16). */
    private static final long SQUAD_SPACING = Infantry.INF_SIZE_FIXED + Fixed.fromInt(8);
    /**
     * Відстань між юнітами в сітці наказу руху (Q47.16).
     *
     * <p>З появою колізій це вже не лише естетика: інтервал, менший за діаметр
     * хітбокса, означає, що юніти штовхатимуться на місці призначення замість
     * того, щоб спокійно стати.
     */
    private static final long GRID_SPACING  = Infantry.INF_SIZE_FIXED + Fixed.fromInt(12);

    public void spawnSquad(Team team, long centerX, long centerY) {
        spawnSquad(team, centerX, centerY, UnitType.INFANTRY);
    }

    /**
     * Відділення з трьох юнітів заданого типу.
     *
     * <p>Через {@link UnitType}, а не через клас: тип уже знає, як створити
     * юніта, і другого місця з таким знанням у грі бути не має — інакше новий
     * рід військ доводилось би вписувати ще й сюди.
     */
    public void spawnSquad(Team team, long centerX, long centerY, UnitType type) {
        addUnit(type.create(team, centerX - SQUAD_SPACING, centerY));
        addUnit(type.create(team, centerX, centerY));
        addUnit(type.create(team, centerX + SQUAD_SPACING, centerY));
    }

    /**
     * Один крок симуляції для всіх юнітів.
     *
     * <p>Порядок обходу — індекси {@code allUnits}, і він мусить бути однаковим
     * на всіх клієнтах: юніти впливають один на одного через бій, тож обхід у
     * різному порядку дає різний результат. Саме тому тут {@code Array}, а не
     * будь-яка колекція з хешуванням.
     *
     * @param terrain спільний доступ до обох масок; null → місцевість ігнорується
     */
    public void tick(TerrainQuery terrain, long mapW, long mapH) {
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            u.beginTick();

            long multiplier = terrain != null
                ? terrain.movementMultiplierF(u.x, u.y)
                : Fixed.ONE;
            u.tick(multiplier);
            // Поштовх ближнього бою — ПІСЛЯ власного руху: удар зсуває ціль
            // на додачу до її кроку, а не замість нього.
            u.advanceKnockback(mapW, mapH);
        }

        // Розштовхування — ПІСЛЯ руху всіх: інакше юніт, який ходить раніше за
        // сусіда, штовхав би того ще до того, як той зробив свій крок, і
        // результат залежав би від порядку в масиві сильніше, ніж треба.
        UnitSeparation.resolve(allUnits, mapW, mapH);

        // Мертві й зламані вилітають із виділення. Без нового Array на кожен
        // тік — при 40 тіках/с це були б 40 зайвих обʼєктів на секунду в сміття.
        //
        // Зламаний прибирається саме ТУТ, а не в системі моралі: виділення —
        // це пара «прапорець на юніті + список», і гасити її по частинах із
        // двох різних місць означало б рано чи пізно лишити юніта в списку без
        // прапорця. Прапорець при цьому гаситься лише йому — мертвих усе одно
        // ніхто не малює, а зламаний лишається на полі й обводка над ним
        // читалась би як «він мене слухає».
        for (int i = selectedUnits.size - 1; i >= 0; i--) {
            Unit u = selectedUnits.get(i);
            if (u.alive && !u.isBroken()) continue;
            u.selected = false;
            selectedUnits.removeIndex(i);
        }
    }

    // ── Виділення ─────────────────────────────────────────────────────────
    //
    // Виділення — суто локальний стан UI: воно в кожного своє, по мережі не
    // їздить і в симуляцію не входить. Тому тут координати приймаються у float
    // (вони приходять від камери) — жодного впливу на стан гри це не має.

    /** Клік по юніту. Обирати можна лише своїх — чужа армія не слухається. */
    public boolean trySelectAtPoint(float x, float y, boolean shift, Team owner) {
        Unit found = null;
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u == null || !u.alive || u.team != owner) continue;
            // Хітбокс, а не розмір спрайту: у частини юнітів (артилерія) фігура
            // займає лише середину картинки.
            float halfW = u.getHalfWidthPx(), halfH = u.getHalfHeightPx();
            if (x >= u.worldX() - halfW && x <= u.worldX() + halfW &&
                y >= u.worldY() - halfH && y <= u.worldY() + halfH) {
                found = u;
                break; // Беремо першого знайденого зверху
            }
        }

        if (found != null) {
            if (!shift) clearSelection();
            if (!selectedUnits.contains(found, true)) {
                found.selected = true;
                selectedUnits.add(found);
            }
            return true;
        }
        return false;
    }

    /** Виділення рамкою — теж лише своїх. */
    public void selectInRect(float rx, float ry, float rw, float rh, boolean shift, Team owner) {
        if (!shift) clearSelection();

        for (Unit u : allUnits) {
            if (!u.alive || u.team != owner) continue;
            float ux = u.worldX(), uy = u.worldY();
            if (ux >= rx && ux <= rx + rw && uy >= ry && uy <= ry + rh) {
                if (!selectedUnits.contains(u, true)) {
                    u.selected = true;
                    selectedUnits.add(u);
                }
            }
        }
    }

    public void clearSelection() {
        for (Unit u : selectedUnits) u.selected = false;
        selectedUnits.clear();
    }

    /** Id виділених юнітів — те, що піде в команду. */
    public int[] selectedIds() {
        int[] ids = new int[selectedUnits.size];
        for (int i = 0; i < selectedUnits.size; i++) ids[i] = selectedUnits.get(i).id;
        return ids;
    }

    // ── Накази руху ───────────────────────────────────────────────────────
    //
    // Усе у Q47.16 і на явному списку юнітів, а не на виділенні: викликаються
    // вони вже з тіку виконання команди, коли локальне виділення ні до чого.

    /** Шикування сіткою навколо точки. */
    public void moveUnitsTo(Array<Unit> units, long targetX, long targetY, long mapW, long mapH) {
        int count = units.size;
        if (count == 0) return;
        int cols = Math.max(1, Fixed.ceilSqrtInt(count));
        for (int i = 0; i < count; i++) {
            Unit u = units.get(i);
            int col = i % cols;
            int row = i / cols;
            // (col - cols/2) з половинкою: колонки центруються навколо цілі.
            long offset = Fixed.mul(Fixed.fromInt(col * 2 - cols) >> 1, GRID_SPACING);
            long tx = targetX + offset;
            long ty = targetY - Fixed.mul(Fixed.fromInt(row), GRID_SPACING);
            moveClamped(u, tx, ty, mapW, mapH);
        }
    }

    /** Шикування в лінію між двома точками. */
    public void moveUnitsToLine(Array<Unit> units, long x1, long y1, long x2, long y2,
                                long mapW, long mapH) {
        int count = units.size;
        if (count == 0) return;
        long dx = x2 - x1, dy = y2 - y1;
        if (Fixed.length(dx, dy) < Fixed.fromFloat(0.01f)) return;

        for (int i = 0; i < count; i++) {
            Unit u = units.get(i);
            // t = i / (count-1), одиничний юніт стає посередині
            long t = count == 1 ? Fixed.HALF : Fixed.divInt(Fixed.fromInt(i), count - 1);
            moveClamped(u, x1 + Fixed.mul(dx, t), y1 + Fixed.mul(dy, t), mapW, mapH);
        }
    }

    /**
     * Рівномірне шикування вздовж ламаної.
     *
     * <p>Точки приходять уже проріджені автором наказу: густота сирого сліду
     * курсора залежить від його FPS, а прорідити її однаково на всіх клієнтах
     * неможливо.
     *
     * @param pointsXY пари координат підряд: x0, y0, x1, y1, … (Q47.16)
     */
    public void moveUnitsAlongPath(Array<Unit> units, long[] pointsXY, long mapW, long mapH) {
        int count  = units.size;
        int points = pointsXY == null ? 0 : pointsXY.length / 2;
        if (count == 0 || points < 2) return;

        long[] segLens = new long[points - 1];
        long totalLen = 0;
        for (int i = 0; i < points - 1; i++) {
            segLens[i] = Fixed.length(pointsXY[(i + 1) * 2]     - pointsXY[i * 2],
                                      pointsXY[(i + 1) * 2 + 1] - pointsXY[i * 2 + 1]);
            totalLen  += segLens[i];
        }
        if (totalLen < Fixed.fromFloat(0.01f)) return;

        for (int ui = 0; ui < count; ui++) {
            long t      = count == 1 ? Fixed.HALF : Fixed.divInt(Fixed.fromInt(ui), count - 1);
            long target = Fixed.mul(t, totalLen);

            long accumulated = 0;
            long px = pointsXY[0], py = pointsXY[1];

            for (int si = 0; si < segLens.length; si++) {
                long ax = pointsXY[si * 2],       ay = pointsXY[si * 2 + 1];
                long bx = pointsXY[(si + 1) * 2], by = pointsXY[(si + 1) * 2 + 1];
                if (accumulated + segLens[si] >= target) {
                    long localT = segLens[si] == 0 ? 0 : Fixed.div(target - accumulated, segLens[si]);
                    px = ax + Fixed.mul(bx - ax, localT);
                    py = ay + Fixed.mul(by - ay, localT);
                    break;
                }
                accumulated += segLens[si];
                px = bx; py = by;
            }

            moveClamped(units.get(ui), px, py, mapW, mapH);
        }
    }

    /**
     * Кожному — та сама поправка до ЙОГО позиції.
     *
     * <p>Ніякої сітки, на відміну від {@link #moveUnitsTo}: стрій має лишитись
     * той самий, лише переїхати. Тому й обрізання по краю карти діє на кожного
     * окремо — той, хто вперся в межу, стане біля неї, а решта пройде повний
     * зсув. Зсувати весь загін на найменший спільний крок було б «правильніше»
     * геометрично, але означало б, що один юніт біля краю спиняє всю роту.
     *
     * <p>Позиція береться ПОТОЧНА, а не остання ціль: наказ читається як
     * «звідси — туди», і юніт на марші зсувається від того місця, де він
     * зараз, а не від того, куди йшов.
     */
    public void offsetUnits(Array<Unit> units, long dx, long dy, long mapW, long mapH) {
        for (int i = 0; i < units.size; i++) {
            Unit u = units.get(i);
            moveClamped(u, u.x + dx, u.y + dy, mapW, mapH);
        }
    }

    private void moveClamped(Unit u, long tx, long ty, long mapW, long mapH) {
        // sizeFixed, а НЕ півосі хітбокса: це відступ ЦІЛІ від краю карти, він
        // однаковий по обох осях і мусить лишитись тим самим числом, що й до
        // появи витягнутих хітбоксів — інакше зсунулись би всі накази руху
        // вздовж межі, у тому числі в уже зіграних матчах.
        long half = u.sizeFixed() >> 1;
        u.moveTo(Fixed.clamp(tx, half, mapW - half),
                 Fixed.clamp(ty, half, mapH - half));
    }

    // ── Знімок стану (ресинк) ─────────────────────────────────────────────

    /** Дискримінатор типу юніта у знімку. Значення — частина формату. */
    private static final byte KIND_INFANTRY  = 0;
    private static final byte KIND_ARTILLERY = 1;
    private static final byte KIND_CAVALRY   = 2;
    private static final byte KIND_HEAVY_CAV = 3;
    private static final byte KIND_LIGHT_INF = 4;

    public void writeSnapshot(java.io.DataOutputStream out) throws java.io.IOException {
        out.writeInt(nextId);
        out.writeInt(allUnits.size);
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            out.writeByte(u instanceof Artillery     ? KIND_ARTILLERY
                        : u instanceof HeavyCavalry  ? KIND_HEAVY_CAV
                        : u instanceof Cavalry       ? KIND_CAVALRY
                        : u instanceof LightInfantry ? KIND_LIGHT_INF
                                                     : KIND_INFANTRY);
            out.writeInt(u.team.ordinal());
            u.writeSnapshot(out);
        }
    }

    /**
     * Відновити список юнітів зі знімка.
     *
     * <p>Юніти створюються заново, а не оновлюються на місці: після
     * розбіжності склад армій теж міг розійтись, і зіставляти «хто тут зайвий»
     * складніше й ризикованіше, ніж побудувати все з нуля. Ціна — втрачене
     * виділення, і воно свідомо не відновлюється: це локальний стан UI, у
     * кожного свій, і в знімку його немає.
     *
     * <p>Характеристики (швидкість, урон, дальність) не пишуться в знімок
     * узагалі — вони сталі й задаються конструктором. Писати їх означало б
     * дозволити знімку від однієї збірки нав'язати чужий баланс.
     */
    public void readSnapshot(java.io.DataInputStream in) throws java.io.IOException {
        allUnits.clear();
        selectedUnits.clear();
        byId.clear();

        nextId = in.readInt();
        int count = in.readInt();
        Team[] teams = Team.values();

        for (int i = 0; i < count; i++) {
            byte kind = in.readByte();
            Team team = teams[in.readInt()];

            Unit u = kind == KIND_ARTILLERY ? new Artillery(team, 0, 0)
                   : kind == KIND_HEAVY_CAV ? new HeavyCavalry(team, 0, 0)
                   : kind == KIND_CAVALRY   ? new Cavalry(team, 0, 0)
                   : kind == KIND_LIGHT_INF ? new LightInfantry(team, 0, 0)
                                            : new Infantry(team, 0, 0);
            u.readSnapshot(in);

            allUnits.add(u);
            byId.put(u.id, u);
        }

        // Другий прохід: ручні цілі артилерії — це посилання, а посилатись
        // можна лише на юніта, який уже створений.
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (!(u instanceof Artillery)) continue;
            Artillery a = (Artillery) u;
            a.manualTarget = a.pendingManualTargetId < 0 ? null
                                                         : byId.get(a.pendingManualTargetId);
            a.pendingManualTargetId = -1;
            a.aimTarget = a.pendingAimTargetId < 0 ? null
                                                   : byId.get(a.pendingAimTargetId);
            a.pendingAimTargetId = -1;
        }
    }

    public Array<Unit> getAllUnits() { return allUnits; }
    public Array<Unit> getSelectedUnits() { return selectedUnits; }
    public boolean hasSelection() { return selectedUnits.size > 0; }
}
