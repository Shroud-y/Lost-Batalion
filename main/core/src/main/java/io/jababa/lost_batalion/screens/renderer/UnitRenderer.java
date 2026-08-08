package io.jababa.lost_batalion.screens.renderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.ObjectMap;
import io.jababa.lost_batalion.debug.DevView;
import io.jababa.lost_batalion.Team;
import io.jababa.lost_batalion.math.Fixed;
import io.jababa.lost_batalion.sim.PlayerRoster;
import io.jababa.lost_batalion.ui.UIFactory;
import io.jababa.lost_batalion.units.Artillery;
import io.jababa.lost_batalion.units.Unit;

public class UnitRenderer {

    private final ObjectMap<String, Texture> textureCache = new ObjectMap<>();
    private final ShapeRenderer shapes;

    private static final float OUTLINE_PAD       = 0f;
    private static final float BAR_W             = 0.7f;
    private static final float BAR_LEFT          = 0.5f;

    /**
     * Наскільки прозорим стає юніт, поки біжить.
     *
     * <p>Не «трохи блідішим»: прозорість тут несе сенс «його зараз немає» —
     * він не слухає, не стріляє й не тримає точку. Гравець мусить читати це з
     * одного погляду на юрбу, а не звіряючи відтінки.
     */
    private static final float BROKEN_ALPHA = 0.45f;

    /**
     * Проміжок між баром здоров'я і баром моралі.
     *
     * <p>Обидва ліворуч від фігури, мораль — крайня. Дзеркальну розкладку
     * (мораль праворуч) пробували першою і відкинули: у бою погляд читає
     * стовпчик збоку від юніта, і два боки означали два місця, куди треба
     * дивитись. Тепер це одна колонка, і бари порівнюються між собою.
     */
    private static final float MORALE_BAR_GAP = 0.4f;

    /**
     * Склад матчу й номер того, хто дивиться, — для обводки союзників.
     *
     * <p>Ставиться один раз на матч: обидва незмінні від старту. {@code null}
     * означає «нікого розрізняти» і вимикає обводку цілком — так працює
     * одиночна гра, поки {@code setViewer} ніхто не викликав.
     */
    private PlayerRoster roster;
    private int localPlayerId = -1;

    public UnitRenderer() {
        shapes = new ShapeRenderer();
    }

    public void setViewer(PlayerRoster roster, int localPlayerId) {
        this.roster        = roster;
        this.localPlayerId = localPlayerId;
    }

    /**
     * Чи носить цей юніт кольорову позначку власника.
     *
     * <p>Рівно три правила, і кожне зі своєї причини:
     * <ul>
     *   <li><b>Союзник — так.</b> Це єдиний випадок, коли треба знати ЧИЄ
     *       військо: наказу йому не віддаси, а рахувати на нього в бою
     *       доводиться.</li>
     *   <li><b>Свої — ні.</b> Гравець і так знає, що його; обводка на кожному
     *       власному юніті зробила б поле строкатим і знецінила б обводку
     *       ВИДІЛЕННЯ, яка справді щось повідомляє.</li>
     *   <li><b>Вороги — ні.</b> Розрізняти чужих гравців між собою гравцеві
     *       нічим не допоможе: наказів він їм не віддає, а сторону вже показує
     *       спрайт.</li>
     * </ul>
     */
    private boolean marksOwner(Unit u) {
        return roster != null
            && u.owner != localPlayerId
            && roster.allied(u.owner, localPlayerId);
    }

