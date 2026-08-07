package io.jababa.lost_batalion.debug;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.InputListener;
import com.badlogic.gdx.utils.Align;
import io.jababa.lost_batalion.ui.UIFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Налагоджувальна консоль. Відкривається тильдою, живе на власній сцені.
 *
 * <h3>Тільки одиночна гра</h3>
 * Консоль не створюється взагалі, якщо матч мережевий. Майже все, що вона
 * вміє, — це або пряма зміна стану симуляції повз накази, або зміна темпу
 * годинника; у lockstep і те, і те розводить клієнтів на першому ж тіку. Краще
 * не мати кнопки, ніж мати кнопку, яка тихо ламає чужий матч.
 *
 * <h3>Розбір команд</h3>
 * Свідомо примітивний: розбиття по пробілах, перше слово — команда. Ніякого
 * автодоповнення, історії з підказками й лапок. Це інструмент для двох людей,
 * і кожна година, вкладена в його зручність, — це година, не вкладена в гру.
 * Історія введеного все ж є (стрілки вгору/вниз), бо повторний набір
 * {@code aivsai hard hard} втомлює вже на третій раз.
 */
public class DevConsole {

    /** Скільки рядків виводу тримати. */
    private static final int LOG_LINES = 14;

    /** Що консоль уміє робити з матчем. Реалізує екран. */
    public interface Host {
        /** Обидві сторони під керуванням бота. {@code null} — лишити як є. */
        void setAutoPlay(boolean enabled, String selfLevel, String foeLevel);
        /** Рівень бота-супротивника на льоту. */
        void setFoeLevel(String level);
        /** Золото стороні. */
        void setGold(int playerId, int amount);
        /** Замовити юнітів. */
        void spawn(int playerId, String type, int count);
        /** Знищити армію сторони. */
        void killArmy(int playerId);
        /** Оголосити переможця негайно. */
        void forceWin(int playerId);
        /** Тактична пауза — те саме, що пробіл. */
        void setFrozen(boolean frozen);
        /**
         * Звести ВСІ свої гармати в батареї — те саме, що кнопка в панелі,
         * тільки без виділення мишею. Потрібне саме для автознімка: інакше
         * механіку об'єднання неможливо ні побачити, ні прогнати без людини.
         */
        void mergeGuns();
        /** Рядок стану матчу. */
        String describeState();
    }

    private final Table     root;
    private final Label     output;
    private final TextField input;
    private final Host      host;
    private final Stage     stage;

    private final Deque<String> lines   = new ArrayDeque<>();
    private final java.util.List<String> history = new java.util.ArrayList<>();
    private int historyIndex = -1;

    /** Відкладені команди сценарію: час від старту (с) і сам рядок. */
    private final java.util.List<Float>  delayedAt  = new java.util.ArrayList<>();
    private final java.util.List<String> delayedCmd = new java.util.ArrayList<>();
    private float scriptClock;

    private boolean open;
    /** Останнє відоме положення паузи — щоб `pause` без аргументу перемикав. */
    private boolean frozenHint;

    public DevConsole(Stage stage, Host host) {
        this.host  = host;
        this.stage = stage;

        output = new Label("", UIFactory.createHintStyle());
        output.setAlignment(Align.topLeft);
        output.setWrap(true);

        input = new TextField("", UIFactory.createTextFieldStyle());
        input.setMessageText("команда (help — список)");

        Table panel = new Table();
        panel.setBackground(UIFactory.createPanelBackground());
        panel.pad(10f);
        panel.add(output).growX().height(190f).top().left().row();
        panel.add(input).growX().height(30f).padTop(8f).row();

        root = new Table();
        root.setFillParent(true);
        root.top();
        root.add(panel).growX().pad(8f, 8f, 0f, 8f);
        root.setVisible(false);
        stage.addActor(root);

        input.addListener(new InputListener() {
            @Override public boolean keyDown(InputEvent event, int keycode) {
                if (keycode == Input.Keys.ENTER || keycode == Input.Keys.NUMPAD_ENTER) {
                    submit(input.getText());
                    input.setText("");
                    return true;
                }
                if (keycode == Input.Keys.UP)   { recall(+1); return true; }
                if (keycode == Input.Keys.DOWN) { recall(-1); return true; }
                return false;
            }
        });

        print("Консоль. help — список команд.");
    }

