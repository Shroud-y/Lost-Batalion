package io.jababa.lost_batalion.screens.game;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Disposable;
import io.jababa.lost_batalion.ui.UIFactory;

/**
 * Екран підготовки бою: назва кроку і смуга поступу.
 *
 * <p>Малюється вручну, а не через scene2d, і має ВЛАСНИЙ шрифт — не з
 * {@link UIFactory}. Причина конкретна: останній крок підготовки збирає
 * інтерфейс матчу, а він починається з {@code UIFactory.disposeAll()}. Шрифт,
 * узятий із фабрики, у цю мить став би звільненим, і наступний же кадр смуги
 * малював би знищену текстуру.
 *
 * <p>Кольори — з {@link UIFactory} (це константи, їх disposeAll не чіпає), тож
 * екран лишається в тій самій гамі, що й меню: темне тло, золото акцентом,
 * рівні краї, розріджений верхній регістр (DESIGN §1–§3).
 */
public class LoadingOverlay implements Disposable {

    /** Ті самі поля, що в меню (DESIGN §4), щоб смуга стояла де й лінійка шапки. */
    private static final float MARGIN = UIFactory.MARGIN;
    private static final float BAR_HEIGHT = 6f;

    private final SpriteBatch   batch  = new SpriteBatch();
    private final ShapeRenderer shapes = new ShapeRenderer();
    private final BitmapFont    title;
    private final BitmapFont    body;

    public LoadingOverlay() {
        title = generate(28, 8f);   // розріджено, як заголовок екрана результату
        body  = generate(14, 2f);
    }

    /**
     * Власний шрифт замість фабричного.
     *
     * <p>Білі гліфи плюс тінт при малюванні: {@code BitmapFont} множить колір
     * текстури на заданий, тож запечений білий — єдиний, що дає точний тон
     * (та сама причина, що в {@code UIFactory.generateTintableFont}).
     */
    private static BitmapFont generate(int size, float spacing) {
        FreeTypeFontGenerator generator =
            new FreeTypeFontGenerator(Gdx.files.internal("fonts/main.ttf"));
        try {
            FreeTypeFontGenerator.FreeTypeFontParameter p =
                new FreeTypeFontGenerator.FreeTypeFontParameter();
            p.size    = size;
            p.color   = Color.WHITE;
            p.spaceX  = (int) spacing;
            // ОБОВ'ЯЗКОВО: типовий набір FreeType — це латиниця з цифрами, і
            // без цього рядка весь український текст просто не малюється.
            // Саме так і сталось на першому прогоні: смуга й «25%» були, а
            // підписів не було взагалі.
            p.characters = UIFactory.FONT_CHARS;
            BitmapFont font = generator.generateFont(p);
            font.setUseIntegerPositions(true);
            return font;
        } finally {
            // Генератор більше не потрібен: гліфи вже в текстурі шрифту.
            generator.dispose();
        }
    }

    /**
     * @param progress 0..1
     * @param label    що робиться зараз
     * @param note     другий рядок; порожній — не малюється
     */
    public void render(float progress, String label, String note) {
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();

        Gdx.gl.glClearColor(0.047f, 0.059f, 0.075f, 1f);   // #0C0F13
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        float barY = h * 0.42f;
        float barW = w - MARGIN * 2f;

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        // Ложе смуги — той самий рядок списку, що в меню.
        shapes.setColor(0.09f, 0.11f, 0.13f, 1f);
        shapes.rect(MARGIN, barY, barW, BAR_HEIGHT);
        // Заповнення акцентом. Колір працює на площі в кілька пікселів —
        // тому смуга тонка, а не панель на пів екрана (DESIGN §1).
        shapes.setColor(UIFactory.COLOR_ACCENT);
        shapes.rect(MARGIN, barY, barW * Math.max(0f, Math.min(1f, progress)), BAR_HEIGHT);
        shapes.end();

        batch.begin();
        title.setColor(UIFactory.COLOR_TEXT);
        title.draw(batch, "ПІДГОТОВКА БОЮ", MARGIN, barY + 96f);

        body.setColor(UIFactory.COLOR_ACCENT);
        body.draw(batch, label, MARGIN, barY + 44f);

        body.setColor(0.47f, 0.45f, 0.41f, 1f);
        body.draw(batch, (int) (progress * 100f) + "%", MARGIN + barW - 46f, barY + 44f);

        if (note != null && !note.isEmpty()) {
            body.draw(batch, note, MARGIN, barY - 22f);
        }
        batch.end();
    }

    @Override
    public void dispose() {
        batch.dispose();
        shapes.dispose();
        title.dispose();
        body.dispose();
    }
}