    /**
     * Рендер спрайтів очима сторони {@code viewer}.
     *
     * <p>Видимість рахується для обох сторін і належить симуляції; рендер лише
     * бере той її бік, за який грає локальний гравець. Кольори спрайтів при
     * цьому абсолютні (хост синій, гість червоний) — гість бачить свою армію
     * червоною, зате обидва бачать однакову картинку.
     *
     * @param alpha частка тіку, що вже минула (0..1). Позиція береться між
     *              станом на початок тіку і поточним — симуляція йде 40 разів
     *              на секунду, і без цього рух виглядав би ривками.
     */
    public void drawSprites(SpriteBatch batch, Iterable<Unit> units, float alpha, Team viewer) {
        for (Unit u : units) {
            if (!u.alive) continue;
            if (!DevView.visible(u, viewer)) continue; // туман (dev: reveal)

            Texture tex = getTexture(u);
            // getSizePx(), а НЕ getSize(): друге повертає fixed-point long, і
            // Java мовчки розширила б його у float — юніт розміром 10 малювався б
            // розміром 655360 і закривав би собою всю карту.
            // Ширина й висота окремо: спрайт легкої піхоти 29×11, і квадрат
            // показував би її розчавленою. У решти обидва числа однакові.
            float w     = u.renderWidthPx();
            float h     = u.renderHeightPx();
            // Тремтіння бою — тільки спрайт. Обводка виділення й HP-бар стоять
            // рівно: смужка, що дрижить разом із фігурою, читається як брак
            // рендера, а не як напруга бою.
            float x     = u.renderX(alpha) - w / 2f + u.shakeOffsetX();
            float y     = u.renderY(alpha) - h / 2f + u.shakeOffsetY();

            if (u instanceof Artillery) {
                // Віддача: гармату відкидає НАЗАД уздовж ствола. Це рендер —
                // у симуляції вона стоїть на місці.
                float recoil = ((Artillery) u).recoilOffsetPx();
                if (recoil > 0f) {
                    float rad = Fixed.toFloat(u.facing);
                    x -= MathUtils.cos(rad) * recoil;
                    y -= MathUtils.sin(rad) * recoil;
                }
            }

            // Зламаний малюється напівпрозорим. Колір ставиться на batch, а не
            // домішується в текстуру, і обов'язково повертається назад: batch
            // спільний, і забутий колір пофарбував би все, що малюється далі.
            boolean broken = u.isBroken();
            if (broken) batch.setColor(1f, 1f, 1f, BROKEN_ALPHA);

            if (u.hasFacing()) {
                // Юніти з напрямком (артилерія) малюються поверненими. Кут —
                // стан симуляції, тут його лише переводять у градуси.
                batch.draw(tex, x, y, w / 2f, h / 2f, w, h, 1f, 1f,
                           u.facingDegrees(), 0, 0, tex.getWidth(), tex.getHeight(),
                           false, false);
            } else {
                batch.draw(tex, x, y, w, h);
            }

            if (broken) batch.setColor(Color.WHITE);
        }
    }

    /** Рендер оверлеїв (виділення, HP-бар) очима сторони {@code viewer}. */
    public void drawOverlays(Iterable<Unit> units, float alpha, Team viewer) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        shapes.begin(ShapeRenderer.ShapeType.Line);
        for (Unit u : units) {
            if (!u.alive) continue;
            if (!DevView.visible(u, viewer)) continue;
            // else-if безпечний: союзного юніта виділити НЕ можна взагалі
            // (UnitManager.collectOwned фільтрує накази й виділення за owner),
            // тож два стани зійтись на одному юніті не можуть.
            if (u.selected)        drawOutline(u, alpha);
            else if (marksOwner(u)) drawOwnerMark(u, alpha);
        }
        shapes.end();

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (Unit u : units) {
            if (!u.alive) continue;
            if (!DevView.visible(u, viewer)) continue;
            // Ціле здоров'я бара не малює: смужка має привертати увагу до того,
            // хто вже отримав по зубах, а не стояти над кожним юнітом завжди.
            // Порівняння цілих (Q47.16), а не hpRatio(): ratio міг би дати 1.0
            // після округлення при подряпині в кілька одиниць.
            if (u.hp >= u.maxHp) continue;
            drawHpBar(u, alpha);
        }
        // Другим проходом, а не в тому ж циклі: у моралі власна умова показу,
        // і юніт із цілим hp та просілою мораллю мусить показати саме її.
        for (Unit u : units) {
            if (!u.alive) continue;
            if (!DevView.visible(u, viewer)) continue;
            if (u.morale >= u.maxMorale) continue;
            drawMoraleBar(u, alpha);
        }
        shapes.end();

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    public void setProjectionMatrix(com.badlogic.gdx.math.Matrix4 combined) {
        shapes.setProjectionMatrix(combined);
    }

