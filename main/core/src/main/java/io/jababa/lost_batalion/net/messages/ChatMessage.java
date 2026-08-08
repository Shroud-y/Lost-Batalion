package io.jababa.lost_batalion.net.messages;

/**
 * Повідомлення чату лоббі. Клієнт → хост, далі хост → усім.
 *
 * <p>Нік їде разом із текстом, хоч його й можна було б узяти зі слота: рядок
 * чату лишається в історії й після того, як гравець вийшов, і тоді слота вже
 * немає. Автора хост підставляє САМ зі свого списку — присланому playerId
 * довіряти не можна, інакше будь-хто пише від чужого імені.
 */
public class ChatMessage {

    public int    playerId;
    public String nick;
    public String text;

    public ChatMessage() {}

    public ChatMessage(String text) { this.text = text; }

    public ChatMessage(int playerId, String nick, String text) {
        this.playerId = playerId;
        this.nick     = nick;
        this.text     = text;
    }
}
