package io.jababa.lost_batalion.net;

import com.badlogic.gdx.Gdx;

/**
 * Логування мережевого шару.
 *
 * <p>Мережеві класи мусять працювати і без запущеного libGDX: їх ганяють у
 * тестах і потенційно в майбутньому серверному модулі, де {@code Gdx.app}
 * дорівнює null. Прямий {@code Gdx.app.error} у такому середовищі падає з NPE
 * рівно в тому місці, де намагався повідомити про справжню проблему —
 * і справжня причина губиться.
 */
public final class NetLog {

    private static final String TAG = "NET";

    static {
        // KryoNet тягне minlog, який за замовчуванням сипле INFO про кожне
        // з'єднання просто в stdout, повз логер гри. Під час матчу таких рядків
        // будуть сотні, і в них потоне все, що справді варто прочитати.
        com.esotericsoftware.minlog.Log.WARN();
    }

    private NetLog() {}

    /**
     * Змусити клас завантажитись, а разом із ним застосувати налаштування minlog.
     * Без явного дотику статичний блок спрацював би лише при першій помилці —
     * тобто вже після того, як KryoNet засипав би консоль.
     */
    public static void init() {}

    /** Увімкнути детальний лог KryoNet — для розбору мережевих проблем. */
    public static void enableVerboseNetworkLogging() {
        com.esotericsoftware.minlog.Log.INFO();
    }

    public static void info(String message) {
        if (Gdx.app != null) Gdx.app.log(TAG, message);
        else System.out.println("[" + TAG + "] " + message);
    }

    public static void error(String message) {
        if (Gdx.app != null) Gdx.app.error(TAG, message);
        else System.err.println("[" + TAG + "] " + message);
    }

    public static void error(String message, Throwable cause) {
        if (Gdx.app != null) Gdx.app.error(TAG, message, cause);
        else {
            System.err.println("[" + TAG + "] " + message);
            cause.printStackTrace(System.err);
        }
    }
}
