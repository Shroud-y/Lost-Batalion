package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.audio.AudioManager;
import io.jababa.lost_batalion.ui.ScreenResolution;
import io.jababa.lost_batalion.ui.ScreenResolution.WindowMode;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.ui.UIScale;

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

    /**
     * Чи ігнорувати події списків.
     *
     * <p>Ставиться, поки панель сама переставляє вміст списку роздільностей:
     * {@code setItems}/{@code setSelected} шлють {@code ChangeEvent}, не
     * відрізнити від людського вибору, і без цього прапорця показ поточної
     * роздільності записався б у налаштування як намір гравця.
     */
    private boolean suppressEvents;

    /**
     * Чим перескласти інтерфейс після зміни масштабу.
     *
     * <p>Панель не знає, де вона висить — на екрані налаштувань чи на паузі
     * посеред матчу, — а перескладати треба різне: там сцену меню, тут три
     * сцени HUD. Тому це справа господаря.
     */
    private final Runnable onUiScaleChanged;

    public SettingsPanel(Runnable onUiScaleChanged) {
        this.onUiScaleChanged = onUiScaleChanged;

        table.top();
        table.add(buildMasterVolumeRow()).growX().height(ROW_HEIGHT).row();
        table.add(buildMusicVolumeRow()).growX().height(ROW_HEIGHT).padTop(6f).row();
        table.add(buildSfxVolumeRow()).growX().height(ROW_HEIGHT).padTop(6f).row();
        table.add(buildUiScaleRow()).growX().height(ROW_HEIGHT).padTop(6f).row();
        table.add(buildWindowModeRow()).growX().height(ROW_HEIGHT).padTop(6f).row();
        table.add(buildResolutionRow()).growX().height(ROW_HEIGHT).padTop(6f).row();
    }

    /** Готова таблиця параметрів. Тло й поля навколо — справа викликача. */
    public Table getTable() { return table; }

    // ── Рядки ─────────────────────────────────────────────────────────────

    /** Куди повзунок віддає нове значення. */
    private interface VolumeSink { void accept(float value); }

    private Table buildMasterVolumeRow() {
        return volumeRow("Загальна гучність", LostBatalion.Settings.getVolume(),
                new VolumeSink() {
                    @Override public void accept(float v) { AudioManager.setMaster(v); }
                });
    }

    private Table buildMusicVolumeRow() {
        return volumeRow("Музика", LostBatalion.Settings.getMusicVolume(),
                new VolumeSink() {
                    @Override public void accept(float v) { AudioManager.setMusic(v); }
                });
    }

    private Table buildSfxVolumeRow() {
        return volumeRow("Звуки", LostBatalion.Settings.getSfxVolume(),
                new VolumeSink() {
                    @Override public void accept(float v) { AudioManager.setSfx(v); }
                });
    }

    /**
     * Рядок гучності.
     *
     * <p>Значення йде в {@link AudioManager}, а не просто в {@code Preferences}:
     * шина тримає їх у полях, і без перечитування новий рівень почався б аж із
     * наступного запуску гри — рівно та поведінка, через яку повзунок здавався
     * несправним.
     */
    private static Table volumeRow(String caption, float initial, final VolumeSink sink) {
        final Slider slider = new Slider(0f, 1f, 0.05f, false, UIFactory.createSliderStyle());
        slider.setValue(initial);

        final Label value = new Label(percent(slider.getValue()), UIFactory.createHintStyle());

        slider.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                sink.accept(slider.getValue());
                // Цифра поруч із повзунком: без неї незрозуміло, чи 40% це
                // «тихо» чи «майже вимкнено», і доводиться перевіряти на слух.
                value.setText(percent(slider.getValue()));
            }
        });

        Table control = new Table();
        control.add(slider).growX();
        control.add(value).width(48f).right().padLeft(10f);
        return row(caption, control);
    }

    /**
     * Масштаб інтерфейсу МАТЧУ, 50–200%.
     *
     * <p>Стосується того, що намальовано поверх бою: мінікарти, панелі
     * виділення, панелі військ, меню паузи. Меню поза матчем він не чіпає — там
     * розкладка й так росте разом із вікном. Тому на екрані налаштувань із
     * головного меню повзунок нічого не перемальовує: значення просто чекає
     * наступного бою.
     *
     * <p>Множник особистий і лягає ПОВЕРХ автоматичного (той тримає HUD
     * однакового розміру відносно екрана на різних роздільностях) — див.
     * {@link UIScale}.
     *
     * <p>Застосовується не миттєво, а наступним кадром: перескладання сцени
     * звільняє повзунок, усередині обробника якого ми зараз стоїмо. Та сама
     * пастка, що й у режимі вікна.
     *
     * <p>І не на кожному кроці, а по відпусканню. Перескладання перепікає всі
     * шрифти FreeType і створює сцену наново — тобто знищує сам повзунок разом
     * із захопленням миші. Протягування від 50% до 200% розсипалось би на
     * півтора десятка перебудов, кожна з яких обриває перетягування на першому
     * ж кроці. Цифра поруч при цьому міняється одразу, тож зворотний звʼязок
     * лишається миттєвим.
     */
    private Table buildUiScaleRow() {
        final Slider slider = new Slider(UIScale.USER_MIN, UIScale.USER_MAX,
                                         UIScale.USER_STEP, false,
                                         UIFactory.createSliderStyle());
        slider.setValue(UIScale.user());

        final Label value = new Label(percent(slider.getValue()), UIFactory.createHintStyle());

        slider.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                value.setText(percent(slider.getValue()));
                // Не перетягують — значить, це вже кінцеве значення (клік по
                // жолобу, клавіші). Під час протягування чекаємо відпускання.
                if (!slider.isDragging()) commitUiScale(slider.getValue());
            }
        });
        slider.addListener(new ClickListener() {
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, int button) {
                super.touchUp(event, x, y, pointer, button);
                commitUiScale(slider.getValue());
            }
        });

        Table control = new Table();
        control.add(slider).growX();
        control.add(value).width(48f).right().padLeft(10f);
        return row("Масштаб інтерфейсу", control);
    }

    /**
     * Записати масштаб і перескласти інтерфейс — якщо він справді змінився.
     *
     * <p>Перевірка обовʼязкова: відпускання повзунка приходить і тоді, коли
     * гравець просто торкнувся його й нічого не зрушив, а перебудова сцени
     * посеред матчу коштує помітної паузи.
     */
    private void commitUiScale(float value) {
        if (value == UIScale.user()) return;

        UIScale.setUser(value);
        if (onUiScaleChanged == null) return;
        apply(new Runnable() {
            @Override public void run() { onUiScaleChanged.run(); }
        });
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
        resolutionBox = new SelectBox<>(UIFactory.createSelectBoxStyle());

        resolutionBox.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (suppressEvents) return;

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
     * Що показує список роздільностей і чи можна його чіпати.
     *
     * <p>Розмір вікна щось значить лише в режимі «у вікні»: і без рамки, і в
     * повний екран його диктує монітор. Тому в цих режимах список гасне — але
     * показує при цьому ФАКТИЧНИЙ розмір, а не збережений віконний. Раніше він
     * лишався на 1280×720, поки гра йшла в 1920×1080, і це читалось як
     * несправність, а не як «тут зараз керує монітор».
     *
     * <p>Збережену віконну роздільність це не чіпає: вона просто не
     * показується, поки не діє, і повертається в список разом із режимом
     * «у вікні».
     */
    private void refreshResolutionRow() {
        if (resolutionBox == null) return;

        boolean windowed =
                LostBatalion.Settings.getWindowMode() == WindowMode.WINDOWED;
        resolutionBox.setDisabled(!windowed);

        // Наповнення списку саме по собі шле ChangeEvent, а обробник записав би
        // показане значення в налаштування як вибір гравця. Тобто без цього
        // гейта показ поточної роздільності мовчки затирав би збережену.
        suppressEvents = true;
        try {
            if (windowed) {
                modes = ScreenResolution.available();
                resolutionBox.setItems(modes);
                resolutionBox.setSelected(modes.get(ScreenResolution.indexOfSaved(modes,
                        LostBatalion.Settings.getResWidth(),
                        LostBatalion.Settings.getResHeight())));
            } else {
                modes = ScreenResolution.availableWithDesktop();
                ScreenResolution.Mode desktop = ScreenResolution.desktopMode();
                resolutionBox.setItems(modes);
                // Береться елемент СПИСКУ, а не свіжий об'єкт: Mode не
                // перекриває equals, тож SelectBox шукає пункт за тотожністю і
                // на чужому екземплярі мовчки відкотився б на перший.
                resolutionBox.setSelected(modes.get(
                        ScreenResolution.indexOfSaved(modes, desktop.w, desktop.h)));
            }
        } finally {
            suppressEvents = false;
        }
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
