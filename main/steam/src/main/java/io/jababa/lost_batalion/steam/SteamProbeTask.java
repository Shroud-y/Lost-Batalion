package io.jababa.lost_batalion.steam;

/**
 * Самоперевірка, яку кличуть щокадру з {@code SteamBackend.pump()}.
 *
 * <p>Перевірки Steam неможливо написати звичайним прогоном: половина відповідей
 * приходить колбеками через секунди, і чекати їх у циклі означає не дати
 * Steamworks тікати. Тому кожен зонд — маленький автомат станів, який живе в
 * кадрі гри.
 */
public interface SteamProbeTask {

    /** Вмикається {@code -Dlb.steamProbe=list|session}. */
    String PROPERTY = "lb.steamProbe";

    void tick();

    /** @return зонд за системною властивістю або {@code null} */
    static SteamProbeTask createIfRequested() {
        String mode = System.getProperty(PROPERTY);
        if (mode == null || mode.isEmpty() || "false".equalsIgnoreCase(mode)) return null;

        if (SteamMatchmakingHub.get() == null) {
            SteamMatchmakingHub.log("ЗОНД: Steam не піднявся, перевірка неможлива");
            return null;
        }

        // "true" лишається синонімом "list" — саме так зонд запускався до
        // появи другого режиму, і ламати вже записані командні рядки нема за що.
        if ("session".equalsIgnoreCase(mode)) return new SteamSessionProbe();
        if ("match".equalsIgnoreCase(mode))   return new SteamMatchProbe();
        return new SteamProbe();
    }
}
