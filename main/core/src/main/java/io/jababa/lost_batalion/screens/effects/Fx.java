package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.MathUtils;

import java.util.Random;

/**
 * Усі процедурні ефекти гри в одному місці: кисті, пул частинок і самі ефекти.
 *
 * <p>Раніше це були два класи — {@code FxTextures} (кисті) і {@code ArtilleryFx}
 * (пул + артилерійські ефекти). Розділення означало, що кожен новий тип ефекту
 * або тягне за собою третій клас, або лізе в чужий артилерійський. Тут точка
 * входу одна: {@code Fx.muzzleBlast(...)}, {@code Fx.impact(...)} — і сюди ж
 * дописуються наступні ефекти.
 *
 * <p>Усе тут ВІЗУАЛЬНЕ: рахується за часом кадру, смикає {@link MathUtils#random}
 * і в стан гри не входить. Симуляція знає лише те, що снаряд вилетів і влучив;
 * як саме це виглядає — справа кадру, а не тіку.
 *
 * <h3>Кисті</h3>
 * Дві текстури на весь бій — м'яка пляма і «клубок» диму, обидві генеруються
 * кодом при першому зверненні. Решта (колір, розмір, рух, згасання) задається
 * на місці: одна кисть із різними параметрами дає і спалах пострілу, і іскру,
 * і хмару диму. Через це не потрібні ані PNG, ані spritesheet-и з покадровою
 * розкладкою. Шум у клубку береться з {@link Random} зі СТАЛИМ зерном:
 * текстура має бути однаковою в кожному запуску.
 *
 * <h3>Блендинг</h3>
 * Кольори кладуться в буфер ПОМНОЖЕНИМИ на альфу (premultiplied). Тоді одна
 * формула {@code (GL_ONE, GL_ONE_MINUS_SRC_ALPHA)} дає і звичайне перекриття
 * (дим, альфа &gt; 0), і чисте додавання (вогонь та іскри, альфа = 0) — тобто
 * світло не затуляє те, що під ним, а дим затуляє. Це той самий простір, у
 * якому працює {@link BloomEffect}, тож частинки можна малювати всередині
 * його буфера без жодних перемикань режимів.
 */
public final class Fx {

    private Fx() {}

    // ══ Кисті ═════════════════════════════════════════════════════════════

    /** Сторона кожної текстури в пікселях. На екрані вони розтягуються. */
    private static final int BRUSH_SIZE = 64;

    private static Texture dotTex;
    private static Texture puffTex;

    /** М'яка кругла пляма: ядро снаряда, іскри, спалах. */
    public static Texture dot() {
        if (dotTex == null) dotTex = buildBrush(Brush.DOT);
        return dotTex;
    }

    /** Нерівний клубок — дим і пил. */
    public static Texture puff() {
        if (puffTex == null) puffTex = buildBrush(Brush.PUFF);
        return puffTex;
    }

    /** Кисть частинки. */
    private enum Brush { DOT, PUFF }

    private static Texture brush(Brush brush) {
        return brush == Brush.PUFF ? puff() : dot();
    }

