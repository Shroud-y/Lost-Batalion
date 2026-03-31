package io.jababa.lost_batalion.units;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;

public class UnitManager {

    private final Array<Unit> allUnits      = new Array<>();
    private final Array<Unit> selectedUnits = new Array<>();

    public void addUnit(Unit unit) { allUnits.add(unit); }

    public void spawnPlayerSquad(float centerX, float centerY) {
        float spacing = Infantry.INF_SIZE + 8f;
        addUnit(new Infantry(Team.PLAYER, centerX - spacing, centerY));
        addUnit(new Infantry(Team.PLAYER, centerX,           centerY));
        addUnit(new Infantry(Team.PLAYER, centerX + spacing, centerY));
    }


    public void update(float delta) {
        for (Unit u : allUnits) u.update(delta);

        Array<Unit> dead = new Array<>();
        for (Unit u : allUnits) if (!u.alive) dead.add(u);
        selectedUnits.removeAll(dead, true);
    }

    public boolean trySelectAtPoint(float x, float y) {
        Unit found = null;

        synchronized(allUnits) {
            for (int i = 0; i < allUnits.size; i++) {
                Unit u = allUnits.get(i);
                if (u == null || u.team != Team.PLAYER || !u.alive) continue;

                float halfSize = u.getSize() / 2f;

                if (x >= u.position.x - halfSize && x <= u.position.x + halfSize &&
                    y >= u.position.y - halfSize && y <= u.position.y + halfSize) {
                    found = u;

                }
            }
        }

        if (found != null) {

            clearSelection();
            found.selected = true;
            if (!selectedUnits.contains(found, true)) {
                selectedUnits.add(found);
            }
            return true;
        }
        return false;
    }

    public void selectInRect(float rx, float ry, float rw, float rh, boolean replace) {
        if (replace) clearSelection();
        Rectangle rect = new Rectangle(rx, ry, rw, rh);
        for (Unit u : allUnits) {
            if (u.team != Team.PLAYER || !u.alive) continue;
            if (rect.contains(u.position.x, u.position.y)) {
                u.selected = true;
                if (!selectedUnits.contains(u, true)) selectedUnits.add(u);
            }
        }
    }

    public void clearSelection() {
        for (Unit u : selectedUnits) u.selected = false;
        selectedUnits.clear();
    }

    public void moveSelectedTo(float worldX, float worldY, float mapW, float mapH) {
        int   count   = selectedUnits.size;
        if (count == 0) return;

        float spacing = Infantry.INF_SIZE + 6f;
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(count)));

        for (int i = 0; i < count; i++) {
            Unit  u   = selectedUnits.get(i);
            int col = i % cols;
            int row = i / cols;

            float tx = worldX + (col - cols / 2f) * spacing;
            float ty = worldY - row * spacing;

            float half = u.getSize() * 0.5f;
            tx = Math.max(half, Math.min(mapW - half, tx));
            ty = Math.max(half, Math.min(mapH - half, ty));

            u.moveTo(tx, ty);
        }
    }

    public Array<Unit> getAllUnits() { return allUnits; }
    public Array<Unit> getSelectedUnits() { return selectedUnits; }
    public boolean hasSelection() { return selectedUnits.size > 0; }
}
