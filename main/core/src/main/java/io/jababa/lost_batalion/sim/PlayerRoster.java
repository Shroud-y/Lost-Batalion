package io.jababa.lost_batalion.sim;

import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.net.NetConfig;
import io.jababa.lost_batalion.net.messages.PlayerSlot;

import java.util.List;

/**
 * Склад матчу: хто за яку сторону грає, яким кольором і чи він бот.
 *
 * <p>Це ЄДИНЕ місце, де номер гравця перетворюється на сторону. Доти, доки
 * гравців було двоє, перетворення було тотожністю ({@code Team.forPlayer}) і
 * лежало розкидане по двадцяти файлах; при п'яти гравцях на сторону кожне таке
 * місце стало б окремою можливістю помилитись.
 *
 * <h3>Чому це не стан симуляції</h3>
 * Ростер незмінний від старту матчу і однаковий у всіх — він приїжджає в
 * {@code StartMatch}. Тому в checksum і в знімок він НЕ входить: писати туди
 * константу означає лише роздувати обидва. Гравець, що вибув, лишається в
 * ростері — його армія зникає, а номер лишається валідним, бо накази, які вже
 * летіли, мусять тихо відсіятись, а не впасти.
 */
public final class PlayerRoster {

    /** Не учасник цього матчу. */
    private static final int NO_TEAM = -1;

    private final int[]     teamOf     = filled(NetConfig.MAX_PLAYERS, NO_TEAM);
    private final int[]     colorOf    = new int[NetConfig.MAX_PLAYERS];
    private final boolean[] botOf      = new boolean[NetConfig.MAX_PLAYERS];
    /**
     * Рівень бота — ІМЕНЕМ константи {@code Difficulty}, як і в {@code PlayerSlot}.
     * Ростер сюди його тягне тому, що хост будує мозки вже після старту матчу, зі
     * складу, а не з лоббі: слоти на той момент лишаються тільки в {@code StartMatch}.
     */
    private final String[]  levelOf    = new String[NetConfig.MAX_PLAYERS];
    /**
     * Нік і SteamID учасника — для HUD: ряди аватарок обабіч рахунку.
     *
     * <p>На симуляцію вони не впливають НІЯК і саме тому живуть тут, а не в
     * окремому «складі для показу»: другий список того самого набору гравців
     * рано чи пізно розійшовся б із цим. Ростер і без того незмінний від старту
     * й однаковий у всіх — місце для константи про учасника рівно одне.
     */
    private final String[]  nickOf     = new String[NetConfig.MAX_PLAYERS];
    private final String[]  steamOf    = new String[NetConfig.MAX_PLAYERS];
    private final int[]     playerIds;

    private PlayerRoster(int[] playerIds) {
        this.playerIds = playerIds;
    }

    private static int[] filled(int size, int value) {
        int[] a = new int[size];
        for (int i = 0; i < size; i++) a[i] = value;
        return a;
    }

    /**
     * Склад одиночної гри: гравець 0 за синіх, бот 1 за червоних.
     *
     * <p>Рівно те, що робив старий {@code Team.forPlayer}, — тому одиночна гра
     * від усього цього не змінюється ні на біт.
     */
    public static PlayerRoster local() {
        PlayerRoster r = new PlayerRoster(new int[] { 0, 1 });
        r.teamOf[0]  = 0;
        r.teamOf[1]  = 1;
        r.colorOf[0] = 0;
        r.colorOf[1] = 1;
        r.botOf[1]   = true;
        return r;
    }

    /**
     * Склад із слотів лоббі.
     *
     * <p>Порядок {@link #players()} — за зростанням playerId, а не за порядком
     * у списку: за ним ідуть початкова розстановка і роздача id юнітів, тобто
     * він мусить бути однаковим у всіх незалежно від того, хто в якому порядку
     * заходив у лоббі.
     */
    public static PlayerRoster of(List<PlayerSlot> slots) {
        if (slots == null || slots.isEmpty()) return local();

        int count = 0;
        int[] ids = new int[NetConfig.MAX_PLAYERS];
        PlayerRoster r = new PlayerRoster(null);

        for (int id = 0; id < NetConfig.MAX_PLAYERS; id++) {
            for (int i = 0; i < slots.size(); i++) {
                PlayerSlot s = slots.get(i);
                if (s == null || s.playerId != id || !s.seated()) continue;
                r.teamOf[id]  = s.team;
                r.colorOf[id] = s.colorIndex;
                r.botOf[id]   = s.bot;
                r.levelOf[id] = s.botDifficulty;
                r.nickOf[id]  = s.nick;
                r.steamOf[id] = s.steamId;
                ids[count++]  = id;
                break;
            }
        }
        if (count == 0) return local();

        int[] trimmed = new int[count];
        System.arraycopy(ids, 0, trimmed, 0, count);
        return new PlayerRoster(trimmed).copyFrom(r);
    }

