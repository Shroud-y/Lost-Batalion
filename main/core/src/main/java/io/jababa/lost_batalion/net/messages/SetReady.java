package io.jababa.lost_batalion.net.messages;

/** Клієнт → хост: натиснуто «Готовий». */
public class SetReady {

    public boolean ready;

    public SetReady() {}

    public SetReady(boolean ready) { this.ready = ready; }
}
