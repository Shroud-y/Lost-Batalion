package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import io.jababa.lost_batalion.sim.MatchRunner;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Модальне вікно розсинхронізації.
 *
 * <p>З'являється, коли хеші стану розійшлись. Симуляція на цей момент уже
 * зупинена — вікно не спиняє гру, воно пояснює, чому вона стоїть. Мовчазне
 * зависання гравці читають як «гра зламалась», і це найгірший спосіб
 * повідомити про десинхрон.
 *
 * <p>Кнопка синхронізації — лише в хоста: еталонним може бути тільки один
 * стан, і арбітром призначений хост. Гість бачить, на якій стадії процес.
 */
public class DesyncOverlay {

    public interface Listener {
        /** Хост натиснув «Синхронізувати». */
        void onResync();
        /** Вихід у меню — доступний обом. */
        void onLeave();
    }

    private final Label statusLabel;
    private final Label detailLabel;
    private final PlateButton resyncBtn;
    private final Table root;

    public DesyncOverlay(Stage stage, MatchRunner runner, Listener listener) {
        Table backdrop = new Table();
        backdrop.setFillParent(true);
        backdrop.setBackground(UIFactory.createModalScrim());

        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(28f, 32f, 28f, 32f);

        Label title = new Label("РОЗСИНХРОНІЗАЦІЯ", UIFactory.createScreenTitleStyle());

        detailLabel = new Label("", UIFactory.createHintStyle());
        detailLabel.setWrap(true);
        detailLabel.setAlignment(com.badlogic.gdx.utils.Align.center);

        statusLabel = new Label("", UIFactory.createErrorStyle());
        statusLabel.setWrap(true);
        statusLabel.setAlignment(com.badlogic.gdx.utils.Align.center);

        resyncBtn = PlateButton.action("СИНХРОНІЗУВАТИ");
        resyncBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) {
                if (!resyncBtn.isDisabled()) listener.onResync();
            }
        });

        PlateButton leaveBtn = PlateButton.action("ВИЙТИ В МЕНЮ");
        leaveBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { listener.onLeave(); }
        });

        float w = 560f;
        panel.add(title).left().row();
        panel.add(new Image(UIFactory.createRuleDrawable()))
             .height(1f).growX().padTop(12f).padBottom(20f).row();
        panel.add(statusLabel).width(w).padBottom(10f).row();
        panel.add(detailLabel).width(w).padBottom(24f).row();
        if (runner.isHost()) panel.add(resyncBtn).size(280f, 46f).padBottom(10f).row();
        panel.add(leaveBtn).size(220f, 42f).row();

        backdrop.add(panel);
        root = backdrop;
        stage.addActor(backdrop);

        update(runner);
    }

    /** Оновити текст і доступність кнопки. Викликається щокадру, поки вікно видиме. */
    public void update(MatchRunner runner) {
        String summary = runner.getDesyncSummary();
        statusLabel.setText(summary == null ? "Стани гравців розійшлися." : summary);

        boolean canResync = false;
        String detail;

        switch (runner.getResyncPhase()) {
            case AWAITING_ACKS:
                detail = "Роздаю стан гравцям… підтверджено "
                       + runner.getAckCount() + " з " + runner.getAckExpected() + ".";
                break;
            case AWAITING_RESUME:
                detail = "Стан отримано. Чекаємо, поки хост дасть продовжити.";
                break;
            case FAILED:
                detail = runner.getResyncError() != null
                       ? runner.getResyncError() + " Спробуй ще раз або вийди в меню."
                       : "Синхронізація не вдалась.";
                canResync = runner.isHost();
                break;
            default:
                detail = runner.isHost()
                       ? "Далі грати не можна: симуляції розійшлись і розходитимуться більше. "
                       + "Синхронізація роздасть твій стан усім."
                       : "Далі грати не можна. Чекаємо, поки хост синхронізує стан.";
                canResync = runner.isHost();
                break;
        }

        detailLabel.setText(detail);
        resyncBtn.setDisabled(!canResync);
        resyncBtn.getColor().a = canResync ? 1f : 0.45f;
    }

    public void remove() { root.remove(); }
}
