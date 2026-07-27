package io.jababa.lost_batalion.screens.effects;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.Viewport;

/**
 * Пост-обробка «світіння» для дрібної яскравої графіки.
 *
 * <p>Усе, що намальовано між {@link #begin(Viewport)} і {@link #end()}, лягає в
 * окремий буфер, звідти розмивається гаусом у два проходи (горизонталь +
 * вертикаль) на вдвічі меншій текстурі й повертається на екран двома шарами:
 * різкий оригінал і розмитий ореол поверх нього.
 *
 * <p>Буфер живе в <b>premultiplied alpha</b>: той, хто малює всередині, має
 * множити RGB на альфу сам. Це дає одну формулу змішування
 * {@code (GL_ONE, GL_ONE_MINUS_SRC_ALPHA)} і для звичайного перекриття
 * (альфа = 1), і для чистого додавання (альфа = 0), і для всього між ними —
 * тобто блендинг підлаштовується під колір, а не задається окремим режимом.
 * Розмиття теж коректне лише в premultiplied-просторі.
 *
 * <p>Якщо шейдер не зібрався (старий драйвер), ефект тихо вимикається:
 * {@code begin}/{@code end} стають порожніми, і графіка малюється просто на
 * екран без світіння.
 */
public class BloomEffect implements Disposable {

    /** У скільки разів менша текстура розмиття. Дрібніша — ширший і дешевший ореол. */
    private static final int DOWNSCALE = 2;
    /** Скільки разів прогнати пару проходів розмиття. */
    private static final int PASSES = 2;
    /** Яскравість ореолу поверх різкого шару. */
    private static final float INTENSITY = 1.35f;
    /** Радіус розмиття в текселях меншої текстури. */
    private static final float RADIUS = 1.6f;

    private static final String VERT =
        "attribute vec4 a_position;\n"
      + "attribute vec4 a_color;\n"
      + "attribute vec2 a_texCoord0;\n"
      + "uniform mat4 u_projTrans;\n"
      + "varying vec4 v_color;\n"
      + "varying vec2 v_texCoords;\n"
      + "void main() {\n"
      + "    v_color = a_color;\n"
      + "    v_texCoords = a_texCoord0;\n"
      + "    gl_Position = u_projTrans * a_position;\n"
      + "}\n";

    /** Дев'ятитактовий гаус одним напрямком; напрямок задає u_dir. */
    private static final String FRAG =
        "#ifdef GL_ES\n"
      + "precision mediump float;\n"
      + "#endif\n"
      + "varying vec4 v_color;\n"
      + "varying vec2 v_texCoords;\n"
      + "uniform sampler2D u_texture;\n"
      + "uniform vec2 u_dir;\n"
      + "void main() {\n"
      + "    vec4 sum = texture2D(u_texture, v_texCoords) * 0.227027;\n"
      + "    vec2 o1 = u_dir * 1.3846154;\n"
      + "    vec2 o2 = u_dir * 3.2307692;\n"
      + "    sum += (texture2D(u_texture, v_texCoords + o1)\n"
      + "          + texture2D(u_texture, v_texCoords - o1)) * 0.3162162;\n"
      + "    sum += (texture2D(u_texture, v_texCoords + o2)\n"
      + "          + texture2D(u_texture, v_texCoords - o2)) * 0.0702703;\n"
      + "    gl_FragColor = v_color * sum;\n"
      + "}\n";

    private FrameBuffer scene, ping, pong;
    private final SpriteBatch batch = new SpriteBatch();
    private final Matrix4 quadProj = new Matrix4();
    private ShaderProgram blur;

    private int width, height;
    private boolean supported = true;
    private boolean capturing;
    private Viewport viewport;

    public BloomEffect(int width, int height) {
        ShaderProgram.pedantic = false;
        blur = new ShaderProgram(VERT, FRAG);
        if (!blur.isCompiled()) {
            Gdx.app.error("BLOOM", "шейдер розмиття не зібрався: " + blur.getLog());
            blur.dispose();
            blur = null;
            supported = false;
            return;
        }
        resize(width, height);
    }

    public boolean isSupported() { return supported; }

