package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.units.Unit;

/**
 * Маршрут виділених юнітів: куди кожен ІДЕ насправді.
 *
 * <p>Не пряма від юніта до кінцевої точки, а сам маршрут з усіма поворотами:
 * після подвійного ПКМ юніт обходить ліс і річку, і пряма лінія показувала б
 * зовсім не той шлях, яким він піде. Тому лінія будується по тих самих
 * вузлах, якими юніт керується ({@link Unit#routeX}), і по тих самих зсувах
 * смуги — інакше вона проходила б повз місце, куди він справді прийде.
 *
 * <p>Пройдене САМО зникає: перша точка лінії — поточна позиція юніта, а вузли
 * позаду вже викинуті з залишку маршруту. Ніякого окремого «стирання» для
 * цього не треба.
 *
 * <p>Малюється лише для виділених. Постійні лінії над усією армією
 * перетворили б поле на павутину — маршрут потрібен рівно тоді, коли гравець
 * питає «а куди воно йде», тобто коли юніт у нього виділений.
 */
public class OrderRouteRenderer {

    /**
     * Тонша за лінії наказу (ті 3 px): маршрут — це довідка про вже віддану
     * команду, а не сама команда, і він не має сперечатись за увагу з тим,
     * що гравець малює просто зараз.
     */
    private static final float LINE_PX = 2f;

    /** Той самий білий, що й решта графіки поверх карти (DESIGN §2). */
    private static final float R = 1f, G = 1f, B = 1f;

    /** Коротший «маршрут» — це юніт, який фактично дійшов; лінія лише блимала б. */
    private static final float MIN_LENGTH = 4f;

    public void render(ShapeRenderer shapes, Array<Unit> selected, float zoom) {
        if (selected == null || selected.size == 0) return;

        float thickness = LINE_PX * zoom;
        boolean opened = false;

        for (int i = 0; i < selected.size; i++) {
            Unit u = selected.get(i);
            if (!u.alive || !u.isMoving()) continue;

            float x = u.worldX();
            float y = u.worldY();

            if (!opened) {
                Gdx.gl.glEnable(GL20.GL_BLEND);
                Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                shapes.begin(ShapeRenderer.ShapeType.Filled);
                // Напівпрозоріша за лінії наказу — з тієї ж причини, що й тонша.
                shapes.setColor(R, G, B, 0.55f);
                opened = true;
            }

            if (u.hasPath()) {
                // Ланка за ланкою від поточної позиції через усі вузли, що
                // лишились. Кружечок у стику — інакше на різкому повороті між
                // двома смугами лишається клин порожнечі.
                int points = u.remainingRoutePoints();
                for (int p = 0; p < points; p++) {
                    float nx = u.routeX(p);
                    float ny = u.routeY(p);
                    if (dist(x, y, nx, ny) >= MIN_LENGTH) {
                        shapes.rectLine(x, y, nx, ny, thickness);
                        if (p > 0) shapes.circle(x, y, thickness * 0.5f, 8);
                    }
                    x = nx;
                    y = ny;
                }
            } else {
                float tx = Fixed.toFloat(u.getTargetX());
                float ty = Fixed.toFloat(u.getTargetY());
                if (dist(x, y, tx, ty) >= MIN_LENGTH) shapes.rectLine(x, y, tx, ty, thickness);
            }
        }

        if (opened) {
            shapes.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }
    }

    private static float dist(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1, dy = y2 - y1;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
