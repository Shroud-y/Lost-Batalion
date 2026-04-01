package io.jababa.lost_batalion.mobile;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.math.Vector3;
import io.jababa.lost_batalion.screens.game.GameScreen;
import io.jababa.lost_batalion.screens.game.MoveMarker;
import io.jababa.lost_batalion.units.Unit;

public class MobileTouchHandler extends InputAdapter {

    private static final long  LONG_PRESS_MS = 500;
    private static final long  DOUBLE_TAP_MS = 350;
    private static final float DRAG_THRESHOLD = 20f;

    private final GameScreen screen;

    private boolean fingerDown = false;
    private float downScreenX, downScreenY;
    private long downTime;
    private boolean longPressArmed = false;
    private boolean dragging = false;

    private long lastTapTime = 0;
    private float lastTapX, lastTapY;

    public MobileTouchHandler(GameScreen screen) {
        this.screen = screen;
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (pointer != 0 || screen.isPaused()) return false;

        fingerDown = true;
        longPressArmed = false;
        dragging = false;
        downScreenX = screenX;
        downScreenY = screenY;
        downTime = System.currentTimeMillis();

        if (screen.isFormationModeActive() && screen.getUnitManager().hasSelection()) {
            Vector3 world = screen.getCamera().unproject(
                new Vector3(screenX, screenY, 0));
            screen.getFormationDrag().onRmbDown(world.x, world.y);
            screen.getFormationDrag().forceActivate();
            return true;
        }

        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (pointer != 0 || !fingerDown || screen.isPaused()) return false;

        if (screen.isFormationModeActive() && screen.getFormationDrag().isActive()) {
            Vector3 world = screen.getCamera().unproject(
                new Vector3(screenX, screenY, 0));
            screen.getFormationDrag().update(0f, world.x, world.y);
            return true;
        }

        float ddx  = screenX - downScreenX;
        float ddy  = screenY - downScreenY;
        float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        long  held = System.currentTimeMillis() - downTime;

        if (!longPressArmed && !dragging
            && held >= LONG_PRESS_MS && dist < DRAG_THRESHOLD * 2) {
            longPressArmed = true;
            Gdx.input.vibrate(40);
            Vector3 world = screen.getCamera().unproject(
                new Vector3(downScreenX, downScreenY, 0));
            screen.startSelecting(world.x, world.y);
        }

        if (longPressArmed && screen.isSelecting()) {
            Vector3 world = screen.getCamera().unproject(
                new Vector3(screenX, screenY, 0));
            screen.updateSelection(world.x, world.y);
            return true;
        }

        if (!longPressArmed && dist > DRAG_THRESHOLD) {
            dragging = true;
            float worldDx = (screenX - downScreenX) * screen.getCamera().zoom;
            float worldDy = (screenY - downScreenY) * screen.getCamera().zoom;
            screen.getCamera().position.add(-worldDx, worldDy, 0);
            downScreenX = screenX;
            downScreenY = screenY;
            return true;
        }

        return false;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (pointer != 0 || !fingerDown) return false;

        boolean wasSelecting = screen.isSelecting();
        boolean wasLongPress = longPressArmed;
        boolean wasDragging  = dragging;

        fingerDown = false;
        longPressArmed = false;
        dragging = false;

        if (screen.isPaused()) return false;

        if (screen.isFormationModeActive() && screen.getFormationDrag().isActive()) {
            screen.applyFormationLine();
            return true;
        }

        if (wasSelecting) {
            screen.finishSelection();
            return true;
        }

        if (!wasDragging && !wasLongPress) {
            long    now   = System.currentTimeMillis();
            Vector3 world = screen.getCamera().unproject(
                new Vector3(screenX, screenY, 0));

            boolean isDoubleTap = now - lastTapTime < DOUBLE_TAP_MS
                && Math.abs(screenX - lastTapX) < 60
                && Math.abs(screenY - lastTapY) < 60;

            if (isDoubleTap && screen.getUnitManager().hasSelection()) {
                lastTapTime = 0;

                Unit enemy = screen.getCombatManager()
                    .tryGetEnemyAtPoint(world.x, world.y);
                if (enemy != null) {
                    screen.getCombatManager().orderAttack(enemy);
                    screen.getMoveMarker().show(
                        enemy.position.x, enemy.position.y,
                        MoveMarker.MarkerType.ATTACK);
                } else {
                    screen.getUnitManager().moveSelectedTo(
                        world.x, world.y,
                        screen.getMapWidth(), screen.getMapHeight());
                    screen.getMoveMarker().show(
                        world.x, world.y, MoveMarker.MarkerType.MOVE);
                }
                return true;
            }

            screen.getUnitManager().trySelectAtPoint(world.x, world.y);

            lastTapTime = now;
            lastTapX    = screenX;
            lastTapY    = screenY;
            return true;
        }

        return false;
    }
}
