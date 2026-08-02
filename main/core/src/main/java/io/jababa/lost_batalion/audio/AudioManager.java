package io.jababa.lost_batalion.audio;

import com.badlogic.gdx.audio.Sound;
import io.jababa.lost_batalion.LostBatalion;

/**
 * Єдина шина гучності. Через неї проходить КОЖЕН звук у грі.
 *
 * <h3>Навіщо</h3>
 * Повзунок гучності в налаштуваннях писав значення в {@code Preferences}, і на
 * цьому все закінчувалось: постріл грався з {@code CombatManager.soundVolume =
 * 0.35f} — константи, яку ніхто не читав із налаштувань. Тобто повзунок працював
 * рівно ні на що. Тепер джерело гучності одне, і новий звук неможливо додати
 * повз нього, не написавши {@code sound.play(...)} власноруч.
 *
 * <h3>Три рівні</h3>
 * <ul>
 *   <li><b>master</b> — множиться на все;</li>
 *   <li><b>music</b> — фонова музика;</li>
 *   <li><b>sfx</b> — постріли, вибухи, інтерфейс.</li>
 * </ul>
 * Роздільні, бо музику прибирають майже завжди, а звук бою — майже ніколи:
 * одним повзунком це не виражається.
 *
 * <p>Понад них кожен окремий звук має власний {@code gain} — його «природну»
 * гучність відносно решти (постріл тихіший за вибух). Це властивість ассета, а
 * не налаштування, тому вона лишається в коді.
 *
 * <h3>Кеш</h3>
 * Значення читаються з {@code Preferences} один раз і живуть у полях: постріли
 * йдуть десятками за секунду, а кожен {@code getPrefs()} — це похід у файлову
 * абстракцію. {@link #refresh()} перечитує їх, коли гравець посунув повзунок.
 *
 * <p>Ліниво, а не в статичному ініціалізаторі: {@code Gdx.app} на момент
 * завантаження класу може ще не існувати.
 */
public final class AudioManager {

    private static boolean loaded;

    private static float master = 1f;
    private static float music  = 0.7f;
    private static float sfx    = 1f;

    private AudioManager() {}

    // ── Читання ───────────────────────────────────────────────────────────

    public static float master() { ensureLoaded(); return master; }
    public static float music()  { ensureLoaded(); return music;  }
    public static float sfx()    { ensureLoaded(); return sfx;    }

    /** Підсумкова гучність музики — те, що йде в {@code Music.setVolume}. */
    public static float musicVolume() {
        ensureLoaded();
        return master * music;
    }

    /**
     * Підсумкова гучність ефекту.
     *
     * @param gain власна гучність цього звуку відносно інших (0..1)
     */
    public static float sfxVolume(float gain) {
        ensureLoaded();
        return master * sfx * gain;
    }

    /**
     * Програти ефект. Тихо нічого не робить, якщо звук не завантажився —
     * ассети читаються з {@code exists()}-перевіркою і цілком можуть бути null.
     *
     * @return id відтворення або -1
     */
    public static long playSfx(Sound sound, float gain) {
        if (sound == null) return -1L;
        float v = sfxVolume(gain);
        // Нуль теж вартий пропуску: беззвучне відтворення однаково займає канал.
        if (v <= 0f) return -1L;
        return sound.play(v);
    }

    // ── Запис ─────────────────────────────────────────────────────────────

    public static void setMaster(float v) { LostBatalion.Settings.setVolume(clamp(v));      refresh(); }
    public static void setMusic(float v)  { LostBatalion.Settings.setMusicVolume(clamp(v)); refresh(); }
    public static void setSfx(float v)    { LostBatalion.Settings.setSfxVolume(clamp(v));   refresh(); }

    /**
     * Перечитати налаштування.
     *
     * <p>Викликати після кожної зміни повзунка: інакше нове значення долетить
     * лише до наступного запуску гри, і на слух здаватиметься, що повзунок знову
     * ні на що не впливає.
     */
    public static void refresh() {
        master = clamp(LostBatalion.Settings.getVolume());
        music  = clamp(LostBatalion.Settings.getMusicVolume());
        sfx    = clamp(LostBatalion.Settings.getSfxVolume());
        loaded = true;
    }

    private static void ensureLoaded() {
        if (!loaded) refresh();
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
