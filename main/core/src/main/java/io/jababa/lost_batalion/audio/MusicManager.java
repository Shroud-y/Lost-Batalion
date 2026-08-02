package io.jababa.lost_batalion.audio;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Disposable;

import java.util.Random;

/**
 * Фонова музика: плейлист на кожен режим гри і перехід між ними.
 *
 * <h3>Чому не в екрані</h3>
 * Екрани в цій грі не переживають перехід: {@code LostBatalion.setScreen}
 * навмисно кличе {@code dispose()} попереднього. Музика, що належала б екрану,
 * обривалась би на вході в налаштування й починалась заново на виході — тобто
 * рівно там, де гравець нічого не змінював. Тому менеджер один на застосунок,
 * живе в {@link io.jababa.lost_batalion.LostBatalion} і звільняється разом із
 * грою, а екрани лише кажуть, ЩО зараз відбувається:
 * {@link #setContext(Context)}.
 *
 * <h3>Що звучить</h3>
 * Трек грається без зациклення, а по завершенню менеджер бере наступний із того
 * самого списку — випадковий, але не той самий двічі поспіль. Зациклений один
 * трек чути вже на другому колі; чотири по черзі — ні.
 *
 * <p>Зміна режиму (меню → бій) — це кросфейд {@link #CROSSFADE} секунд:
 * обидва треки звучать одночасно, старий згасає. Різкий обрив читався б як
 * збій звуку, а тиша між ними — як зависання гри.
 *
 * <h3>Чого тут навмисно немає</h3>
 * Файлів може не бути (плейсхолдери — тимчасові): відсутній трек не помилка, а
 * тиша. Жоден виклик не кидає винятку через це, бо музика не та річ, через яку
 * має падати гра.
 *
 * <p>Випадковість тут — {@link Random}, а НЕ {@code DeterministicRandom}
 * симуляції. Черга треків не є станом матчу, і чіпати симуляційний генератор
 * означало б розсинхронити клієнтів через порядок пісень.
 */
public class MusicManager implements Disposable {

    /** Що зараз відбувається в грі. Кожному режиму — свій список. */
    public enum Context {
        /** Головне меню й усе, що з нього відкривається. */
        MENU(new String[] { "music/menu_01.ogg", "music/menu_02.ogg" }),
        /** Матч. */
        BATTLE(new String[] { "music/battle_01.ogg", "music/battle_02.ogg" });

        private final String[] tracks;
        Context(String[] tracks) { this.tracks = tracks; }
    }

    /** Тривалість переходу між режимами, секунди. */
    private static final float CROSSFADE = 1.5f;

    /**
     * Наростання гучності на початку треку всередині одного режиму.
     *
     * <p>Коротше за кросфейд: тут нема чого перекривати, потрібно лише прибрати
     * клац на першому семплі.
     */
    private static final float TRACK_FADE_IN = 0.8f;

    private final Random random = new Random();

    private Context context;

    private Music current;
    private int   currentIndex = -1;
    private float currentGain;          // 0..1, множиться на гучність із налаштувань
    private float currentFadeRate;      // за секунду

    private Music previous;             // той, що згасає під час кросфейду
    private float previousGain;

    /**
     * Прапорець «трек догрався».
     *
     * <p>Слухач завершення викликається зсередини оновлення аудіо, і міняти в
     * ньому склад треків — напрошуватись на роботу з обʼєктом, який зараз
     * використовує бекенд. Тому там лише прапорець, а наступний трек стартує в
     * {@link #update(float)}, у звичайному кадрі.
     */
    private volatile boolean trackFinished;

    /** Гучність, з якою востаннє виставляли треки — щоб не смикати бекенд щокадру. */
    private float appliedSettingsVolume = -1f;

    // ── Публічне API ──────────────────────────────────────────────────────

    /**
     * Перемкнути режим. Повторний виклик із тим самим режимом нічого не робить —
     * екрани кличуть це з {@code show()}, а меню перемикаються часто.
     */
    public void setContext(Context next) {
        if (next == null || next == context) return;
        context = next;

        if (current != null) {
            // Місце для згасання одне. Якщо попередній ще не догас (швидкий
            // тичок меню → бій → меню), він обривається: тримати три треки
            // одночасно заради цього не варто.
            stopPrevious();
            previous     = current;
            previousGain = currentGain;
            current      = null;
            currentIndex = -1;
        }
        startTrack(pickIndex(), CROSSFADE);
    }

