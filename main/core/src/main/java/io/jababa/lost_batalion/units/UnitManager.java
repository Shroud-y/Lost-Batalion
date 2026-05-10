package io.jababa.lost_batalion.units;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.terrain.TerrainMovementModifier;
import io.jababa.lost_batalion.terrain.TerrainType;

public class UnitManager {

    private final Array<Unit> allUnits      = new Array<>();
    private final Array<Unit> selectedUnits = new Array<>();

    public void addUnit(Unit unit) { allUnits.add(unit); }

    public void spawnPlayerSquad(float centerX, float centerY) {
        float spacing = Infantry.INF_SIZE + 8f;
        addUnit(new Infantry(Team.PLAYER, centerX - spacing, centerY));
        addUnit(new Infantry(Team.PLAYER, centerX, centerY));
        addUnit(new Infantry(Team.PLAYER, centerX + spacing, centerY));
    }

    // У UnitManager.java
    public void update(float delta, io.jababa.lost_batalion.terrain.TerrainMaskManager mask) {
        for (Unit u : allUnits) {
            float multiplier = 1.0f;
            if (mask != null) {
                boolean isForest = mask.isForestAt(u.position.x, u.position.y);
                TerrainType terrain = mask.getElevationAt(u.position.x, u.position.y);
                multiplier = TerrainMovementModifier.getMultiplier(terrain, isForest);
            }

            // Тепер передаємо два аргументи
            u.update(delta, multiplier);
        }

        // Очищення мертвих юнітів
        Array<Unit> dead = new Array<>();
        for (Unit u : allUnits) if (!u.alive) dead.add(u);
        selectedUnits.removeAll(dead, true);
    }

    // Оновлений метод для кліку по юніту
    public boolean trySelectAtPointAnyTeam(float x, float y, boolean shift) {
        Unit found = null;
        for (int i = 0; i < allUnits.size; i++) {
            Unit u = allUnits.get(i);
            if (u == null || !u.alive) continue;
            float halfSize = u.getSize() / 2f;
            if (x >= u.position.x - halfSize && x <= u.position.x + halfSize &&
                y >= u.position.y - halfSize && y <= u.position.y + halfSize) {
                found = u;
                break; // Беремо першого знайденого зверху
            }
        }

        if (found != null) {
            // Якщо SHIFT не натиснуто - очищуємо все старе
            if (!shift) {
                clearSelection();
            }

            // Додаємо юніта, якщо його ще немає у списку
            if (!selectedUnits.contains(found, true)) {
                found.selected = true;
                selectedUnits.add(found);
            }
            return true;
        }
        return false;
    }

    // Виділення рамкою
    public void selectInRect(float rx, float ry, float rw, float rh, boolean shift) {
        // Якщо не шифт - скидаємо старе виділення перед початком
        if (!shift) {
            clearSelection();
        }

        Rectangle rect = new Rectangle(rx, ry, rw, rh);
        for (Unit u : allUnits) {
            if (!u.alive) continue;
            // Для тестування можна додати перевірку на команду PLAYER
            if (rect.contains(u.position.x, u.position.y)) {
                if (!selectedUnits.contains(u, true)) {
                    u.selected = true;
                    selectedUnits.add(u);
                }
            }
        }
    }

    public void clearSelection() {
        for (Unit u : selectedUnits) u.selected = false;
        selectedUnits.clear();
    }

    // --- Решта методів без змін (рух і т.д.) ---

    public void moveSelectedTo(float worldX, float worldY, float mapW, float mapH) {
        int count = selectedUnits.size;
        if (count == 0) return;
        float spacing = Infantry.INF_SIZE + 6f;
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        for (int i = 0; i < count; i++) {
            Unit u = selectedUnits.get(i);
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

    public void moveSelectedToLine(float x1, float y1, float x2, float y2, float mapW, float mapH) {
        int count = selectedUnits.size;
        if (count == 0) return;
        float dx = x2 - x1, dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f) return;
        for (int i = 0; i < count; i++) {
            Unit u = selectedUnits.get(i);
            float t = count == 1 ? 0.5f : (float) i / (count - 1);
            float tx = x1 + dx * t, ty = y1 + dy * t;
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
