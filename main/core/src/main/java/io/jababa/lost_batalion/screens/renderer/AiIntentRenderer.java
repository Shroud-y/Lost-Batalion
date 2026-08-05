package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.ai.TacticalBrain;
import io.jababa.lost_batalion.capture.CapturePoint;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.units.Artillery;
import io.jababa.lost_batalion.units.Unit;

/**
 * Намір ботів просто на карті: куди тиснуть, де збираються, чи вже рушили.
 *
 * <p>Суто налагоджувальний оверлей, вмикається з {@link io.jababa.lost_batalion.debug.DevConsole}.
 * Стану гри не читає й не міняє нічого — бере готові поля мозку.
 *
 * <p>Чому це взагалі варте окремого класу: дивитись матч ботів без нього
 * означає бачити, ЩО вони роблять, і не бачити ЧОМУ. Купа стоїть на місці —
 * вона збирається чи застрягла? Рота пішла вбік — це обхід чи бот передумав?
 * Без наміру на екрані на ці питання відповідають здогадом, а здогад тут уже
 * тричі був хибним.
 */
public class AiIntentRenderer {

    /** Скільки кружечок збірного пункту. */
    private static final float RALLY_RADIUS = 26f;

    private final ShapeRenderer shapes = new ShapeRenderer();
    private final SpriteBatch   batch  = new SpriteBatch();
    private final BitmapFont    font   = UIFactory.createMapLabelFont();

    private final Array<TacticalBrain> brains = new Array<>();

    /** Зареєструвати мозок, чий намір показувати. */
    public void watch(TacticalBrain brain) {
        if (brain != null && !brains.contains(brain, true)) brains.add(brain);
    }

    public void clear() { brains.clear(); }

    public void render(OrthographicCamera camera, Array<Unit> allUnits) {
        if (brains.size == 0) return;

        shapes.setProjectionMatrix(camera.combined);
        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (int i = 0; i < brains.size; i++) drawShapes(brains.get(i), allUnits);
        shapes.end();

        batch.setProjectionMatrix(camera.combined);
        batch.begin();
        for (int i = 0; i < brains.size; i++) drawLabel(brains.get(i), camera);
        batch.end();
    }

    private void drawShapes(TacticalBrain brain, Array<Unit> allUnits) {
        Team team = Team.forPlayer(brain.getPlayerId());
        Color c = team == Team.PLAYER ? UIFactory.COLOR_TEAM_SELF : UIFactory.COLOR_TEAM_FOE;
        shapes.setColor(c.r, c.g, c.b, 0.85f);

        float rx = brain.getRallyX(), ry = brain.getRallyY();
        shapes.circle(rx, ry, RALLY_RADIUS);

        CapturePoint obj = brain.getObjective();
        if (obj != null) {
            float ox = Fixed.toFloat(obj.x), oy = Fixed.toFloat(obj.y);
            // Лінія збірний → ціль: вісь наступу. Суцільна, коли армія рушила,
            // пунктирна — поки збирається; це та сама різниця, яку гравець і
            // намагається зрозуміти, дивлячись на застиглу купу.
            if (brain.isCommitted()) shapes.line(rx, ry, ox, oy);
            else                     dashed(rx, ry, ox, oy);
            shapes.circle(ox, oy, 14f);
        }

        // Нитка від кожного бійця до збірного — видно, хто ще в дорозі.
        shapes.setColor(c.r, c.g, c.b, 0.25f);
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (!u.alive || u.team != team || u instanceof Artillery) continue;
            shapes.line(u.worldX(), u.worldY(), rx, ry);
        }
    }

    private void dashed(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;

        final float dash = 14f;
        int steps = (int) (len / dash);
        for (int i = 0; i < steps; i += 2) {
            float a = i * dash / len, b = Math.min((i + 1) * dash / len, 1f);
            shapes.line(x0 + dx * a, y0 + dy * a, x0 + dx * b, y0 + dy * b);
        }
    }

    private void drawLabel(TacticalBrain brain, OrthographicCamera camera) {
        Team team = Team.forPlayer(brain.getPlayerId());
        Color c = team == Team.PLAYER ? UIFactory.COLOR_TEAM_SELF : UIFactory.COLOR_TEAM_FOE;

        String state = brain.isCommitted() ? "ІДЕ" : "ЗБИРАЄТЬСЯ";
        // Поріг береться з БЮДЖЕТУ головного загону, а не з рівня: у бота тепер
        // кілька напрямків, і кожен має свій — показувати спільне число рівня
        // означало б порівнювати купу не з тим, чого вона чекає.
        String text = brain.getLevel().title + " · " + state
                    + " · купа " + brain.getCluster() + "/" + brain.getMassGoal()
                    + " · напрямків " + brain.getFrontCount()
                    + (brain.getObjective() == null ? "" : " · ціль " + brain.getObjective().name);

        // Колір ставиться ДО setText: GlyphLayout запікає його в момент верстки,
        // а не малювання — та сама пастка, що з літерами точок захоплення.
        font.setColor(c.r, c.g, c.b, 0.95f);
        font.draw(batch, text, brain.getRallyX() - 60f, brain.getRallyY() - RALLY_RADIUS - 6f);
    }

    public void dispose() {
        shapes.dispose();
        batch.dispose();
        // font належить UIFactory і звільняється в disposeAll()
    }
}