    private PlayerRoster copyFrom(PlayerRoster src) {
        System.arraycopy(src.teamOf,  0, teamOf,  0, teamOf.length);
        System.arraycopy(src.colorOf, 0, colorOf, 0, colorOf.length);
        System.arraycopy(src.botOf,   0, botOf,   0, botOf.length);
        System.arraycopy(src.levelOf, 0, levelOf, 0, levelOf.length);
        System.arraycopy(src.nickOf,  0, nickOf,  0, nickOf.length);
        System.arraycopy(src.steamOf, 0, steamOf, 0, steamOf.length);
        return this;
    }

    // ── Читання ───────────────────────────────────────────────────────────

    public boolean has(int playerId) {
        return playerId >= 0 && playerId < teamOf.length && teamOf[playerId] != NO_TEAM;
    }

    /** Сторона гравця. Для не-учасника — {@code null}, і це не помилка. */
    public Team team(int playerId) {
        return has(playerId) ? Team.byIndex(teamOf[playerId]) : null;
    }

    /** Індекс сторони гравця, або -1. */
    public int teamIndex(int playerId) {
        return has(playerId) ? teamOf[playerId] : NO_TEAM;
    }

    public boolean isBot(int playerId)   { return has(playerId) && botOf[playerId]; }
    public int     colorIndex(int playerId) { return has(playerId) ? colorOf[playerId] : 0; }

    /** Рівень бота іменем константи, або {@code null}. Для людини — завжди {@code null}. */
    public String botDifficulty(int playerId) {
        return isBot(playerId) ? levelOf[playerId] : null;
    }

    /** Нік учасника; {@code null} в одиночній грі, де їх ніхто не вводив. */
    public String nick(int playerId) {
        return has(playerId) ? nickOf[playerId] : null;
    }

    /** SteamID64 рядком або {@code null} — для аватарки. По LAN завжди null. */
    public String steamId(int playerId) {
        return has(playerId) ? steamOf[playerId] : null;
    }

    /**
     * Номери всіх ботів у складі, за зростанням.
     *
     * <p>Ними хост будує мозки: бот — це ПІР, і за нього має віддавати накази
     * рівно один учасник матчу. Ним призначено хоста, бо він і так єдина влада
     * в лоббі; будь-хто інший означав би два джерела наказів на один playerId.
     */
    public int[] bots() {
        int n = 0;
        for (int i = 0; i < playerIds.length; i++) if (botOf[playerIds[i]]) n++;
        int[] out = new int[n];
        n = 0;
        for (int i = 0; i < playerIds.length; i++) {
            if (botOf[playerIds[i]]) out[n++] = playerIds[i];
        }
        return out;
    }

    /** Усі учасники матчу, за зростанням номера. Не копія — не міняти. */
    public int[] players() { return playerIds; }

    public int size() { return playerIds.length; }

    /** Чи цей гравець і той — союзники (в тому числі він сам із собою). */
    public boolean allied(int a, int b) {
        return has(a) && has(b) && teamOf[a] == teamOf[b];
    }

    public int teamSize(int teamIndex) {
        int n = 0;
        for (int i = 0; i < playerIds.length; i++) {
            if (teamOf[playerIds[i]] == teamIndex) n++;
        }
        return n;
    }

    /** Найбільша зі сторін. Від неї залежить, скільки очок треба на перемогу. */
    public int maxTeamSize() {
        int max = 0;
        for (int t = 0; t < NetConfig.TEAM_COUNT; t++) max = Math.max(max, teamSize(t));
        return max;
    }

    /** Номери гравців сторони, за зростанням. */
    public int[] playersOf(int teamIndex) {
        int[] out = new int[teamSize(teamIndex)];
        int n = 0;
        for (int i = 0; i < playerIds.length; i++) {
            if (teamOf[playerIds[i]] == teamIndex) out[n++] = playerIds[i];
        }
        return out;
    }

    public int[] playersOf(Team team) {
        return playersOf(team == null ? NO_TEAM : team.index());
    }

    /**
     * Порядковий номер гравця в СВОЇЙ команді (0..n-1).
     *
     * <p>Ним рознесені точки виходу підкріплення: усі свої виходять з одного
     * кута, і без зсуву п'ятеро висаджували б роти в один піксель.
     */
    public int seatInTeam(int playerId) {
        if (!has(playerId)) return 0;
        int n = 0;
        for (int i = 0; i < playerIds.length; i++) {
            if (playerIds[i] == playerId) return n;
            if (teamOf[playerIds[i]] == teamOf[playerId]) n++;
        }
        return n;
    }
}
