package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Interpolation;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import io.jababa.lost_batalion.ui.PlateButton;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Підсумок матчу: смуга на всю ширину поверх затемненого поля бою.
 *
 * <h3>Чому це не {@link MatchNoticeOverlay}</h3>
 * Раніше результат показувало те саме вікно, що й обірваний зв'язок, з
 * поясненням «стан однаковий — матч скінчився, лишився один вихід». Для обриву
 * це й досі так: там сталася аварія, і сказати про неї треба рівно один раз,
 * коротко. А кінець матчу — єдина подія в грі, заради якої гравець і грав, і
 * панель на 520 пікселів з абзацом тексту повідомляла її тим самим тоном, що й
 * розрив TCP. Тому вікно розійшлось надвоє: аварія лишилась вікном, підсумок
 * став смугою.
 *
 * <h3>Форма</h3>
 * Смуга тягнеться від краю до краю, а поле бою лишається видимим над нею й під
 * нею — матч скінчився, але дивитись гравець мусить усе ще на карту, а не на
 * діалог посеред екрана. Золоті лінійки зверху й знизу — та сама лінійка, що
 * під заголовками екранів, тільки в акценті й на всю ширину.
 *
 * <h3>Що тут можна анімувати, а що ні</h3>
 * {@code Table} на кожній розкладці викликає {@code setBounds} дітям, тобто
 * СКИДАЄ позицію й розмір. Тому рух через {@code moveBy} тут неможливий у
 * принципі: наступний кадр поверне актора на місце. Скидається саме геометрія,
 * а не {@code scale} й не колір — на цих двох і побудована вся поява:
 * прозорість для написів, {@code scaleX} для лінійок.
 */
public class MatchResultOverlay {

    public interface Listener {
        void onLeave();
    }

    /** Підсумок у тому вигляді, в якому його показують. Рахує не цей клас. */
    public static class Summary {
        public String title;
        public String cause;
        public int scoreSelf, scoreFoe;
        public int durationSeconds;
        public int pointsHeld, pointsTotal;
        public int unitsLost, unitsKilled;
        /** Порожній підсумок: матч обірвався, чисел немає. */
        public boolean statsKnown = true;
    }

    // ── Тайминг появи (DESIGN §9) ─────────────────────────────────────────
    //
    // Порядок не косметичний: спершу гасне поле, потім лінійки окреслюють
    // місце, і лише тоді в готову рамку лягає слово. Якщо пустити все разом,
    // кадр читається як «вискочив діалог».

    private static final float FADE_SCRIM   = 0.50f;
    private static final float RULE_AT      = 0.50f, RULE_TIME  = 0.30f;
    private static final float TITLE_AT     = 0.80f, TITLE_TIME = 0.35f;
    private static final float CAUSE_AT     = 1.00f;
    private static final float SCORE_AT     = 1.15f;
    private static final float STATS_AT     = 1.35f, STAT_STEP  = 0.07f;
    private static final float BUTTON_AT    = 1.70f;
    private static final float ITEM_TIME    = 0.30f;

    /** Ширина кольорової риски під числом рахунку. */
    private static final float SIDE_BAR_WIDTH = 84f;

    private final Table root;

    public MatchResultOverlay(Stage stage, Summary s, Listener listener) {
        Table backdrop = new Table();
        backdrop.setFillParent(true);
        backdrop.setBackground(UIFactory.createModalScrim());

        Table band = new Table();
        band.setBackground(UIFactory.createColorDrawable(new Color(0.047f, 0.059f, 0.075f, 0.72f)));

        Image ruleTop    = accentRule();
        Image ruleBottom = accentRule();

        band.add(ruleTop).height(2f).growX().row();
        band.add(buildBody(s)).growX().pad(30f, 0f, 26f, 0f).row();
        band.add(ruleBottom).height(2f).growX().row();

        PlateButton leaveBtn = PlateButton.action("ВИЙТИ В МЕНЮ");
        leaveBtn.addListener(new ChangeListener() {
            @Override public void changed(ChangeEvent e, Actor a) { listener.onLeave(); }
        });

        backdrop.add(band).growX().row();
        backdrop.add(leaveBtn).size(280f, 46f).padTop(34f).row();

        animate(backdrop, ruleTop, ruleBottom, leaveBtn);

        root = backdrop;
        stage.addActor(backdrop);
    }

    // ── Побудова ──────────────────────────────────────────────────────────

    /** Заголовок, причина, табло рахунку й рядок статистики — усе в одну колонку. */
    private Table buildBody(Summary s) {
        Table body = new Table();

        titleLabel = new Label(s.title, UIFactory.createResultTitleStyle(UIFactory.COLOR_TEXT));
        titleLabel.setAlignment(Align.center);
        body.add(titleLabel).padBottom(10f).row();

        causeLabel = new Label(s.cause, UIFactory.createResultCauseStyle());
        causeLabel.setAlignment(Align.center);
        body.add(causeLabel).padBottom(18f).row();

        scoreBlock = buildScore(s);
        body.add(scoreBlock).padBottom(s.statsKnown ? 22f : 0f).row();

        if (s.statsKnown) body.add(buildStats(s)).row();
        return body;
    }

    /**
     * Табло рахунку.
     *
     * <p>Числа ЗОЛОТІ, а не в кольорах сторін: синьо-червоні цифри вже пробували
     * в HUD і відкинули — дві насичені барви вибивались зі спокійної золотої
     * гами. Сторону тут показує тонка риска ПІД числом, тобто рівно там, де вона
     * щось розрізняє, і рівно тим самим кольором, що зона на землі.
     */
    private Table buildScore(Summary s) {
        Table t = new Table();
        t.add(sideColumn(s.scoreSelf, UIFactory.COLOR_TEAM_SELF, "ВИ")).padRight(46f);
        t.add(verticalRule()).width(2f).height(40f).padRight(46f);
        t.add(sideColumn(s.scoreFoe, UIFactory.COLOR_TEAM_FOE, "СУПРОТИВНИК"));
        return t;
    }

