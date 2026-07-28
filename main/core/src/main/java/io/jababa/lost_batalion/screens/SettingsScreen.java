package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Slider;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Налаштування.
 *
 * <p>Один стовпчик рядків «підпис — керування» замість двоколонкової сітки:
 * пунктів мало, і сітка з двох стовпців розтягувала б їх на весь екран,
 * лишаючи посередині порожнечу.
 */
public class SettingsScreen extends BaseScreen {

    /** Ширина панелі з параметрами. */
    private static final float PANEL_WIDTH = 520f;
    /** Висота рядка параметра. */
    private static final float ROW_HEIGHT  = 46f;
    /** Ширина колонки керування всередині рядка. */
    private static final float CONTROL_W   = 220f;

    public SettingsScreen(LostBatalion game) {
        super(game);
    }

    @Override
    protected void buildUI() {
        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(22f, 24f, 22f, 24f);
        panel.top();

        panel.add(buildVolumeRow()).growX().height(ROW_HEIGHT).row();
        panel.add(buildFullscreenRow()).growX().height(ROW_HEIGHT).padTop(6f).row();

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        root.pad(UIFactory.HEADER_TOP, UIFactory.MARGIN,
                 UIFactory.FOOTER_PAD, UIFactory.MARGIN);

        root.add(new io.jababa.lost_batalion.ui.ScreenHeader(
                    "НАЛАШТУВАННЯ", () -> game.setScreen(new MainMenuScreen(game))))
            .growX().row();
        root.add(panel).width(PANEL_WIDTH).left().padTop(26f).row();
        root.add().expandY().row();   // притискає панель угору

        stage.addActor(root);
    }

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

    private Table buildFullscreenRow() {
        final CheckBox box = new CheckBox("", UIFactory.createCheckBoxStyle());
        box.setChecked(Gdx.graphics.isFullscreen());
        box.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent event, Actor actor) {
                if (box.isChecked()) {
                    Gdx.graphics.setFullscreenMode(Gdx.graphics.getDisplayMode());
                } else {
                    Gdx.graphics.setWindowedMode(1280, 720);
                }
                LostBatalion.Settings.setFullscreen(box.isChecked());
            }
        });

        Table control = new Table();
        control.add(box).left().expandX();
        return row("На весь екран", control);
    }

    /** Рядок параметра: підпис ліворуч, керування праворуч фіксованої ширини. */
    private Table row(String caption, Table control) {
        Table row = new Table();
        row.add(new Label(caption, UIFactory.createBodyStyle())).left().expandX();
        row.add(control).width(CONTROL_W).right();
        return row;
    }

    private static String percent(float v) {
        return Math.round(v * 100f) + "%";
    }
}
