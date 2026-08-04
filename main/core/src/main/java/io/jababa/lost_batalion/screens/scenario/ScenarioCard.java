package io.jababa.lost_batalion.screens.scenario;

import io.jababa.lost_batalion.capture.CaptureZone;

public class ScenarioCard {

    public final String id;
    public final String title;
    public final String description;
    public final String texturePath;
    public final String maskPath;        // маска лісу
    public final String terrainMaskPath; // маска топографії (висоти)

    /**
     * Зони захоплення карти.
     *
     * <p>Належать сценарію, а не масці: межу захоплення малює автор карти, і
     * вона не зобов'язана збігатися з плямою хат. Порядок задає порядок
     * підписів у HUD, тож переставляти елементи місцями означає переставити
     * літери на екрані.
     *
     * <p>Ніколи не {@code null} — порожній масив означає карту без точок.
     */
    public final CaptureZone[] captureZones;

    public ScenarioCard(String id, String title, String description,
                        String texturePath, String maskPath, String terrainMaskPath,
                        CaptureZone... captureZones) {
        this.id              = id;
        this.title           = title;
        this.description     = description;
        this.texturePath     = texturePath;
        this.maskPath        = maskPath;
        this.terrainMaskPath = terrainMaskPath;
        this.captureZones    = captureZones != null ? captureZones : new CaptureZone[0];
    }

    // Конструктор без террейн-маски
    public ScenarioCard(String id, String title, String description,
                        String texturePath, String maskPath) {
        this(id, title, description, texturePath, maskPath, null);
    }

    // Конструктор без будь-яких масок
    public ScenarioCard(String id, String title, String description, String texturePath) {
        this(id, title, description, texturePath, null, null);
    }
}