    private Table sideColumn(int score, Color side, String caption) {
        Table col = new Table();

        Label number = new Label(Integer.toString(score), UIFactory.createResultScoreStyle());
        number.setAlignment(Align.center);
        col.add(number).row();

        Image bar = new Image(UIFactory.createColorDrawable(side));
        col.add(bar).width(SIDE_BAR_WIDTH).height(2f).padTop(6f).row();

        Label cap = new Label(caption, UIFactory.createResultCaptionStyle());
        cap.setAlignment(Align.center);
        col.add(cap).padTop(6f).row();
        return col;
    }

    /**
     * Рядок статистики: чотири колонки «число зверху, підпис знизу».
     *
     * <p>Число крупніше за підпис навмисно — гравець читає рядок числами, а
     * підпис дивиться лише там, де число здивувало.
     */
    private Table buildStats(Summary s) {
        Table t = new Table();

        String[][] cells = {
            { formatDuration(s.durationSeconds),         "ТРИВАЛІСТЬ" },
            { s.pointsHeld + " з " + s.pointsTotal,      "ТОЧОК НАПРИКІНЦІ" },
            { Integer.toString(s.unitsLost),             "ВТРАЧЕНО" },
            { Integer.toString(s.unitsKilled),           "ЗНИЩЕНО" },
        };

        statColumns = new Table[cells.length];
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) t.add(verticalRule()).width(1f).height(36f).padLeft(30f).padRight(30f);

            Table col = new Table();
            Label value = new Label(cells[i][0], UIFactory.createResultStatStyle());
            value.setAlignment(Align.center);
            col.add(value).row();

            Label cap = new Label(cells[i][1], UIFactory.createResultCaptionStyle());
            cap.setAlignment(Align.center);
            col.add(cap).padTop(6f).row();

            statColumns[i] = col;
            t.add(col).width(180f);
        }
        return t;
    }

    /** {@code мм:сс}. Годин не буває: матч за очками впирається в стелю задовго до того. */
    private static String formatDuration(int seconds) {
        if (seconds < 0) seconds = 0;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    /**
     * Лінійка, яка ТРИМАЄ центр як точку опори.
     *
     * <p>Звичайного {@code setOrigin(Align.center)} у конструкторі не досить:
     * там ширина ще нульова, бо її задасть {@code Table} аж на першій
     * розкладці — і опора назавжди лишається в лівому краю, а розчерк, який
     * мав розходитись у два боки, виповзає збоку. {@code sizeChanged} — єдине
     * місце, де ширина вже відома і про кожну наступну зміну теж повідомлять.
     */
    private static class CenteredRule extends Image {
        CenteredRule(Color color) { super(UIFactory.createColorDrawable(color)); }
        @Override protected void sizeChanged() {
            super.sizeChanged();
            setOrigin(Align.center);
        }
    }

    private static Image accentRule() {
        return new CenteredRule(UIFactory.COLOR_ACCENT);
    }

    private static Image verticalRule() {
        return new Image(UIFactory.createColorDrawable(new Color(0.47f, 0.43f, 0.34f, 0.55f)));
    }

    // ── Поява ─────────────────────────────────────────────────────────────

    private Label titleLabel, causeLabel;
    private Table scoreBlock;
    private Table[] statColumns;

    /**
     * Розкласти появу в часі.
     *
     * <p>Діти стартують невидимими й проявляються самі: {@code parentAlpha}
     * множиться, тож затемнення може виїхати цілком, поки вміст на ньому ще
     * порожній.
     */
    private void animate(Table backdrop, Image ruleTop, Image ruleBottom, PlateButton button) {
        backdrop.getColor().a = 0f;
        backdrop.addAction(Actions.fadeIn(FADE_SCRIM, Interpolation.smooth));

        sweep(ruleTop);
        sweep(ruleBottom);

        appear(titleLabel, TITLE_AT, TITLE_TIME);
        appear(causeLabel, CAUSE_AT, ITEM_TIME);
        appear(scoreBlock, SCORE_AT, ITEM_TIME);
        if (statColumns != null) {
            for (int i = 0; i < statColumns.length; i++) {
                appear(statColumns[i], STATS_AT + i * STAT_STEP, ITEM_TIME);
            }
        }

        // Кнопка не тільки невидима, а й НЕ НАТИСКАЄТЬСЯ, поки не проявилась:
        // невидима кнопка посеред екрана ловила б клік, яким гравець ще
        // намагався командувати військом.
        appear(button, BUTTON_AT, ITEM_TIME);
        button.setTouchable(Touchable.disabled);
        button.addAction(Actions.delay(BUTTON_AT + ITEM_TIME,
                         Actions.touchable(Touchable.enabled)));
    }

    /**
     * Лінійка розчерком від центру.
     *
     * <p>Саме {@code scaleX}, а не ширина: ширину задає комірка таблиці й
     * поверне її на наступній розкладці. Опору тримає {@link CenteredRule},
     * тому розчерк іде в обидва боки одночасно.
     */
    private static void sweep(Image rule) {
        rule.setScaleX(0f);
        rule.addAction(Actions.delay(RULE_AT,
            Actions.scaleTo(1f, 1f, RULE_TIME, Interpolation.smooth)));
    }

    private static void appear(Actor actor, float at, float time) {
        if (actor == null) return;
        actor.getColor().a = 0f;
        actor.addAction(Actions.delay(at, Actions.fadeIn(time, Interpolation.smooth)));
    }

    public void remove() { root.remove(); }
}
