package io.jababa.lost_batalion.net.messages;

/** Клієнт → хост, перше повідомлення після встановлення TCP-з'єднання. */
public class JoinRequest {

    public int    protocolVersion;
    public String nick;

    public JoinRequest() {}

    public JoinRequest(int protocolVersion, String nick) {
        this.protocolVersion = protocolVersion;
        this.nick = nick;
    }
}
