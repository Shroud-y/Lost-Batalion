package io.jababa.lost_batalion.net.messages;

/**
 * Хост → усім: пауза знята, симуляція продовжується з {@code resumeTick + 1}.
 *
 * <p>Надсилається після успішного ресинку або після рішення хоста продовжити
 * без відключеного гравця.
 */
public class ResumeMatch {

    public int resumeTick;

    /**
     * Номер покоління, з яким усі продовжують.
     *
     * <p>Їде разом із дозволом на продовження, а не рахується кожним окремо:
     * покоління мусить бути СПІЛЬНИМ числом, інакше накази одного клієнта
     * відкидатимуться другим як чуже відлуння, і матч завмре назавжди.
     */
    public int generation;

    public ResumeMatch() {}

    public ResumeMatch(int resumeTick, int generation) {
        this.resumeTick = resumeTick;
        this.generation = generation;
    }
}
