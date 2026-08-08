package io.jababa.lost_batalion.net.messages;

import io.jababa.lost_batalion.net.NetConfig;

/**
 * Один учасник лоббі/матчу: гравець або бот.
 *
 * <p>Ключова відмінність від попередньої версії: {@link #playerId} більше НЕ
 * визначає сторону. Раніше «гравець 0 = сині, гравець 1 = червоні» діяло
 * скрізь, бо гравців було рівно двоє; при 5 на 5 сторона стає окремим полем
 * ({@link #team}), а playerId лишається тільки власністю юнітів і адресою в
 * lockstep.
 *
 * <p>Місце ({@link #seat}) потрібне тому, що колонка команди має фіксовані
 * рядки: закрите місце мусить лишатись на своєму рядку, коли сусід вийшов, а
 * порядок гравців не має стрибати на кожне приєднання.
 */
public class PlayerSlot {

    /** {@link #team} того, хто ще не обрав сторону і сидить у списку очікування. */
    public static final int TEAM_NONE = -1;

    /**
     * Номер гравця, 0..MAX_PLAYERS-1. Хост завжди 0.
     * Це той самий id, за яким симуляція визначає власника юніта, тож він не
     * змінюється від приєднання до кінця матчу.
     */
    public int playerId;

    public String  nick;
    public boolean ready;
    public boolean host;

    /** false, поки гравець у стані розриву зв'язку (слот ще тримається під перепідключення). */
    public boolean connected = true;

    /** Сторона: 0 — сині, 1 — червоні, {@link #TEAM_NONE} — список очікування. */
    public int team = TEAM_NONE;

    /** Рядок у колонці своєї команди, 0..TEAM_SIZE-1. Поза командою -1. */
    public int seat = -1;

    /** Бот замість людини. Його накази рахує хост і розсилає від цього playerId. */
    public boolean bot;

    /**
     * Рівень бота — ІМЕНЕМ константи {@code Difficulty}, не порядковим номером.
     * Рівень, вставлений між наявними, тихо перемкнув би чужий вибір; те саме
     * правило вже діє в {@code Settings.getBotDifficulty}.
     */
    public String botDifficulty;

    /**
     * Колір цього гравця серед своїх. Індекс у палітрі, а не самий колір:
     * по мережі їде число, а відтінок — справа рендера й теми.
     */
    public int colorIndex;

    /** SteamID64 як рядок — для аватарки. По локальній мережі null. */
    public String steamId;

    public PlayerSlot() {}

    public PlayerSlot(int playerId, String nick, boolean host) {
        this.playerId = playerId;
        this.nick     = nick;
        this.host     = host;
        this.ready    = host;   // хост завжди «готовий», він натискає Старт
    }

    /** Бот: готовий завжди й одразу зі своїм місцем — вибирати йому нічим. */
    public static PlayerSlot bot(int playerId, String nick, int team, int seat, String difficulty) {
        PlayerSlot slot = new PlayerSlot(playerId, nick, false);
        slot.bot           = true;
        slot.botDifficulty = difficulty;
        slot.ready         = true;
        slot.team          = team;
        slot.seat          = seat;
        return slot;
    }

    /** Чи учасник уже сидить у команді (а не чекає в списку очікування). */
    public boolean seated() {
        return team >= 0 && team < NetConfig.TEAM_COUNT
            && seat >= 0 && seat < NetConfig.TEAM_SIZE;
    }

    /**
     * Повна копія.
     *
     * <p>Копіювання полів по одному розкидане було по трьох файлах, і кожне
     * нове поле треба було не забути в кожному з них. Тепер місце одне: усе,
     * що не потрапило сюди, не доїде ні до гостя, ні в {@code StartMatch}.
     */
    public PlayerSlot copy() {
        PlayerSlot c = new PlayerSlot(playerId, nick, host);
        c.ready         = ready;
        c.connected     = connected;
        c.team          = team;
        c.seat          = seat;
        c.bot           = bot;
        c.botDifficulty = botDifficulty;
        c.colorIndex    = colorIndex;
        c.steamId       = steamId;
        return c;
    }

    @Override
    public String toString() {
        return "#" + playerId + " " + nick + (host ? " (host)" : "") + (bot ? " (bot)" : "")
             + (seated() ? " [" + team + ":" + seat + "]" : " [waitlist]")
             + (ready ? " [ready]" : "");
    }
}
