package io.jababa.lost_batalion.units;

import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.math.Fixed;

/**
 * Розштовхування юнітів, що налізли один на одного.
 *
 * <h3>Чому саме так, а не «справжня фізика»</h3>
 * Юніти не мають маси, інерції й не блокують рух — вони просто не повинні
 * стояти в одній точці. Тому замість колізій із зупинкою тут м'яке
 * розсування: якщо два хітбокси перетнулись, обидва відсуваються на половину
 * перекриття. Такий підхід не заклинює строй у вузькому місці й не потребує
 * перерахунку шляху, коли попереду хтось став.
 *
 * <h3>Детермінізм</h3>
 * Обхід — подвійний цикл за індексами масиву, той самий порядок на всіх
 * клієнтах. Штовхання симетричне й рахується у {@link Fixed}. Ніяких
 * просторових хешів: {@code HashMap} тут дав би різний порядок обходу і
 * різні позиції.
 *
 * <h3>Складність</h3>
 * O(n²) на тік. При 22 юнітах це 231 пара — дешевше за одне звернення до
 * маски місцевості. Коли армії виростуть за пару сотень, сюди знадобиться
 * сітка сусідства; робити її наперед немає сенсу, а робити на хешах — не можна.
 */
public final class UnitSeparation {

    private UnitSeparation() {}

    /**
     * Скільки перекриття прибирати за один тік.
     *
     * <p>Не одиниця: розсунути миттєво означає, що двоє, яких стиснув строй,
     * стрибатимуть один від одного щотіку. Часткове розведення гасить
     * коливання само собою за кілька тіків.
     */
    private static final long RESOLVE_FRACTION = Fixed.fromFloat(0.5f);

    /**
     * Мінімальний зсув, який ще має сенс. Нижче цього — тремтіння на місці,
     * яке нікому не видно, але яке щотіку міняє позиції й хеш стану.
     */
    private static final long MIN_PUSH = Fixed.fromFloat(0.02f);

    /**
     * Розсунути всіх, хто перетинається.
     *
     * @param mapW ширина карти (Q47.16) — за межі не виштовхуємо
     */
    public static void resolve(Array<Unit> units, long mapW, long mapH) {
        for (int i = 0; i < units.size; i++) {
            Unit a = units.get(i);
            if (!a.alive) continue;

            for (int j = i + 1; j < units.size; j++) {
                Unit b = units.get(j);
                if (!b.alive) continue;

                long minGap = a.hitRadiusFixed() + b.hitRadiusFixed();
                long dx = b.x - a.x, dy = b.y - a.y;

                // Спершу дешева відсічка по квадратах: корінь потрібен лише
                // тим парам, що справді перетнулись.
                long distSq = Fixed.lengthSq(dx, dy);
                if (distSq >= Fixed.mul(minGap, minGap)) continue;

                long dist = Fixed.sqrt(distSq);

                long ux, uy;
                if (dist <= 0) {
                    // Рівно в одній точці: напрямок треба взяти хоч якийсь, але
                    // ОДНАКОВИЙ у всіх. Id для цього годиться, час чи випадок — ні.
                    boolean sideways = ((a.id + b.id) & 1) == 0;
                    ux = sideways ? Fixed.ONE : 0;
                    uy = sideways ? 0 : Fixed.ONE;
                    dist = 0;
                } else {
                    ux = Fixed.div(dx, dist);
                    uy = Fixed.div(dy, dist);
                }

                long overlap = minGap - dist;
                long push    = Fixed.mul(Fixed.mul(overlap, RESOLVE_FRACTION), Fixed.HALF);
                if (push < MIN_PUSH) continue;

                long offX = Fixed.mul(ux, push), offY = Fixed.mul(uy, push);

                a.x = clamp(a.x - offX, a.hitRadiusFixed(), mapW - a.hitRadiusFixed());
                a.y = clamp(a.y - offY, a.hitRadiusFixed(), mapH - a.hitRadiusFixed());
                b.x = clamp(b.x + offX, b.hitRadiusFixed(), mapW - b.hitRadiusFixed());
                b.y = clamp(b.y + offY, b.hitRadiusFixed(), mapH - b.hitRadiusFixed());
            }
        }
    }

    private static long clamp(long v, long lo, long hi) {
        if (hi < lo) return v;      // юніт більший за карту — не наша турбота
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
