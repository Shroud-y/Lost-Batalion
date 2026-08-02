package io.jababa.lost_batalion.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Graphics;
import com.badlogic.gdx.utils.Array;

/**
 * Список роздільностей, які пропонує гра, і застосування вибраної.
 *
 * <h3>Чому список, а не вільний розмір вікна</h3>
 * Вікно навмисно нерозтяжне ({@code setResizable(false)} у лаунчері). HUD
 * рахується від {@link UIScale}, і на довільному розмірі він не ламається — але
 * перевіряти вигляд гри на нескінченній множині розмірів неможливо, а на п'яти
 * можливо. Плюс зникає найдивніший зі старих станів: вікно, розтягнуте так, що
 * панель виділення займає пів екрана.
 *
 * <h3>Що з чим звіряється</h3>
 * Пропонуються лише ті пресети, які ВЛІЗАЮТЬ у поточний монітор. Інакше вибір
 * 2560×1440 на ноутбуці з 1080p дав би вікно, більше за екран, у якого не
 * дістати ані заголовка, ані кнопки закриття.
 */
public final class ScreenResolution {

    /**
     * Пресети, від меншого до більшого. Усі 16:9, крім найменшого: рівно те
     * співвідношення, під яке рахований HUD, тож інших тут і не треба.
     */
    private static final int[][] PRESETS = {
        {1280,  720},
        {1366,  768},
        {1600,  900},
        {1920, 1080},
        {2560, 1440},
    };

    /** Розмір вікна за замовчуванням і запасний, якщо збережений не підходить. */
    public static final int DEFAULT_W = 1280;
    public static final int DEFAULT_H =  720;

    private ScreenResolution() {}

    /**
     * Режим вікна.
     *
     * <p>Три стани замість двох галочок «на весь екран» + «без рамки»: галочки
     * можна поставити обидві, і що це означає — не відповість ніхто. Режим один
     * і взаємовиключний за побудовою.
     */
    public enum WindowMode {
        /** Звичайне вікно вибраного зі списку розміру. */
        WINDOWED("У вікні"),
        /**
         * Вікно без рамки на весь монітор.
         *
         * <p>{@code setWindowedMode} у бекенді сам центрує вікно
         * ({@code calculateCenteredWindowPosition}), а вікно розміром з монітор
         * центрується рівно в нуль — окремо ставити позицію не треба, і core
         * лишається без platform-специфічного коду.
         */
        BORDERLESS("Без рамки"),
        /** Ексклюзивний повний екран через {@code setFullscreenMode}. */
        FULLSCREEN("Повний екран");

        private final String label;
        WindowMode(String label) { this.label = label; }
        public String label() { return label; }
        /** {@code SelectBox} малює пункти через {@code toString}. */
        @Override public String toString() { return label; }

        public static WindowMode parse(String name, WindowMode fallback) {
            if (name == null) return fallback;
            for (WindowMode m : values()) if (m.name().equals(name)) return m;
            return fallback;
        }
    }

    /** Застосувати режим вікна. {@code w}/{@code h} значать щось лише для {@code WINDOWED}. */
    public static void applyMode(WindowMode mode, int w, int h) {
        Graphics.DisplayMode desktop = Gdx.graphics.getDisplayMode();

        switch (mode) {
            case FULLSCREEN:
                Gdx.graphics.setFullscreenMode(desktop);
                break;

            case BORDERLESS:
                // Порядок важливий: спершу зняти рамку, потім задати розмір.
                // Навпаки вікно спершу стало б розміром з монітор ІЗ рамкою, і
                // заголовок з тінню вилізли б за край екрана.
                Gdx.graphics.setUndecorated(true);
                Gdx.graphics.setWindowedMode(desktop.width, desktop.height);
                break;

            case WINDOWED:
            default:
                Gdx.graphics.setUndecorated(false);
                Array<Mode> modes = available();
                Mode m = modes.get(indexOfSaved(modes, w, h));
                Gdx.graphics.setWindowedMode(m.w, m.h);
                break;
        }
    }

    /** Одна роздільність зі списку. */
    public static final class Mode {
        public final int w, h;
        public Mode(int w, int h) { this.w = w; this.h = h; }
        /** Підпис для інтерфейсу: {@code 1920×1080}. Знак × є у шрифті. */
        public String label() { return w + "×" + h; }
        public boolean is(int ow, int oh) { return w == ow && h == oh; }
        /** {@code SelectBox} малює пункти саме через {@code toString}. */
        @Override public String toString() { return label(); }
    }

    /**
     * Пресети, що влізають у поточний монітор.
     *
     * <p>Список ніколи не порожній: якщо не влазить навіть найменший пресет
     * (екран дрібніший за 1280×720), повертається сам розмір монітора — краще
     * один незручний варіант, ніж порожній перемикач.
     */
    public static Array<Mode> available() {
        Array<Mode> out = new Array<>();
        Graphics.DisplayMode desktop = Gdx.graphics.getDisplayMode();

        for (int[] p : PRESETS)
            if (p[0] <= desktop.width && p[1] <= desktop.height)
                out.add(new Mode(p[0], p[1]));

        if (out.isEmpty()) out.add(new Mode(desktop.width, desktop.height));
        return out;
    }

    /**
     * Фактичний розмір, який зараз диктує монітор.
     *
     * <p>Саме він потрібен спискам у режимах «повний екран» і «без рамки»: там
     * збережена віконна роздільність ні на що не впливає, і показувати її —
     * значить брехати. Гравець із 1920×1080 бачив у налаштуваннях 1280×720 і
     * мав усі підстави вважати це помилкою.
     */
    public static Mode desktopMode() {
        Graphics.DisplayMode desktop = Gdx.graphics.getDisplayMode();
        return new Mode(desktop.width, desktop.height);
    }

    /**
     * Пресети плюс розмір монітора, якщо його серед них немає.
     *
     * <p>Потрібно, щоб {@code SelectBox} мав що показати: він уміє вибрати лише
     * той елемент, який реально є в його списку, а монітор цілком може бути
     * нестандартним (2560×1080 і подібні).
     */
    public static Array<Mode> availableWithDesktop() {
        Array<Mode> out = available();
        Mode desktop = desktopMode();
        for (int i = 0; i < out.size; i++)
            if (out.get(i).is(desktop.w, desktop.h)) return out;
        out.add(desktop);
        return out;
    }

    /** Індекс збереженої роздільності в {@link #available()}; 0, якщо її там немає. */
    public static int indexOfSaved(Array<Mode> modes, int savedW, int savedH) {
        for (int i = 0; i < modes.size; i++)
            if (modes.get(i).is(savedW, savedH)) return i;
        return 0;
    }

    /**
     * Змінити розмір вікна, якщо він зараз узагалі щось значить.
     *
     * <p>У повний екран і без рамки розмір диктує монітор, і вибір зі списку
     * лише запам'ятовується на потім — до вікна його прикладати не можна.
     */
    public static void applyWindowed(WindowMode mode, int w, int h) {
        if (mode == WindowMode.WINDOWED) applyMode(WindowMode.WINDOWED, w, h);
    }
}