    // ── Приватні методи ───────────────────────────────────────────────────

    private void drawOutline(Unit u, float alpha) {
        // Обводка по хітбоксу, а не по спрайту: інакше в артилерії рамка
        // обводить порожні кути картинки і виглядає завеликою. Півосі окремо —
        // у легкої піхоти хітбокс витягнутий, і квадратна рамка брехала б про
        // те, куди по ній можна клікнути.
        float halfW = u.getHalfWidthPx();
        float halfH = u.getHalfHeightPx();
        float pad   = OUTLINE_PAD;
        float x     = u.renderX(alpha) - halfW - pad;
        float y     = u.renderY(alpha) - halfH - pad;
        float w     = halfW * 2f + pad * 2f;
        float h     = halfH * 2f + pad * 2f;

        shapes.setColor(1f, 1f, 1f, 0.9f);
        shapes.rect(x, y, w, h);

    }

    /**
     * Наскільки позначка власника стоїть ДАЛІ від фігури, ніж обводка виділення.
     *
     * <p>Не декор: обводка виділення йде впритул до хітбокса, і позначка на тому
     * самому місці читалась би як «цей юніт виділений, тільки іншим кольором».
     * Відступ у пару одиниць робить її кільцем НАВКОЛО фігури — іншим знаком, а
     * не іншим тоном того самого.
     */
    private static final float OWNER_MARK_PAD = 2.5f;

    /** Прозорість позначки. Нижча за обводку виділення: вона стоїть постійно. */
    private static final float OWNER_MARK_ALPHA = 0.75f;

    /**
     * Кільце кольору власника навколо союзного юніта.
     *
     * <p>Колір береться з того самого {@code UIFactory.playerColor}, що й
     * обводка аватарки в HUD. Це не зручність, а умова: гравець зіставляє тон на
     * полі з тоном угорі, і два незалежні джерела кольору рано чи пізно дали б
     * різні відтінки для одного гравця.
     */
    private void drawOwnerMark(Unit u, float alpha) {
        float halfW = u.getHalfWidthPx();
        float halfH = u.getHalfHeightPx();
        float pad   = OWNER_MARK_PAD;
        float x     = u.renderX(alpha) - halfW - pad;
        float y     = u.renderY(alpha) - halfH - pad;

        Color c = UIFactory.playerColor(roster.colorIndex(u.owner));
        shapes.setColor(c.r, c.g, c.b, OWNER_MARK_ALPHA);
        shapes.rect(x, y, halfW * 2f + pad * 2f, halfH * 2f + pad * 2f);
    }

    /**
     * Найкоротший бар, який ще читається.
     *
     * <p>Висота бару йде від хітбокса, а в легкої піхоти той лише 7 одиниць —
     * смужка виходила б утричі нижчою за сусідні й губилась би саме тоді, коли
     * по ній треба щось зрозуміти. Ширина при цьому спільна для всіх, тож бари
     * різних юнітів однаково широкі й порівнюються між собою.
     */
    private static final float BAR_MIN_H = 12f;

    /** Висота бару: по хітбоксу, але не нижче {@link #BAR_MIN_H}. */
    private static float barHeight(Unit u) {
        return Math.max(u.getHalfHeightPx() * 2f, BAR_MIN_H);
    }

