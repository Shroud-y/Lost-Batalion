package io.jababa.lost_batalion.screens;

import com.badlogic.gdx.scenes.scene2d.ui.Table;
import io.jababa.lost_batalion.LostBatalion;
import io.jababa.lost_batalion.ui.ScreenHeader;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Налаштування з головного меню.
 *
 * <p>Один стовпчик рядків «підпис — керування» замість двоколонкової сітки:
 * пунктів мало, і сітка з двох стовпців розтягувала б їх на весь екран,
 * лишаючи посередині порожнечу.
 *
 * <p>Самі рядки живуть у {@link SettingsPanel} — та сама панель відкривається з
 * паузи всередині матчу, і два її примірники мусять лишатись однаковими.
 */
public class SettingsScreen extends BaseScreen {

    public SettingsScreen(LostBatalion game) {
        super(game);
    }

    @Override
    protected void buildUI() {
        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(22f, 24f, 22f, 24f);
        panel.top();
        panel.add(new SettingsPanel().getTable()).growX().row();

        Table root = new Table();
        root.setFillParent(true);
        root.top();
        root.pad(UIFactory.HEADER_TOP, UIFactory.MARGIN,
                 UIFactory.FOOTER_PAD, UIFactory.MARGIN);

        root.add(new ScreenHeader("НАЛАШТУВАННЯ",
                    () -> game.setScreen(new MainMenuScreen(game))))
            .growX().row();
        root.add(panel).growX().padTop(26f).row();
        root.add().expandY().row();   // притискає панель угору

        stage.addActor(root);
    }
}
