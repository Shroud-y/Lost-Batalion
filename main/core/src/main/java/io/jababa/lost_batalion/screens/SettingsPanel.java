package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ui.ScreenResolution;
import io.jababa.lost_batalion.ui.ScreenResolution.WindowMode;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Самі параметри — гучність, режим вікна, роздільність — без екрана навколо.
 *
 * <h3>Навіщо окремо від {@link SettingsScreen}</h3>
 * Ті самі рядки потрібні у двох місцях: на екрані налаштувань із головного меню
 * і на паузі всередині матчу. Другий випадок не може просто відкрити
 * {@code SettingsScreen} — перехід на інший екран звільняє {@code GameScreen}, а
 * з ним і мережеву сесію, тобто «зайти в налаштування» означало б вилетіти з
 * матчу. Тож панель живе окремо, а обидва місця лише вішають її на своє тло.
 *
 * <h3>Спільна пастка обох списків</h3>
 * Слухач вішається ПІСЛЯ {@code setSelected}: сам виклик теж шле
 * {@code ChangeEvent}, і без цього порядку панель перемикала б режим вікна
 * щоразу, коли її складають.
 */
public class SettingsPanel {

    /** Висота рядка параметра. */
    private static final float ROW_HEIGHT = 46f;
    /** Ширина колонки керування всередині рядка. */
    private static final float CONTROL_W  = 220f;
    /** Висота випадного списку. */
    private static final float CONTROL_H  = 30f;

    private final Table table = new Table();

    private Array<ScreenResolution.Mode>     modes;
    private SelectBox<ScreenResolution.Mode> resolutionBox;
    private SelectBox<WindowMode>            modeBox;

    public SettingsPanel() {
        table.top();
        table.add(buildVolumeRow()).growX().height(ROW_HEIGHT).row();
        table.add(buildWindowModeRow()).growX().height(ROW_HEIGHT).padTop(6f).row();
        table.add(buildResolutionRow()).growX().height(ROW_HEIGHT).padTop(6f).row();
    }

    /** Готова таблиця параметрів. Тло й поля навколо — справа викликача. */
    public Table getTable() { return table; }

    // ── Рядки ─────────────────────────────────────────────────────────────

    private Table buildVolumeRow() {
        final Slider slider = new Slider(0f, 1f, 0.05f, false, UIFactory.createSliderStyle());
        slider.setValue(LostBatalion.Settings.getVolume());

        final Label value = new Label(percent(slider.getValue()), UIFactory.createHintStyle());

        slider.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                LostBatalion.Settings.setVolume(slider.getValue());
                // Цифра поруч із повзунком: без неї незрозуміло, чи 40% це
                // «тихо» чи «майже вимкнено», і доводиться перевіряти на слух.
                value.setText(percent(slider.getValue()));
            }
        });

        Table control = new Table();
        control.add(slider).growX();
        control.add(value).width(48f).right().padLeft(10f);
        return row("Гучність", control);
    }

    /**
     * Режим вікна одним списком: у вікні / без рамки / повний екран.
     *
     * <p>Раніше тут стояла галочка «На весь екран». Додати поруч другу, «без
     * рамки», означало б дозволити поставити обидві — а що це має значити, не
     * скаже ніхто. Три взаємовиключні пункти цієї суперечності не мають за
     * побудовою.
     */
    private Table buildWindowModeRow() {
        modeBox = new SelectBox<>(UIFactory.createSelectBoxStyle());
        modeBox.setItems(WindowMode.values());
        modeBox.setSelected(LostBatalion.Settings.getWindowMode());

        modeBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                final WindowMode mode = modeBox.getSelected();
                if (mode == null) return;

                LostBatalion.Settings.setWindowMode(mode);
                refreshResolutionRow();

                apply(new Runnable() {
                    @Override public void run() {
                        ScreenResolution.applyMode(mode,
                                LostBatalion.Settings.getResWidth(),
                                LostBatalion.Settings.getResHeight());
                        refreshResolutionRow();
                    }
                });
            }
        });

        Table control = new Table();
        control.add(modeBox).growX().height(CONTROL_H);
        return row("Режим вікна", control);
    }

    /** Випадний список роздільностей. */
    private Table buildResolutionRow() {
        modes = ScreenResolution.available();

        resolutionBox = new SelectBox<>(UIFactory.createSelectBoxStyle());
        resolutionBox.setItems(modes);
        resolutionBox.setSelected(modes.get(ScreenResolution.indexOfSaved(modes,
                LostBatalion.Settings.getResWidth(),
                LostBatalion.Settings.getResHeight())));

        resolutionBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                final ScreenResolution.Mode m = resolutionBox.getSelected();
                if (m == null) return;

                LostBatalion.Settings.setResolution(m.w, m.h);
                apply(new Runnable() {
                    @Override public void run() {
                        ScreenResolution.applyWindowed(
                                LostBatalion.Settings.getWindowMode(), m.w, m.h);
                    }
                });
            }
        });

        Table control = new Table();
        control.add(resolutionBox).growX().height(CONTROL_H);

        refreshResolutionRow();
        return row("Роздільність", control);
    }

    /**
     * Доступність списку роздільностей.
     *
     * <p>Розмір вікна щось значить лише в режимі «у вікні»: і без рамки, і в
     * повний екран його диктує монітор. Список у цих режимах гасне — вибір у
     * ньому запам'ятається, але зараз ні на що не вплине.
     */
    private void refreshResolutionRow() {
        if (resolutionBox == null) return;
        resolutionBox.setDisabled(
                LostBatalion.Settings.getWindowMode() != WindowMode.WINDOWED);
    }

    /**
     * Виконати зміну режиму НЕ всередині обробника події.
     *
     * <p>Зміна розміру кадру прилітає назад у {@code resize()}, а
     * {@code BaseScreen.resize} перескладає сцену — тобто звільняє той самий
     * список, усередині обробника якого ми зараз стоїмо. Через
     * {@code postRunnable} це станеться вже наступним кадром, коли розсилка
     * події скінчилась.
     */
    private static void apply(Runnable action) {
        Gdx.app.postRunnable(action);
    }

    /** Рядок параметра: підпис ліворуч, керування праворуч фіксованої ширини. */
    private static Table row(String caption, Table control) {
        Table row = new Table();
        row.add(new Label(caption, UIFactory.createBodyStyle())).left().expandX();
        row.add(control).width(CONTROL_W).right();
        return row;
    }

    private static String percent(float v) {
        return Math.round(v * 100f) + "%";
    }
}
