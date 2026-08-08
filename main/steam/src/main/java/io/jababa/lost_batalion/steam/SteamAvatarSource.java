package io.jababa.lost_batalion.steam;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;
import io.jababa.lost_batalion.net.api.AvatarSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Аватарки зі Steam.
 *
 * <p>Тут же живе єдиний на процес {@link SteamFriends}: його колбек потрібен
 * саме для аватарок ({@code onAvatarImageLoaded}), а тримати два екземпляри
 * заради ще й персони означало б двічі підписатись на ті самі події. Тому
 * {@code SteamBackend} питає ім'я персони теж у цього класу.
 *
 * <h3>Чому це не «завантажити й показати»</h3>
 * Steam віддає аватарку лише того, про кого клієнт уже щось знає. Для друга вона
 * є одразу, для випадкового супротивника з лоббі — ні: спершу треба попросити
 * ({@link SteamFriends#requestUserInformation}), і картинка приїде за кілька
 * кадрів, колбеком. Тому станів три, і кожному потрібне своє місце:
 *
 * <ul>
 *   <li>немає ні в {@code cache}, ні в {@code requested} — ще не питали;</li>
 *   <li>є в {@code requested} — попросили, чекаємо;</li>
 *   <li>є в {@code cache} — готово.</li>
 * </ul>
 *
 * <p>Окремий набір «попросили» — не надмірність: без нього кожен кадр слав би
 * Steam новий запит на того самого гравця. Рядки лоббі перебудовуються на кожне
 * повідомлення від хоста, тобто запити пішли б десятками за секунду.
 *
 * <h3>Потоки</h3>
 * Текстура створюється ТІЛЬКИ в {@link #avatarFor(String)}, який кличе рендер.
 * Колбек ({@link #onAvatarImageLoaded}) не чіпає GL взагалі — він лише знімає
 * позначку очікування й підіймає {@link #revision()}. Це не перестраховка:
 * колбеки приходять із {@code SteamAPI.runCallbacks()}, а той сам кличеться з
 * кадру, — але прив'язувати створення текстур до цього факту означало б
 * поламатись мовчки, щойно такт колбеків переїде в окремий потік.
 */
public class SteamAvatarSource implements AvatarSource {

    /**
     * Беремо СЕРЕДНЮ аватарку — 64×64.
     *
     * <p>Мала (32) помітно мулька вже в рядку лоббі, а велика (184) для
     * квадратика 22×22 — це вісім разів більше пікселів заради того, що
     * однаково стиснеться фільтром.
     */
    private final Map<String, Texture> cache = new HashMap<>();
    /** Кого вже просили — щоб не слати запит щокадру. */
    private final java.util.Set<String> requested = new java.util.HashSet<>();

    private SteamFriends friends;
    private SteamUtils   utils;

    private int revision;

    /** Чи Steam уже впав або ще не піднявся — тоді просто нічого не робимо. */
    private boolean broken;

    @Override
    public int revision() { return revision; }

    /**
     * Ім'я персони. Живе тут, бо тут єдиний {@link SteamFriends}.
     * Порожній рядок, якщо Steam недоступний, — нік гравець впише сам.
     */
    public String personaName() {
        SteamFriends f = friends();
        if (f == null) return "";
        try {
            return f.getPersonaName();
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public Texture avatarFor(String steamId) {
        if (steamId == null || steamId.isEmpty() || broken) return null;

        Texture cached = cache.get(steamId);
        if (cached != null) return cached;

        SteamFriends f = friends();
        if (f == null) return null;

        SteamID id = SteamMatchmakingHub.fromAddress(steamId);
        if (id == null || !id.isValid()) return null;

        int handle;
        try {
            handle = f.getMediumFriendAvatar(id);
        } catch (Throwable t) {
            SteamMatchmakingHub.log("аватарка: getMediumFriendAvatar впав: " + t);
            return null;
        }

        if (handle <= 0) {
            // Нуль означає «клієнт про цього гравця ще нічого не знає». Просимо
            // РІВНО РАЗ: другий аргумент false — «мені треба не лише ім'я, а й
            // картинка». Відповідь прийде в onAvatarImageLoaded.
            if (requested.add(steamId)) {
                try {
                    f.requestUserInformation(id, false);
                } catch (Throwable t) {
                    SteamMatchmakingHub.log("аватарка: запит не пройшов: " + t);
                }
            }
            return null;
        }

        Texture texture = readImage(handle);
        if (texture == null) return null;

        cache.put(steamId, texture);
        return texture;
    }

    /**
     * Витягти піксели з дескриптора Steam у текстуру.
     *
     * <p>Буфер мусить бути ПРЯМИЙ: на тому боці JNI, і звичайний heap-буфер
     * Steamworks не приймає. Порядок байтів рідний — інакше RGBA приїхало б
     * задом наперед на little-endian, тобто скрізь, де гра запускається.
     */
    private Texture readImage(int handle) {
        try {
            SteamUtils u = utils();
            if (u == null) return null;

            int width  = u.getImageWidth(handle);
            int height = u.getImageHeight(handle);
            if (width <= 0 || height <= 0) return null;

            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4)
                                          .order(ByteOrder.nativeOrder());
            if (!u.getImageRGBA(handle, buffer)) return null;

            Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            buffer.position(0);
            pixmap.getPixels().position(0);
            pixmap.getPixels().put(buffer);
            pixmap.getPixels().position(0);

            Texture texture = new Texture(pixmap);
            // Аватарка завжди ЗМЕНШУЄТЬСЯ (64 у квадратик 22), а при зменшенні
            // Nearest викидає рядки й обличчя розсипається — DESIGN §6.
            texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
            pixmap.dispose();
            return texture;
        } catch (Throwable t) {
            SteamMatchmakingHub.log("аватарка: не вдалось прочитати картинку: " + t);
            return null;
        }
    }

    /** Колбек Steam: картинка приїхала. GL тут не чіпаємо — див. javadoc класу. */
    private void onAvatarImageLoaded(SteamID id) {
        String key = SteamMatchmakingHub.toAddress(id);
        // Дозволяємо перепитати: наступний avatarFor побачить уже ненульовий
        // дескриптор і збудує текстуру.
        if (requested.remove(key)) revision++;
    }

    /**
     * Перевірити те, чого чекаємо.
     *
     * <p><b>Це не дублювання колбека, а основний шлях.</b> Steam цілком може
     * дістати аватарку мовчки: {@code getMediumFriendAvatar} починає віддавати
     * дескриптор, а {@code onAvatarImageLoaded} не приходить узагалі — саме так
     * поводиться клієнт на «холодному» акаунті, перевірено зондом. Оскільки
     * екран перепитує лише при зміні {@link #revision()}, самого колбека було б
     * досить рівно доти, доки Steam ласкавий; далі гра назавжди лишалась би з
     * літерою при готовій картинці.
     *
     * <p>Текстуру тут НЕ будуємо: це кадровий такт бекенда, а не рендер. Зняли
     * позначку очікування й підняли лічильник — решту зробить
     * {@link #avatarFor(String)}, коли екран перемалює рядок.
     */
    @Override
    public void update() {
        if (broken || requested.isEmpty()) return;

        SteamFriends f = friends();
        if (f == null) return;

        java.util.Iterator<String> it = requested.iterator();
        while (it.hasNext()) {
            String steamId = it.next();
            SteamID id = SteamMatchmakingHub.fromAddress(steamId);
            if (id == null || !id.isValid()) { it.remove(); continue; }
            try {
                if (f.getMediumFriendAvatar(id) > 0) {
                    it.remove();
                    revision++;
                }
            } catch (Throwable t) {
                it.remove();
            }
        }
    }

    // ── Підняття ──────────────────────────────────────────────────────────

    private SteamFriends friends() {
        if (broken) return null;
        if (friends != null) return friends;
        if (!SteamBoot.isRunning()) return null;
        try {
            friends = new SteamFriends(new SteamFriendsCallback() {
                @Override
                public void onAvatarImageLoaded(SteamID steamID, int image, int width, int height) {
                    SteamAvatarSource.this.onAvatarImageLoaded(steamID);
                }

                @Override
                public void onPersonaStateChange(SteamID steamID, SteamFriends.PersonaChange change) {
                    // Зміна персони приносить і аватарку теж: саме цим колбеком
                    // Steam відповідає на requestUserInformation, коли картинка
                    // вже лежала в кеші клієнта й окремого onAvatarImageLoaded
                    // не буде.
                    SteamAvatarSource.this.onAvatarImageLoaded(steamID);
                }
            });
            SteamMatchmakingHub.log("аватарки: джерело піднято");
            return friends;
        } catch (Throwable t) {
            broken = true;
            SteamMatchmakingHub.log("аватарки недоступні: " + t);
            return null;
        }
    }

    private SteamUtils utils() {
        if (broken) return null;
        if (utils != null) return utils;
        if (!SteamBoot.isRunning()) return null;
        try {
            utils = new SteamUtils(new SteamUtilsCallback() {});
            return utils;
        } catch (Throwable t) {
            broken = true;
            SteamMatchmakingHub.log("аватарки недоступні (utils): " + t);
            return null;
        }
    }

    @Override
    public void dispose() {
        for (Texture texture : cache.values()) {
            if (texture != null) texture.dispose();
        }
        cache.clear();
        requested.clear();
    }
}
