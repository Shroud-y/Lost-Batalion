package io.jababa.lost_batalion.net.messages;

/**
 * Хост → усім: з тіку {@code effectiveTick} гравця більше немає в симуляції.
 *
 * <p>Ключове тут — саме номер тіку. Викинути гравця «прямо зараз» не можна:
 * якщо один клієнт перестане чекати його команд на тіку 500, а інший на 503,
 * вони виконають різну кількість тіків із різним набором наказів і
 * розсинхронізуються. Тому хост призначає спільний тік, з якого всі
 * одночасно перестають вимагати команди цього гравця.
 *
 * <p>{@code effectiveTick} завжди більший за поточний тік хоста на input delay,
 * щоб повідомлення встигло доїхати до всіх раніше за момент дії.
 */
public class PlayerDropped {

    public int playerId;
    public String nick;
    public int effectiveTick;

    /** Що робити з його юнітами — вирішує симуляція, тут лише прапорець наміру. */
    public boolean removeUnits;

    public PlayerDropped() {}

    public PlayerDropped(int playerId, String nick, int effectiveTick, boolean removeUnits) {
        this.playerId      = playerId;
        this.nick          = nick;
        this.effectiveTick = effectiveTick;
        this.removeUnits   = removeUnits;
    }
}