    private static Texture buildBrush(Brush kind) {
        Pixmap pm = new Pixmap(BRUSH_SIZE, BRUSH_SIZE, Pixmap.Format.RGBA8888);
        pm.setBlending(Pixmap.Blending.None);

        // Клубок диму — це та сама пляма, промодульована кількома зміщеними
        // горбами: рівний градієнт читається як куля, а не як дим.
        int lobes = 14;
        float[] lobeX = new float[lobes], lobeY = new float[lobes],
                lobeR = new float[lobes], lobeW = new float[lobes];
        if (kind == Brush.PUFF) {
            Random rnd = new Random(20260728L);
            for (int i = 0; i < lobes; i++) {
                double a = rnd.nextDouble() * Math.PI * 2;
                double d = rnd.nextDouble() * 0.55;
                lobeX[i] = (float) (Math.cos(a) * d);
                lobeY[i] = (float) (Math.sin(a) * d);
                lobeR[i] = (float) (0.13 + rnd.nextDouble() * 0.24);
                lobeW[i] = (float) (0.45 + rnd.nextDouble() * 0.55);
            }
        }

        float half = BRUSH_SIZE / 2f;
        for (int y = 0; y < BRUSH_SIZE; y++) {
            for (int x = 0; x < BRUSH_SIZE; x++) {
                float nx = (x + 0.5f - half) / half;   // -1..1
                float ny = (y + 0.5f - half) / half;
                float d  = (float) Math.sqrt(nx * nx + ny * ny);

                float a;
                switch (kind) {
                    case PUFF: {
                        // Купа перекритих горбів різного розміру й ваги, збита
                        // радіальною маскою. Жоден горб не домінує, тож форма
                        // не читається — саме цього й треба від хмари диму.
                        float sum = 0f;
                        for (int i = 0; i < lobes; i++) {
                            float lx = (nx - lobeX[i]) / lobeR[i];
                            float ly = (ny - lobeY[i]) / lobeR[i];
                            float ld = lx * lx + ly * ly;
                            if (ld < 1f) {
                                float f = 1f - ld;
                                sum += f * f * lobeW[i];   // гладкий горб, без гострої вершини
                            }
                        }
                        // Маска м'яка (не лінійна): край хмари має розчинятись,
                        // а не обриватись колом.
                        float mask = clamp01(1f - d);
                        mask = mask * mask * (3f - 2f * mask);
                        // Насичення експонентою, а не clamp: обрізання давало
                        // рівне біле плато в середині, і клубок читався як
                        // суцільна фігура з контуром.
                        a = (float) (1.0 - Math.exp(-sum * 1.5)) * mask * 0.95f;
                        break;
                    }
                    default: {
                        float f = clamp01(1f - d);
                        a = f * f;              // квадрат — щоб ядро було щільним
                        break;
                    }
                }

                // Біля самого краю альфа гаситься примусово, щоб межа текстури
                // не проступала навіть при сильному масштабуванні.
                if (d > 0.90f) a *= clamp01((1f - d) / 0.10f);

                // RGB множиться на альфу ще тут — текстура зберігається
                // PREMULTIPLIED. Це не оптимізація, а єдиний робочий варіант:
                // ефекти малюються з {@code GL_ONE}, і при білому RGB колір
                // додавався б з однаковою силою по ВСЬОМУ квадрату — градієнт
                // лишався б тільки в альфі, а на екрані було б видно чіткий
                // світлий квадрат замість хмарки.
                pm.setColor(a, a, a, a);
                pm.drawPixel(x, y);
            }
        }

        Texture tex = new Texture(pm);
        tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pm.dispose();
        return tex;
    }

    // ══ Пул частинок ══════════════════════════════════════════════════════
    //
    // Один спільний пул на весь бій — ефекти лише «підсипають» у нього частинки
    // і забувають про них. Так дим від пострілу спокійно живе довше за сам
    // ефект пострілу, а вибух не мусить тримати власний масив.

    /** Стеля пулу. Вибух коштує ~50 частинок, постріл ~25 — запас на залп. */
    private static final int MAX_PARTICLES = 1024;

    private static final Particle[] pool = new Particle[MAX_PARTICLES];
    private static int count = 0;

    static {
        for (int i = 0; i < MAX_PARTICLES; i++) pool[i] = new Particle();
    }

    private static final class Particle {
        float x, y, vx, vy;
        /** Гальмування середовищем: частка швидкості, що гаситься за секунду. */
        float drag;
        /** Вертикальне прискорення — уламки падають, дим піднімається. */
        float gravity;
        float life, maxLife;
        float size0, size1;
        float r0, g0, b0, a0;
        float r1, g1, b1, a1;
        float rot, rotSpeed;
        /** Витягнутість: клубки диму не мають бути однаковими кружальцями. */
        float aspect;
        Brush brush;
        boolean additive;
    }

    private static Particle next() {
        if (count >= MAX_PARTICLES) return null;   // перевантаження — тихо гасимо
        return pool[count++];
    }

    // ══ Ефекти ════════════════════════════════════════════════════════════