    // ── Відкриття ─────────────────────────────────────────────────────────

    public boolean isOpen() { return open; }

    public void toggle(Stage stage) {
        open = !open;
        root.setVisible(open);
        // Фокус клавіатури забирається явно: без цього набране летіло б у гру,
        // і «speed 4» дало б чотири різні гарячі клавіші замість команди.
        stage.setKeyboardFocus(open ? input : null);
        if (open) input.setText("");
    }

    public void close(Stage stage) {
        if (open) toggle(stage);
    }

    // ── Вивід ─────────────────────────────────────────────────────────────

    public void print(String line) {
        if (line == null) return;
        for (String part : line.split("\n")) {
            lines.addLast(part);
            while (lines.size() > LOG_LINES) lines.removeFirst();
        }
        output.setText(String.join("\n", lines));
    }

    private void recall(int direction) {
        if (history.isEmpty()) return;
        historyIndex = Math.max(-1, Math.min(history.size() - 1, historyIndex + direction));
        input.setText(historyIndex < 0 ? "" : history.get(history.size() - 1 - historyIndex));
        input.setCursorPosition(input.getText().length());
    }

    /**
     * Просунути годинник сценарію і виконати те, чому настав час.
     *
     * <p>Час КАДРУ, а не тіку: сценарій — це заміна руці за клавіатурою, і
     * міряти його треба тим самим, чим міряються автознімки. Множник
     * {@code speed} на нього свідомо не діє — інакше «зняти на 12-й секунді»
     * означало б різні моменти в різних прогонах.
     */
    public void updateScript(float delta) {
        if (delayedCmd.isEmpty()) return;
        scriptClock += delta;
        for (int i = delayedCmd.size() - 1; i >= 0; i--) {
            if (scriptClock < delayedAt.get(i)) continue;
            String cmd = delayedCmd.remove(i);
            delayedAt.remove(i);
            submit(cmd);
        }
    }

    // ── Розбір ────────────────────────────────────────────────────────────

    /**
     * Виконати команду ззовні — з {@code -Dlb.devCmd}, розділювач {@code ;}.
     *
     * <p>Потрібне не лише для зручності: без нього консоль неможливо ні зняти
     * на автоскріншот, ні прогнати без людини за клавіатурою, тобто перевірити
     * її можна було б тільки руками. Той самий шлях, що й {@code lb.autoMatch}.
     */
    public void execute(String script) {
        if (script == null) return;
        for (String part : script.split(";")) {
            String cmd = part.trim();
            // «12s:merge» — виконати через 12 секунд від старту матчу. Без
            // відкладених команд сценарієм не перевірити нічого, що вимагає
            // ДВОХ дій у часі: зведення батареї, підхід підкріплення, наказ по
            // юніту, який ще не вийшов із кута. Секунди — ті самі, що в
            // lb.shotAt, тобто знімок і команду можна ставити поруч.
            int colon = cmd.indexOf(':');
            if (colon > 1 && cmd.charAt(colon - 1) == 's') {
                try {
                    float at = Float.parseFloat(cmd.substring(0, colon - 1).trim());
                    delayedAt.add(at);
                    delayedCmd.add(cmd.substring(colon + 1).trim());
                    continue;
                } catch (NumberFormatException ignored) {
                    // не час, а звичайна команда з двокрапкою — виконати одразу
                }
            }
            submit(cmd);
        }
    }

    private void submit(String raw) {
        if (raw == null || raw.trim().isEmpty()) return;
        String line = raw.trim();
        history.add(line);
        historyIndex = -1;
        print("> " + line);

        String[] a = line.split("\\s+");
        try {
            run(a);
        } catch (Exception e) {
            // Консоль не має права впасти разом із грою: помилковий аргумент —
            // звичайна річ, а падіння посеред матчу коштує матчу.
            print("помилка: " + e.getClass().getSimpleName()
                  + (e.getMessage() == null ? "" : " — " + e.getMessage()));
        }
    }

