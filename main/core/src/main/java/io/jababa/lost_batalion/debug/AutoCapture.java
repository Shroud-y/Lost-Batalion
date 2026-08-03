package io.jababa.lost_batalion.debug;

import com.badlogic.gdx.Gdx;

/**
 * Знімки за розкладом і вихід — режим «запустись, покажи себе, закрийся».
 *
 * <p>Сенс у тому, щоб кадр гри можна було отримати БЕЗ людини за клавіатурою:
 * гра стартує, сама знімається на заданих секундах і сама завершується. Інакше
 * будь-яка перевірка вигляду впирається в того, хто зараз перед монітором.
 *
 * <p>Керується системними властивостями, а не налаштуваннями: це інструмент
 * розробки, і в меню йому робити нічого. Нічого не задано — клас мовчить і не
 * коштує нічого.
 *
 * <pre>
 * -Dlb.shotAt=2,6      зняти на 2-й і 6-й секунді (обов'язкове, решта — ні)
 * -Dlb.shotDir=шлях    куди складати (типово screenshots/)
 * -Dlb.shotExit=true   вийти після останнього знімка
 * -Dlb.autoMatch=true  стартувати одразу в матч, повз меню (див. LostBatalion)
 * </pre>
 *
 * <p>Час рахується від запуску РЕАЛЬНИМ часом кадрів, а не таймером системи:
 * знімок має статись після того, як кадр намальовано, і прив'язка до
 * {@code render} — єдиний спосіб це гарантувати.
 */
public final class AutoCapture {

    private final float[] times;
    private final String  dir;
    private final boolean exitWhenDone;

    private float elapsed;
    private int   next;

    private AutoCapture(float[] times, String dir, boolean exitWhenDone) {
        this.times        = times;
        this.dir          = dir;
        this.exitWhenDone = exitWhenDone;
    }

    /** @return налаштований знімач або {@code null}, якщо режим не ввімкнено */
    public static AutoCapture fromSystemProperties() {
        String at = System.getProperty("lb.shotAt");
        if (at == null || at.trim().isEmpty()) return null;

        String[] parts = at.split(",");
        float[] times = new float[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                float t = Float.parseFloat(p.trim());
                if (t >= 0f) times[n++] = t;
            } catch (NumberFormatException ignored) {
                // Один зіпсований елемент не привід глушити весь режим.
            }
        }
        if (n == 0) return null;

        float[] trimmed = new float[n];
        System.arraycopy(times, 0, trimmed, 0, n);
        java.util.Arrays.sort(trimmed);   // розклад мусить іти по зростанню

        AutoCapture c = new AutoCapture(
            trimmed,
            System.getProperty("lb.shotDir"),
            Boolean.parseBoolean(System.getProperty("lb.shotExit")));
        Gdx.app.log("SCREENSHOT", "автознімки на " + java.util.Arrays.toString(trimmed)
                  + " с, вихід після: " + c.exitWhenDone);
        return c;
    }

    /**
     * Викликати В КІНЦІ кадру, після того як усе намальовано.
     *
     * <p>За один кадр знімається не більше одного разу навіть тоді, коли розклад
     * щільніший за частоту кадрів: два PNG з одного й того самого буфера — це
     * два однакові файли.
     */
    public void update(float delta) {
        if (next >= times.length) return;

        elapsed += delta;
        if (elapsed < times[next]) return;

        Screenshot.capture(dir, String.format("lb-%05.1fs", times[next]).replace(',', '.'));
        next++;

        if (next >= times.length && exitWhenDone) {
            Gdx.app.log("SCREENSHOT", "розклад вичерпано — вихід");
            Gdx.app.exit();
        }
    }
}
