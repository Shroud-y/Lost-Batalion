package io.jababa.lost_batalion.commands;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.units.Unit;
import io.jababa.lost_batalion.units.UnitManager;

public class CurvedFormationCommand {

    private static final float SAMPLE_DIST = 3f;

    private static final String TILE_PATH = "ui/formation_tile.png";
    private static final float  TILE_SIZE_PX = 12f;
    private static final float  TILE_GAP_PX  = 4f;

    private static final float MIN_GAP = 8f;

    private static final int SKIP_ADJACENT = 6;

    private final Array<Vector2> path    = new Array<>();
    private boolean active  = false;
    private boolean drawing = false;
    private Texture tileTex = null;

    public CurvedFormationCommand() {
        if (Gdx.files.internal(TILE_PATH).exists()) {
            tileTex = new Texture(Gdx.files.internal(TILE_PATH));
        }
    }


    public void startDraw(float worldX, float worldY) {
        path.clear();
        path.add(new Vector2(worldX, worldY));
        active  = true;
        drawing = true;
    }

    public void addPoint(float worldX, float worldY) {
        if (!drawing || path.size == 0) return;

        Vector2 last = path.get(path.size - 1);
        float dx = worldX - last.x;
        float dy = worldY - last.y;
        if (dx * dx + dy * dy < SAMPLE_DIST * SAMPLE_DIST) return;

        Vector2 candidate = new Vector2(worldX, worldY);

        if (path.size >= 2 && tooCloseOrIntersects(last, candidate)) return;

        path.add(candidate);
    }

    public void tickCurrentCursor(float worldX, float worldY) {
        addPoint(worldX, worldY);
    }

    public boolean finishAndApply(UnitManager unitManager, float mapW, float mapH) {
        drawing = false;
        if (path.size < 2) { cancel(); return false; }
        Array<Unit> selected = unitManager.getSelectedUnits();
        if (selected.size == 0) { cancel(); return false; }
        placeUnitsAlongPath(selected, mapW, mapH);
        active = false;
        path.clear();
        return true;
    }

    public void cancel() {
        active  = false;
        drawing = false;
        path.clear();
    }

    public boolean isDrawing() { return drawing; }
    public boolean isActive()  { return active; }