    /**
     * Дульний спалах: постріл із гармати.
     *
     * @param x,y      точка на зрізі ствола
     * @param angleRad куди дивиться ствол
     */
    public static void muzzleBlast(float x, float y, float angleRad) {
        float dx = MathUtils.cos(angleRad), dy = MathUtils.sin(angleRad);

        // Спалах ледь теплий і дуже дрібний: це чорнопорохова гармата, її
        // постріл — це передусім хмара сірого диму, а не вогняна куля.
        Particle f = next();
        if (f != null) {
            init(f, x + dx * 1.5f, y + dy * 1.5f, 0f, 0f, 0.07f, Brush.DOT, true);
            f.size0 = 3.5f;  f.size1 = 7f;
            color(f, 1f, 0.93f, 0.78f, 0.55f,  0.8f, 0.6f, 0.45f, 0f);
        }

        // Кілька іскор — рідкі й короткі, лише щоб постріл не був «ватним».
        int sparks = MathUtils.random(3, 6);
        for (int i = 0; i < sparks; i++) {
            Particle p = next();
            if (p == null) break;
            float a  = angleRad + MathUtils.random(-0.30f, 0.30f);
            float sp = MathUtils.random(35f, 85f);
            init(p, x + dx * 2f, y + dy * 2f,
                 MathUtils.cos(a) * sp, MathUtils.sin(a) * sp,
                 MathUtils.random(0.10f, 0.20f), Brush.DOT, true);
            p.drag = 6f;  p.gravity = -40f;
            p.size0 = MathUtils.random(1f, 1.8f);  p.size1 = 0.3f;
            color(p, 1f, 0.9f, 0.7f, 0.7f,  0.7f, 0.5f, 0.35f, 0f);
        }

        // Основа ефекту — сірий дим. Кожен клубок зі своїм відтінком, розміром
        // і обертанням: однакові клубки читаються як візерунок, а не як дим.
        int puffs = MathUtils.random(8, 12);
        for (int i = 0; i < puffs; i++) {
            Particle p = next();
            if (p == null) break;
            float a  = angleRad + MathUtils.random(-0.7f, 0.7f);
            float sp = MathUtils.random(8f, 38f);
            init(p, x + dx * MathUtils.random(-1f, 3f) + MathUtils.random(-1.5f, 1.5f),
                    y + dy * MathUtils.random(-1f, 3f) + MathUtils.random(-1.5f, 1.5f),
                 MathUtils.cos(a) * sp, MathUtils.sin(a) * sp,
                 MathUtils.random(0.5f, 1.1f), Brush.PUFF, false);
            p.drag = 2.6f;  p.gravity = 5f;
            p.size0 = MathUtils.random(2f, 4f);  p.size1 = MathUtils.random(6f, 12f);
            p.rotSpeed = MathUtils.random(-110f, 110f);
            smoke(p, MathUtils.random(0.60f, 0.80f), MathUtils.random(0.30f, 0.45f));
        }
    }

    /** Димний слід снаряда в польоті. Викликається кадрами, тому дуже дешевий. */
    public static void shellTrail(float x, float y) {
        Particle p = next();
        if (p == null) return;
        init(p, x + MathUtils.random(-0.8f, 0.8f), y + MathUtils.random(-0.8f, 0.8f),
             MathUtils.random(-6f, 6f), MathUtils.random(-3f, 9f),
             MathUtils.random(0.22f, 0.45f), Brush.PUFF, false);
        p.drag = 3f;
        p.size0 = MathUtils.random(1.2f, 2.2f);  p.size1 = MathUtils.random(3.5f, 6f);
        p.rotSpeed = MathUtils.random(-130f, 130f);
        smoke(p, MathUtils.random(0.62f, 0.78f), 0.24f);
    }

