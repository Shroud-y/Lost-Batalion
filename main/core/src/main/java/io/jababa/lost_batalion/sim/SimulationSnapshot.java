package io.jababa.lost_batalion.sim;

import io.jababa.lost_batalion.net.NetLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Знімок стану симуляції: усе, з чого можна продовжити матч.
 *
 * <h3>Навіщо</h3>
 * Коли хеші розійшлись, домовитись самотужки клієнти вже не можуть — вони
 * рахують різне. Єдиний вихід — узяти чийсь стан за еталон і роздати всім.
 * Еталон — хостовий, бо хтось мусить бути арбітром, а хост і так пропускає
 * через себе весь трафік.
 *
 * <h3>Чому байти, а не граф об'єктів через Kryo</h3>
 * Формат описаний тут, в одному місці, і читається тим самим кодом, що й
 * пишеться. Kryo довелося б пояснювати, як обходити взаємні посилання між
 * юнітами, наказами й групами, і будь-яка зміна цих класів мовчки міняла б
 * формат. Тут посилання перетворюються на id явно.
 *
 * <h3>Що НЕ входить</h3>
 * Місцевість (вона з ассетів і однакова у всіх), характеристики юнітів (сталі,
 * задаються конструктором), виділення і будь-який візуал. Знімок описує те, що
 * могло розійтись, і нічого понад це.
 */
public final class SimulationSnapshot {

    private SimulationSnapshot() {}

    /**
     * Тег формату. Знімок ніколи не їздить між різними версіями протоколу
     * (їх відсіює лоббі), але тег коштує 4 байти і одразу відрізняє
     * пошкоджені дані від чужих.
     */
    private static final int MAGIC = 0x4C42_5303;   // "LBS" + версія 3 (точки, золото, черга військ)

    /** Зняти стан. Викликається лише на хості й лише коли симуляція стоїть. */
    public static byte[] capture(GameSimulation sim) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeInt(sim.getTickNumber());
            out.writeLong(sim.getRandom().getState());
            out.writeLong(sim.getRandom().getCalls());

            sim.getUnitManager().writeSnapshot(out);
            sim.getCombatManager().writeSnapshot(out);
            sim.getCapturePoints().writeSnapshot(out);
            sim.getEconomy().writeSnapshot(out);
            sim.getSpawnQueue().writeSnapshot(out);
            sim.getVictory().writeSnapshot(out);
        } catch (IOException e) {
            // ByteArrayOutputStream не кидає IOException; якщо це сталось —
            // ламати матч мовчки не можна.
            NetLog.error("Не вдалось зняти знімок стану: " + e);
            return null;
        }
        return bytes.toByteArray();
    }

    /**
     * Застосувати стан.
     *
     * <p>Після успішного відновлення хеш симуляції мусить збігтися з тим, що
     * приїхав разом зі знімком — це перевіряє викликач. Тут перевіряється лише
     * цілісність самих байтів.
     *
     * @return true, якщо знімок розпакувався
     */
    public static boolean restore(GameSimulation sim, byte[] data) {
        if (data == null || data.length < 8) {
            NetLog.error("Знімок стану порожній або обрізаний.");
            return false;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) {
                NetLog.error("Знімок стану не впізнано — чужий формат.");
                return false;
            }

            int  tick  = in.readInt();
            long state = in.readLong();
            long calls = in.readLong();

            sim.getUnitManager().readSnapshot(in);
            sim.getCombatManager().readSnapshot(in);
            sim.getCapturePoints().readSnapshot(in);
            sim.getEconomy().readSnapshot(in);
            sim.getSpawnQueue().readSnapshot(in);
            sim.getVictory().readSnapshot(in);

            // RNG і номер тіку ставляться ОСТАННІМИ: якщо читання юнітів
            // впаде на середині, симуляція лишиться хоч і зіпсованою, але з
            // чесним старим тіком, і ресинк можна буде повторити.
            sim.getRandom().restore(state, calls);
            sim.setTickNumber(tick);

            // Видимість не входить у знімок окремо — вона вже прочитана в
            // прапорцях юнітів. Перерахунок тут ЗЛАМАВ би збіг: він рахує
            // видимість «як після тіку tick», а в знімку вона саме така і є.
            return true;
        } catch (IOException | RuntimeException e) {
            NetLog.error("Знімок стану пошкоджений: " + e);
            return false;
        }
    }
}