    public void draw(SpriteBatch batch, float zoom) {
        if (!drawing || path.size < 2 || tileTex == null) return;

        float tileSize = TILE_SIZE_PX * zoom;
        float step     = (TILE_SIZE_PX + TILE_GAP_PX) * zoom;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        batch.begin();
        batch.setColor(1f, 1f, 1f, 0.95f);

        float accumulated = 0f;
        float nextTile    = 0f;

        for (int i = 0; i < path.size - 1; i++) {
            Vector2 a = path.get(i);
            Vector2 b = path.get(i + 1);

            float sdx = b.x - a.x;
            float sdy = b.y - a.y;
            float len = (float) Math.sqrt(sdx * sdx + sdy * sdy);
            if (len < 0.01f) continue;

            float dirX = sdx / len;
            float dirY = sdy / len;
            float angleDeg = (float) Math.toDegrees(Math.atan2(dirY, dirX));
            float segPos = nextTile - accumulated;

            while (segPos <= len) {
                if (segPos >= 0f) {
                    float wx = a.x + dirX * segPos;
                    float wy = a.y + dirY * segPos;
                    batch.draw(tileTex,
                        wx - tileSize / 2f, wy - tileSize / 2f,
                        tileSize / 2f, tileSize / 2f,
                        tileSize, tileSize,
                        1f, 1f, angleDeg,
                        0, 0, tileTex.getWidth(), tileTex.getHeight(),
                        false, false);
                }
                segPos += step;
            }

            accumulated += len;
            nextTile = accumulated + (segPos > len ? segPos - len : step);
        }

        batch.setColor(1f, 1f, 1f, 1f);
        batch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    public void drawFallback(ShapeRenderer shapes, float zoom) {
        if (!drawing || path.size < 2) return;
        float hw = 4f * zoom;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(1f, 0.88f, 0.15f, 0.92f);
        for (int i = 0; i < path.size - 1; i++) {
            Vector2 a = path.get(i), b = path.get(i + 1);
            float dx = b.x-a.x, dy = b.y-a.y;
            float len = (float) Math.sqrt(dx*dx + dy*dy);
            if (len < 0.01f) continue;
            float nx = -dy/len*hw, ny = dx/len*hw;
            shapes.triangle(a.x+nx, a.y+ny, a.x-nx, a.y-ny, b.x-nx, b.y-ny);
            shapes.triangle(a.x+nx, a.y+ny, b.x-nx, b.y-ny, b.x+nx, b.y+ny);
        }
        shapes.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    public void dispose() {
        if (tileTex != null) tileTex.dispose();
    }

    private boolean tooCloseOrIntersects(Vector2 p1, Vector2 p2) {
        if (path.size < SKIP_ADJACENT + 1) return false;

        int limit = path.size - 1 - SKIP_ADJACENT;

        for (int i = 0; i < limit; i++) {
            Vector2 a = path.get(i);
            Vector2 b = path.get(i + 1);

            if (segmentsIntersect(p1, p2, a, b)) return true;
            if (segmentToSegmentDist(p1, p2, a, b) < MIN_GAP) return true;
        }
        return false;
    }

    private float segmentToSegmentDist(Vector2 p1, Vector2 p2,
                                       Vector2 p3, Vector2 p4) {
        float d1 = pointToSegDist(p1, p3, p4);
        float d2 = pointToSegDist(p2, p3, p4);
        float d3 = pointToSegDist(p3, p1, p2);
        float d4 = pointToSegDist(p4, p1, p2);
        return Math.min(Math.min(d1, d2), Math.min(d3, d4));
    }

    private float pointToSegDist(Vector2 p, Vector2 a, Vector2 b) {
        float dx = b.x - a.x, dy = b.y - a.y;
        float lenSq = dx*dx + dy*dy;
        if (lenSq < 0.0001f) return p.dst(a);
        float t = Math.max(0f, Math.min(1f,
            ((p.x-a.x)*dx + (p.y-a.y)*dy) / lenSq));
        float ex = p.x - (a.x + t*dx);
        float ey = p.y - (a.y + t*dy);
        return (float) Math.sqrt(ex*ex + ey*ey);
    }

    private boolean segmentsIntersect(Vector2 p1, Vector2 p2,
                                      Vector2 p3, Vector2 p4) {
        float d1x = p2.x-p1.x, d1y = p2.y-p1.y;
        float d2x = p4.x-p3.x, d2y = p4.y-p3.y;
        float cross = d1x*d2y - d1y*d2x;
        if (Math.abs(cross) < 0.0001f) return false;
        float dx = p3.x-p1.x, dy = p3.y-p1.y;
        float t = (dx*d2y - dy*d2x) / cross;
        float u = (dx*d1y - dy*d1x) / cross;
        float eps = 0.05f;
        return t > eps && t < 1f-eps && u > eps && u < 1f-eps;
    }

    private void placeUnitsAlongPath(Array<Unit> units, float mapW, float mapH) {
        float totalLen = 0f;
        float[] segLens = new float[path.size - 1];
        for (int i = 0; i < path.size - 1; i++) {
            segLens[i] = path.get(i).dst(path.get(i + 1));
            totalLen  += segLens[i];
        }
        if (totalLen < 0.01f) return;

        int count = units.size;
        for (int ui = 0; ui < count; ui++) {
            float t      = count == 1 ? 0.5f : (float) ui / (count - 1);
            float target = t * totalLen;
            float accumulated = 0f;
            Vector2 pos = new Vector2(path.get(0));

            for (int si = 0; si < segLens.length; si++) {
                if (accumulated + segLens[si] >= target) {
                    float localT = (target - accumulated) / segLens[si];
                    Vector2 a = path.get(si), b = path.get(si + 1);
                    pos.set(a.x + (b.x-a.x)*localT, a.y + (b.y-a.y)*localT);
                    break;
                }
                accumulated += segLens[si];
                pos.set(path.get(si + 1));
            }

            float half = units.get(ui).getSize() * 0.5f;
            units.get(ui).moveTo(
                Math.max(half, Math.min(mapW-half, pos.x)),
                Math.max(half, Math.min(mapH-half, pos.y)));
        }
    }
}