    /** Перестворює буфери під новий розмір вікна. */
    public void resize(int width, int height) {
        if (!supported || width <= 0 || height <= 0) return;
        if (this.width == width && this.height == height && scene != null) return;

        this.width  = width;
        this.height = height;
        disposeBuffers();

        int bw = Math.max(1, width  / DOWNSCALE);
        int bh = Math.max(1, height / DOWNSCALE);
        try {
            scene = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false);
            ping  = new FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false);
            pong  = new FrameBuffer(Pixmap.Format.RGBA8888, bw, bh, false);
        } catch (Exception e) {
            Gdx.app.error("BLOOM", "не вдалось створити буфер: " + e.getMessage());
            disposeBuffers();
            supported = false;
            return;
        }
        scene.getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        ping .getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        pong .getColorBufferTexture().setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
        quadProj.setToOrtho2D(0, 0, width, height);
    }

    /**
     * Почати збір. Далі малюють як завжди — тільки в буфер.
     *
     * @param gameViewport ігровий в'юпорт; {@code FrameBuffer.begin()} збиває
     *                     glViewport під розмір буфера, і його треба повернути,
     *                     інакше світ поїде відносно решти кадру
     */
    public void begin(Viewport gameViewport) {
        if (!supported || scene == null || capturing) return;
        this.viewport = gameViewport;
        capturing = true;

        scene.begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        if (viewport != null) viewport.apply();
    }

    /** Завершити збір і вивалити різкий шар + ореол на екран. */
    public void end() {
        if (!capturing) return;
        capturing = false;
        scene.end();

        blurInto();

        Gdx.gl.glEnable(GL20.GL_BLEND);
        batch.setProjectionMatrix(quadProj);
        batch.setShader(null);

        // Різкий шар: premultiplied-перекриття. Пікселі з нульовою альфою і
        // ненульовим кольором тут поводяться як чисте додавання.
        batch.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA,
                                       GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        batch.begin();
        batch.setColor(1f, 1f, 1f, 1f);
        drawFlipped(scene, width, height);
        batch.end();

        // Ореол: тільки додавання — світло не має нічого затуляти.
        batch.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ONE, GL20.GL_ONE, GL20.GL_ONE);
        batch.begin();
        batch.setColor(INTENSITY, INTENSITY, INTENSITY, INTENSITY);
        drawFlipped(ping, width, height);
        batch.end();

        batch.setColor(1f, 1f, 1f, 1f);
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        if (viewport != null) viewport.apply();
    }

    /** Гаус у два напрямки, PASSES разів; результат лишається в {@code ping}. */
    private void blurInto() {
        int bw = ping.getWidth(), bh = ping.getHeight();
        Matrix4 blurProj = new Matrix4().setToOrtho2D(0, 0, bw, bh);

        batch.setProjectionMatrix(blurProj);
        batch.setColor(1f, 1f, 1f, 1f);
        batch.setBlendFunctionSeparate(GL20.GL_ONE, GL20.GL_ZERO, GL20.GL_ONE, GL20.GL_ZERO);

        // Перший прохід зменшує сцену в ping; далі ping ⇄ pong.
        FrameBuffer src = scene;
        for (int i = 0; i < PASSES; i++) {
            renderBlur(src, pong, blurProj, RADIUS / bw, 0f, bw, bh);   // горизонталь
            renderBlur(pong, ping, blurProj, 0f, RADIUS / bh, bw, bh);  // вертикаль
            src = ping;
        }
        batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void renderBlur(FrameBuffer from, FrameBuffer to, Matrix4 proj,
                            float dirX, float dirY, int bw, int bh) {
        to.begin();
        Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.setShader(blur);
        batch.setProjectionMatrix(proj);
        batch.begin();
        blur.setUniformf("u_dir", dirX, dirY);
        drawFlipped(from, bw, bh);
        batch.end();
        batch.setShader(null);
        to.end();
    }

    /** Текстура буфера йде знизу вгору — при малюванні її треба перевернути. */
    private void drawFlipped(FrameBuffer fbo, float w, float h) {
        Texture tex = fbo.getColorBufferTexture();
        batch.draw(tex, 0f, 0f, w, h, 0, 0, tex.getWidth(), tex.getHeight(), false, true);
    }

    private void disposeBuffers() {
        if (scene != null) { scene.dispose(); scene = null; }
        if (ping  != null) { ping.dispose();  ping  = null; }
        if (pong  != null) { pong.dispose();  pong  = null; }
    }

    @Override
    public void dispose() {
        disposeBuffers();
        batch.dispose();
        if (blur != null) blur.dispose();
    }
}