    private void run(String[] a) {
        switch (a[0].toLowerCase()) {
            case "help":
                print("aivsai [рівень] [рівень] — обидві сторони під ботом; off — вимкнути");
                print("reveal on|off      — бачити всіх (тільки рендер, матч не міняється)");
                print("ai on|off          — наміри ботів на карті");
                print("speed <0.25..8>    — темп симуляції");
                print("level <easy|normal|hard> — рівень супротивника");
                print("gold <0|1> <n>     — видати золото");
                print("spawn <inf|linf|art|cav|hcav> <0|1> [скільки]");
                print("merge              — звести всі свої гармати в батареї");
                print("kill <0|1>         — знищити армію сторони");
                print("win <0|1>          — оголосити переможця");
                print("state              — стан матчу");
                print("pause on|off       — тактична пауза (те саме, що пробіл)");
                print("console on|off     — сама панель (для -Dlb.devCmd)");
                break;

            case "aivsai":
                if (a.length > 1 && a[1].equalsIgnoreCase("off")) {
                    host.setAutoPlay(false, null, null);
                    print("автопілот вимкнено");
                } else {
                    String self = a.length > 1 ? a[1] : "normal";
                    String foe  = a.length > 2 ? a[2] : self;
                    host.setAutoPlay(true, self, foe);
                    print("автопілот: ви=" + self + ", суперник=" + foe
                          + "  (reveal on — щоб бачити обох)");
                }
                break;

            case "reveal":
                DevView.revealAll = onOff(a, DevView.revealAll);
                print("reveal " + (DevView.revealAll ? "on" : "off"));
                break;

            case "ai":
                DevView.showAiIntent = onOff(a, DevView.showAiIntent);
                print("наміри " + (DevView.showAiIntent ? "on" : "off"));
                break;

            case "speed": {
                float v = a.length > 1 ? Float.parseFloat(a[1]) : 1f;
                DevView.timeScale = Math.max(0.25f, Math.min(8f, v));
                print("темп ×" + DevView.timeScale);
                break;
            }

            case "level":
                if (a.length < 2) { print("level <easy|normal|hard>"); break; }
                host.setFoeLevel(a[1]);
                print("рівень супротивника: " + a[1]);
                break;

            case "gold":
                if (a.length < 3) { print("gold <0|1> <n>"); break; }
                host.setGold(Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                print("золото видано");
                break;

            case "spawn":
                if (a.length < 3) { print("spawn <inf|linf|art|cav|hcav> <0|1> [скільки]"); break; }
                host.spawn(Integer.parseInt(a[2]), a[1],
                           a.length > 3 ? Integer.parseInt(a[3]) : 1);
                print("замовлено");
                break;

            case "merge":
                host.mergeGuns();
                print("гармати зводяться");
                break;

            case "kill":
                if (a.length < 2) { print("kill <0|1>"); break; }
                host.killArmy(Integer.parseInt(a[1]));
                print("армію знищено");
                break;

            case "win":
                if (a.length < 2) { print("win <0|1>"); break; }
                host.forceWin(Integer.parseInt(a[1]));
                print("переможця оголошено");
                break;

            case "pause": {
                boolean want = onOff(a, frozenHint);
                frozenHint = want;
                host.setFrozen(want);
                print("пауза " + (want ? "on" : "off"));
                break;
            }

            case "console":
                // Потрібне сценарію з lb.devCmd: інакше панель неможливо ні
                // побачити на автознімку, ні лишити відкритою для спостереження.
                if (onOff(a, open) != open) toggle(stage);
                break;

            case "state":
                print(host.describeState());
                break;

            default:
                print("невідома команда: " + a[0]);
        }
    }

    /** {@code on}/{@code off}/нічого — перемкнути. */
    private static boolean onOff(String[] a, boolean current) {
        if (a.length < 2) return !current;
        return a[1].equalsIgnoreCase("on") || a[1].equals("1");
    }
}
