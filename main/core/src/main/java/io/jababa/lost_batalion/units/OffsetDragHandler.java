package io.jababa.lost_batalion.units;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Array;

/**
 * Shift + протяг ПКМ: посунути весь стрій на однаковий вектор.
 *
 * <p>Окремий обробник, а не режим усередині {@link FormationDragHandler}, і
 * причина не в чистоті: у того є ЗАТРИМКА (пів секунди утримання, перш ніж
 * лінія стане активною) — вона потрібна, щоб звичайний клік не перетворювався
 * на шикування. Зсуву затримка шкідлива: Shift уже сказав, чого хоче гравець,
 * тож вектор має тягтись за курсором із першого пікселя.
 *
 * <p>Малює те, що станеться: лінію від початку до курсора і привидів юнітів
 * там, де вони опиняться. Саме привиди, а не сама лінія, роблять наказ
 * зрозумілим — видно, що стрій ПЕРЕЇЖДЖАЄ, а не збирається в купу.
 */
public class OffsetDragHandler {

    /** Коротший протяг — це промах, а не наказ; такий ПКМ лишається звичайним. */
    private static final float MIN_LENGTH = 10f;

    /**
     * Білий — той самий виняток, що для рамки мінікарти й лінії шикування
     * (DESIGN §2): графіка наказу лежить поверх карти й мусить читатись над
     * будь-яким її кольором.
     */
    private static final float R = 1f, G = 1f, B = 1f;

    /** Товщина в екранних пікселях; у світові переводиться через zoom камери. */
    private static final float LINE_PX  = 3f;
    private static final float GHOST_PX = 2f;

    private float startX, startY;
    private float endX, endY;
    private boolean pressed;

    public void onRmbDown(float worldX, float worldY) {
        startX = endX = worldX;
        startY = endY = worldY;
        pressed = true;
    }

    public void update(float worldX, float worldY) {
        if (!pressed) return;
        endX = worldX;
        endY = worldY;
    }

    /** @return чи протяг досить довгий, щоб рахуватись наказом */
    public boolean onRmbUp() {
        boolean ordered = pressed && length() >= MIN_LENGTH;
        pressed = false;
        return ordered;
    }

    public void cancel() { pressed = false; }

    public boolean isPressed() { return pressed; }

    public float deltaX() { return endX - startX; }
    public float deltaY() { return endY - startY; }

    private float length() {
        float dx = deltaX(), dy = deltaY();
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Стрілка зсуву й привиди майбутніх позицій.
     *
     * <p>{@code shapes} приходить із уже виставленою матрицею камери — той
     * самий контракт, що у {@link FormationDragHandler#draw}.
     */
    public void draw(ShapeRenderer shapes, Array<Unit> selected, float zoom) {
        if (!pressed || length() < 1f) return;

        float thickness = LINE_PX  * zoom;
        float ghostLine = GHOST_PX * zoom;

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Filled + rectLine: glLineWidth товщини не дає (див. FormationDragHandler).
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(R, G, B, 0.95f);
        shapes.rectLine(startX, startY, endX, endY, thickness);

        // Вістря: дві риски під 30° до вектора. Стрілка тут не прикраса —
        // без неї лінія не каже, у який бік іти.
        float dx = deltaX(), dy = deltaY();
        float len = length();
        float ux = dx / len, uy = dy / len;
        float head = Math.min(LINE_PX * 5f * zoom, len * 0.3f);
        float cos = 0.866f, sin = 0.5f;
        shapes.rectLine(endX, endY,
            endX - head * (ux * cos - uy * sin), endY - head * (uy * cos + ux * sin), thickness);
        shapes.rectLine(endX, endY,
            endX - head * (ux * cos + uy * sin), endY - head * (uy * cos - ux * sin), thickness);

        if (selected != null) {
            shapes.setColor(R, G, B, 0.55f);
            for (int i = 0; i < selected.size; i++) {
                Unit u = selected.get(i);
                if (!u.alive) continue;
                // Хітбокс, а не спрайт: у артилерії картинка має порожні кути,
                // і привид «поїхав» би від справжньої фігури (DESIGN §7).
                float w = io.jababa.lost_batalion.math.Fixed.toFloat(u.halfWidthFixed()) * 2f;
                float h = io.jababa.lost_batalion.math.Fixed.toFloat(u.halfHeightFixed()) * 2f;
                float x = u.worldX() + dx - w / 2f;
                float y = u.worldY() + dy - h / 2f;
                // Контур із чотирьох смуг: ShapeType.Line дав би волосину.
                shapes.rectLine(x,     y,     x + w, y,     ghostLine);
                shapes.rectLine(x + w, y,     x + w, y + h, ghostLine);
                shapes.rectLine(x + w, y + h, x,     y + h, ghostLine);
                shapes.rectLine(x,     y + h, x,     y,     ghostLine);
            }
        }
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }
}
