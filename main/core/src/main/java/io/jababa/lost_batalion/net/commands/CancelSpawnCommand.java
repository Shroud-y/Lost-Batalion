package io.jababa.lost_batalion.net.commands;

/**
 * Зняти замовлення війська, поки воно ще не прибуло. Золото повертається
 * повністю: вікно скасування існує саме для того, щоб виправити промах кліком.
 *
 * <p>Замовлення, якого вже немає (вийшов час, або скасування продублювалось за
 * ті два тіки, що команда їхала), просто нічого не робить — так само, як наказ
 * загиблому юніту.
 */
public class CancelSpawnCommand extends GameCommand {

    /** Номер замовлення з {@code SpawnQueue}. */
    public int spawnId;

    public CancelSpawnCommand() {}

    public CancelSpawnCommand(int playerId, int spawnId) {
        super(playerId);
        this.spawnId = spawnId;
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.cancelSpawn(playerId, spawnId);
    }
}