    /**
     * Приліт: спалах, вогняна куля, уламки, пил і дим.
     *
     * @param radius радіус ураження у світових одиницях — від нього масштаб
     */
    public static void impact(float x, float y, float radius) {
        // Ядро вибуху: коротка тепла пляма, дрібна. Далі все сіре.
        int core = MathUtils.random(2, 4);
        for (int i = 0; i < core; i++) {
            Particle p = next();
            if (p == null) break;
            float a  = MathUtils.random(0f, MathUtils.PI2);
            float sp = MathUtils.random(6f, 26f);
            init(p, x + MathUtils.cos(a) * radius * 0.05f,
                    y + MathUtils.sin(a) * radius * 0.05f,
                 MathUtils.cos(a) * sp, MathUtils.sin(a) * sp,
                 MathUtils.random(0.10f, 0.18f), Brush.DOT, true);
            p.drag = 5f;
            p.size0 = radius * MathUtils.random(0.07f, 0.12f);
            p.size1 = radius * MathUtils.random(0.14f, 0.22f);
            color(p, 1f, 0.90f, 0.72f, 0.7f,  0.55f, 0.42f, 0.32f, 0f);
        }

        // Уламки: дрібні, темні, летять на всі боки й падають.
        int debris = MathUtils.random(10, 16);
        for (int i = 0; i < debris; i++) {
            Particle p = next();
            if (p == null) break;
            float a  = MathUtils.random(0f, MathUtils.PI2);
            float sp = MathUtils.random(40f, 140f);
            init(p, x, y, MathUtils.cos(a) * sp, MathUtils.sin(a) * sp,
                 MathUtils.random(0.20f, 0.45f), Brush.DOT, false);
            p.drag = 3f;  p.gravity = -170f;
            p.size0 = MathUtils.random(0.9f, 1.8f);  p.size1 = 0.3f;
            color(p, 0.30f, 0.28f, 0.26f, 0.9f,  0.18f, 0.17f, 0.16f, 0f);
        }

        // Земля з-під вибуху — темніша за дим, летить низько.
        int dirt = MathUtils.random(5, 9);
        for (int i = 0; i < dirt; i++) {
            Particle p = next();
            if (p == null) break;
            float a  = MathUtils.random(0f, MathUtils.PI2);
            float sp = MathUtils.random(20f, 70f);
            init(p, x + MathUtils.random(-2f, 2f), y + MathUtils.random(-2f, 2f),
                 MathUtils.cos(a) * sp, MathUtils.sin(a) * sp,
                 MathUtils.random(0.25f, 0.55f), Brush.PUFF, false);
            p.drag = 3.2f;  p.gravity = -110f;
            p.size0 = MathUtils.random(1.5f, 3f);  p.size1 = MathUtils.random(3.5f, 6.5f);
            p.rotSpeed = MathUtils.random(-160f, 160f);
            color(p, 0.33f, 0.30f, 0.26f, 0.8f,  0.20f, 0.19f, 0.17f, 0f);
        }

        // Дим — головне, що лишається після прильоту: сірий, живе найдовше,
        // повільно піднімається. Розкид параметрів широкий навмисно, інакше
        // клубки лягають рівним колом.
        int smoke = MathUtils.random(9, 14);
        for (int i = 0; i < smoke; i++) {
            Particle p = next();
            if (p == null) break;
            float a  = MathUtils.random(0f, MathUtils.PI2);
            float sp = MathUtils.random(5f, 32f);
            init(p, x + MathUtils.cos(a) * radius * MathUtils.random(0.02f, 0.10f),
                    y + MathUtils.sin(a) * radius * MathUtils.random(0.02f, 0.10f),
                 MathUtils.cos(a) * sp, MathUtils.sin(a) * sp + 8f,
                 MathUtils.random(0.7f, 1.5f), Brush.PUFF, false);
            p.drag = 2f;  p.gravity = 9f;
            p.size0 = radius * MathUtils.random(0.06f, 0.11f);
            p.size1 = radius * MathUtils.random(0.20f, 0.38f);
            p.rotSpeed = MathUtils.random(-90f, 90f);
            smoke(p, MathUtils.random(0.52f, 0.72f), MathUtils.random(0.35f, 0.55f));
        }
    }

    // ══ Життєвий цикл ═════════════════════════════════════════════════════

