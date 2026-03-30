package io.jababa.lost_batalion.android;

import android.os.Bundle;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import io.jababa.lost_batalion.LostBatalion;
import java.io.File;
import java.io.PrintWriter;

public class AndroidLauncher extends AndroidApplication {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Цей блок перехоплює помилку ПЕРЕД вильотом
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                // Створюємо файл у внутрішній папці додатка на телефоні
                File file = new File(getExternalFilesDir(null), "error_log.txt");
                PrintWriter pw = new PrintWriter(file);
                ex.printStackTrace(pw);
                pw.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        });

        AndroidApplicationConfiguration configuration = new AndroidApplicationConfiguration();
        configuration.useImmersiveMode = true;
        initialize(new LostBatalion(), configuration);
    }
}