    /**
     * Кадрове оновлення: фейди, зміна треку, гучність із налаштувань.
     *
     * <p>Кличеться з {@code LostBatalion.render()}, а не з екрана: так воно
     * працює на КОЖНОМУ екрані й переживає переходи між ними.
     */
    public void update(float delta) {
        if (trackFinished) {
            trackFinished = false;
            // Той самий режим — просто наступна пісня, без кросфейду: попередня
            // вже закінчилась, перекривати нічого.
            startTrack(pickIndex(), TRACK_FADE_IN);
        }

        float settings = AudioManager.musicVolume();
        boolean volumeChanged = settings != appliedSettingsVolume;
        appliedSettingsVolume = settings;

        if (previous != null) {
            previousGain -= delta / CROSSFADE;
            if (previousGain <= 0f) {
                stopPrevious();
            } else {
                previous.setVolume(previousGain * settings);
            }
        }

        if (current != null) {
            if (currentGain < 1f) {
                currentGain += delta * currentFadeRate;
                if (currentGain > 1f) currentGain = 1f;
                current.setVolume(currentGain * settings);
            } else if (volumeChanged) {
                // Повзунок гучності має відгукуватись, поки його тягнуть, а не
                // з наступного треку.
                current.setVolume(settings);
            }
        }
    }

    /** Зупинити все. Гра, що згорнулась у трей, не має грати сама собі. */
    public void stop() {
        stopPrevious();
        if (current != null) {
            current.stop();
            current.dispose();
            current = null;
        }
        currentIndex = -1;
        context      = null;
    }

    @Override
    public void dispose() {
        stop();
    }

    // ── Приватне ──────────────────────────────────────────────────────────

    /**
     * Наступний трек списку: випадковий, але не той самий, що грав щойно.
     *
     * @return індекс у списку режиму або -1, якщо список порожній
     */
    private int pickIndex() {
        if (context == null) return -1;
        int n = context.tracks.length;
        if (n == 0) return -1;
        if (n == 1) return 0;

        int next = random.nextInt(n);
        if (next == currentIndex) next = (next + 1 + random.nextInt(n - 1)) % n;
        return next;
    }

    private void startTrack(int index, float fadeIn) {
        if (context == null || index < 0 || index >= context.tracks.length) return;

        Music music = load(context.tracks[index]);
        if (music == null) return;

        // Догралий трек звільняється саме тут. При зміні режиму його забирає
        // кросфейд (він стає previous), але в межах одного режиму треки
        // змінюються без нього — і без цього рядка кожен програний трек лишався
        // б відкритим потоком до кінця матчу.
        if (current != null) {
            current.stop();
            current.dispose();
        }

        current      = music;
        currentIndex = index;
        currentGain  = 0f;
        currentFadeRate = fadeIn > 0f ? 1f / fadeIn : Float.MAX_VALUE;

        current.setLooping(false);      // черга треків замість одного по колу
        current.setVolume(0f);
        current.setOnCompletionListener(new Music.OnCompletionListener() {
            @Override public void onCompletion(Music m) { trackFinished = true; }
        });
        current.play();
    }

    /**
     * Прочитати трек. Відсутній або битий файл — це тиша, а не виняток: музика
     * тут плейсхолдер, і гра не повинна від нього залежати.
     */
    private Music load(String path) {
        try {
            FileHandle file = Gdx.files.internal(path);
            if (!file.exists()) {
                Gdx.app.log("MUSIC", "немає треку: " + path);
                return null;
            }
            return Gdx.audio.newMusic(file);
        } catch (Exception e) {
            Gdx.app.error("MUSIC", "не вдалося прочитати " + path, e);
            return null;
        }
    }

    private void stopPrevious() {
        if (previous == null) return;
        previous.stop();
        previous.dispose();
        previous     = null;
        previousGain = 0f;
    }
}