    public static void update(float delta) {
        for (int i = count - 1; i >= 0; i--) {
            Particle p = pool[i];
            p.life -= delta;
            if (p.life <= 0f) {
                // Дірку затуляє остання жива частинка — порядок у пулі не
                // має значення, а зсув усього хвоста мав би.
                Particle last = pool[count - 1];
                pool[count - 1] = p;
                pool[i] = last;
                count--;
                continue;
            }
            p.vy += p.gravity * delta;
            float k = 1f - p.drag * delta;
            if (k < 0f) k = 0f;
            p.vx *= k;
            p.vy *= k;
            p.x  += p.vx * delta;
            p.y  += p.vy * delta;
            p.rot += p.rotSpeed * delta;
        }
    }

    /**
     * Намалювати всі частинки. {@code batch} має бути закритий: метод сам
     * відкриває його зі своїм режимом змішування і повертає стандартний.
     */
    public static void draw(SpriteBatch batch) {
        if (count == 0) return;

        batch.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA,
                                       GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.begin();
        for (int i = 0; i < count; i++) draw(batch, pool[i]);
        batch.end();
        batch.setColor(1f, 1f, 1f, 1f);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void draw(SpriteBatch batch, Particle p) {
        float t = 1f - p.life / p.maxLife;
        float size = lerp(p.size0, p.size1, t);
        float a = lerp(p.a0, p.a1, t);
        if (a <= 0.001f && !p.additive) return;

        float r = lerp(p.r0, p.r1, t) * a;
        float g = lerp(p.g0, p.g1, t) * a;
        float b = lerp(p.b0, p.b1, t) * a;

        // Additive: альфа в буфер не пишеться взагалі — світло нічого не
        // затуляє, лише додається. Дим навпаки лишає свою альфу.
        batch.setColor(r, g, b, p.additive ? 0f : a);

        Texture tex = brush(p.brush);
        float w = size * p.aspect, h = size / p.aspect;
        batch.draw(tex, p.x - w / 2f, p.y - h / 2f, w / 2f, h / 2f, w, h,
                   1f, 1f, p.rot, 0, 0, tex.getWidth(), tex.getHeight(), false, false);
    }

    /** Скинути всі частинки — на паузу/вихід з бою. */
    public static void clear() { count = 0; }

    /** Погасити частинки і звільнити кисті. */
    public static void dispose() {
        clear();
        if (dotTex  != null) { dotTex.dispose();  dotTex  = null; }
        if (puffTex != null) { puffTex.dispose(); puffTex = null; }
    }

    // ══ Дрібниці ══════════════════════════════════════════════════════════

    private static void init(Particle p, float x, float y, float vx, float vy,
                             float life, Brush brush, boolean additive) {
        p.x = x;  p.y = y;  p.vx = vx;  p.vy = vy;
        p.life = life;  p.maxLife = life;
        p.brush = brush;  p.additive = additive;
        p.drag = 0f;  p.gravity = 0f;
        p.rot = MathUtils.random(0f, 360f);  p.rotSpeed = 0f;
        p.size0 = 1f;  p.size1 = 1f;
        p.aspect = brush == Brush.PUFF ? MathUtils.random(0.75f, 1.32f) : 1f;
    }

    /**
     * Сірий дим: світлішає у щільній частині, темніє й розчиняється під кінець.
     * Відтінок трохи «теплий» (синього менше) — чистий нейтральний сірий на
     * зеленій карті читається як брудна пляма.
     *
     * @param tone  яскравість свіжого клубка
     * @param alpha початкова щільність
     */
    private static void smoke(Particle p, float tone, float alpha) {
        color(p, tone, tone * 0.98f, tone * 0.95f, alpha,
                 tone * 0.42f, tone * 0.42f, tone * 0.42f, 0f);
    }

    private static void color(Particle p,
                              float r0, float g0, float b0, float a0,
                              float r1, float g1, float b1, float a1) {
        p.r0 = r0; p.g0 = g0; p.b0 = b0; p.a0 = a0;
        p.r1 = r1; p.g1 = g1; p.b1 = b1; p.a1 = a1;
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
