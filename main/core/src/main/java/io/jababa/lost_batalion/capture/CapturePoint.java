package io.jababa.lost_batalion.capture;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;

/**
 * Стратегічна точка — село на карті, яке можна захопити.
 *
 * <p>Зона захоплення — опуклий чотирикутник, заданий сценарієм
 * ({@link CaptureZone}). Раніше тут стояли центр із маски й спільне для всіх
 * коло радіуса 70: форма села ні на що не впливала, і межа могла проходити
 * посеред річки. Тепер межу малює автор карти.
 *
 * <p>Весь стан цілочисельний і входить у checksum: захоплення змінює хід матчу,
 * тож розійтись у ньому не можна так само, як у позиціях юнітів.
 */
public class CapturePoint {

    /** Позначка для гравця — «A», «B», «C». Приходить зі сценарію. */
    public final String name;

    /** Вершини зони у Q47.16, підряд: x0,y0, x1,y1, x2,y2, x3,y3. */
    public final long[] corners = new long[8];

    /**
     * Центр зони, Q47.16 — середнє чотирьох вершин.
     *
     * <p>У саму механіку захоплення не входить (вона питає {@link #contains}),
     * але потрібен усьому, що показує точку: підпису, мінікарті, наказам.
     */
    public final long x, y;

    /** Хто вже володіє точкою; {@code null} — нейтральна. */
    public Team owner;

    /**
     * Чий прогрес зараз накопичений. Може не збігатися з {@link #owner}: поки
     * чужа сторона стоїть у зоні, спершу зривається старе володіння
     * ({@code progress} падає до нуля), і лише потім починає рости нове.
     */
    public Team holder;

    /** Накопичений прогрес у «одиницях захоплення», 0..{@link CaptureManager#FULL}. */
    public int progress;

    public CapturePoint(CaptureZone zone) {
        this.name = zone.name;

        long sumX = 0, sumY = 0;
        for (int i = 0; i < 4; i++) {
            corners[i * 2]     = Fixed.fromInt(zone.corners[i * 2]);
            corners[i * 2 + 1] = Fixed.fromInt(zone.corners[i * 2 + 1]);
            sumX += corners[i * 2];
            sumY += corners[i * 2 + 1];
        }
        // Ділення цілих на 4 — точне й однакове скрізь; Fixed.divInt тут зайвий.
        this.x = sumX / 4;
        this.y = sumY / 4;
    }

    /**
     * Чи лежить точка {@code (px, py)} у зоні (Q47.16).
     *
     * <p>Класична перевірка для ОПУКЛОГО многокутника: точка всередині, якщо
     * лежить з одного боку від усіх чотирьох ребер. Бік визначається знаком
     * векторного добутку ребра на вектор до точки; нуль означає «рівно на
     * ребрі» і зараховується всередину, інакше юніт, що став точно на межу,
     * блимав би між станами.
     *
     * <p>Обхід вершин може бути будь-який: замість фіксованого знака
     * накопичуються прапорці «був додатний» і «був від'ємний», і зона вважається
     * пройденою, поки трапився лише один із них.
     *
     * <p>Множення через {@link Fixed#mul} не лише заради масштабу: різниці
     * координат на карті 1440×1440 дають до 94 млн у Q47.16, а їхній прямий
     * добуток — до 8.8·10¹⁵. У {@code long} це ще вміщується, але запас до межі
     * менший за два порядки, і на більшій карті добуток мовчки переповнився б.
     */
    public boolean contains(long px, long py) {
        boolean positive = false, negative = false;

        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;

            long ex = corners[j * 2]     - corners[i * 2];
            long ey = corners[j * 2 + 1] - corners[i * 2 + 1];
            long tx = px - corners[i * 2];
            long ty = py - corners[i * 2 + 1];

            long cross = Fixed.mul(ex, ty) - Fixed.mul(ey, tx);
            if (cross > 0) positive = true;
            else if (cross < 0) negative = true;

            if (positive && negative) return false;
        }
        return true;
    }

    /** Частка захоплення, 0..1 — лише для рендеру. */
    public float fill() {
        return progress / (float) CaptureManager.FULL;
    }
}