    private void drawHpBar(Unit u, float alpha) {
        // Так само по хітбоксу — бар має стояти впритул до фігури, а не до
        // краю картинки.
        float barH = barHeight(u);

        float x = u.renderX(alpha) - u.getHalfWidthPx() - BAR_LEFT - BAR_W + u.hpBarOffsetX();
        float y = u.renderY(alpha) - barH / 2f;

        shapes.setColor(0.3f, 0f, 0f, 0.85f);
        shapes.rect(x, y, BAR_W, barH);

        // hp і maxHp — цілі (Q47.16), тож пряме ділення дало б 1 або 0.
        float ratio = u.hpRatio();
        if (ratio > 0.5f)       shapes.setColor(0.2f, 0.8f, 0.2f, 0.9f);
        else if (ratio > 0.25f) shapes.setColor(0.9f, 0.8f, 0.1f, 0.9f);
        else                    shapes.setColor(0.9f, 0.2f, 0.1f, 0.9f);

        shapes.rect(x, y, BAR_W, barH * ratio);
    }

    /**
     * Бар моралі — ліворуч від бару здоров'я, тобто крайній у колонці.
     *
     * <p>Колір НЕ повторює HP навмисно. Здоров'я — зелене-жовте-червоне, тобто
     * «скільки лишилось»; мораль синя і при просіданні білішає, тобто «наскільки
     * вигорає». Однакова гама означала б два однакові стовпчики поруч, і
     * гравець щоразу згадував би, який з них який.
     */
    private void drawMoraleBar(Unit u, float alpha) {
        float barH = barHeight(u);

        // Рівно на ширину HP-бару плюс проміжок далі від фігури, ніж він.
        // Формула повторює drawHpBar навмисно: два бари мусять стояти на одній
        // висоті й однакової довжини, інакше вони не порівнюються.
        float x = u.renderX(alpha) - u.getHalfWidthPx() - BAR_LEFT - BAR_W + u.hpBarOffsetX()
                - MORALE_BAR_GAP - BAR_W;
        float y = u.renderY(alpha) - barH / 2f;

        shapes.setColor(0f, 0.08f, 0.22f, 0.85f);
        shapes.rect(x, y, BAR_W, barH);

        float ratio = u.moraleRatio();
        // Поки що мораль не міняє нічого, крім себе самої (стат-ефектів немає
        // навмисно), тож уся її розповідь — це колір. Він мусить встигнути
        // попередити ДО зламу, а не показати його постфактум.
        if (ratio > 0.5f)       shapes.setColor(0.35f, 0.6f, 1f, 0.9f);
        else if (ratio > 0.25f) shapes.setColor(0.75f, 0.8f, 1f, 0.9f);
        else                    shapes.setColor(1f, 1f, 1f, 0.95f);

        shapes.rect(x, y, BAR_W, barH * ratio);
    }

    private Texture getTexture(Unit u) {
        String path = u.getTexturePath();
        if (textureCache.containsKey(path)) return textureCache.get(path);

        Texture tex;
        if (Gdx.files.internal(path).exists()) {
            tex = new Texture(Gdx.files.internal(path));
        } else {
            tex = buildFallbackTexture(u);
        }
        textureCache.put(path, tex);
        return tex;
    }

    private Texture buildFallbackTexture(Unit u) {
        int sz = 32;
        Pixmap pm = new Pixmap(sz, sz, Pixmap.Format.RGBA8888);

        if (u.team == Team.PLAYER) pm.setColor(0.2f, 0.4f, 0.9f, 1f);
        else                       pm.setColor(0.85f, 0.2f, 0.2f, 1f);
        pm.fill();

        pm.setColor(1f, 1f, 1f, 0.5f);
        pm.drawRectangle(0, 0, sz, sz);
        pm.setColor(1f, 1f, 1f, 0.6f);
        pm.drawLine(sz / 2, 4, sz / 2, sz - 4);
        pm.drawLine(4, sz / 2, sz - 4, sz / 2);

        Texture tex = new Texture(pm);
        pm.dispose();
        return tex;
    }

    public void dispose() {
        shapes.dispose();
        for (Texture tex : textureCache.values()) tex.dispose();
        textureCache.clear();
    }
}
