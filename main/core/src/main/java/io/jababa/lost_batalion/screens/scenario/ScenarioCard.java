package io.jababa.lost_batalion.screens.scenario;

public class ScenarioCard {

    public final String id;
    public final String title;
    public final String description;
    public final String texturePath;
    public final String maskPath;        // маска лісу
    public final String terrainMaskPath; // маска топографії (висоти)

    public ScenarioCard(String id, String title, String description,
                        String texturePath, String maskPath, String terrainMaskPath) {
        this.id              = id;
        this.title           = title;
        this.description     = description;
        this.texturePath     = texturePath;
        this.maskPath        = maskPath;
        this.terrainMaskPath = terrainMaskPath;
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
