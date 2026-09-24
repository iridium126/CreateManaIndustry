package com.iridium126.createmanaindustry.compat.ysm.model;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YsmTextureSelectionTest {
    @Test
    void prefersConfiguredDefaultUvAndFallsBackToFirstExistingUv() {
        byte[] skin = {1, 2}, preferred = {3, 4}, fallback = {5, 6};
        var archive = new YsmResourceArchive(Map.of(
                "ysm.json", ("{\"files\":{\"player\":{\"model\":\"models/player.json\",\"texture\":["
                        + "{\"name\":\"skin\",\"uv\":\"textures/skin.png\"},"
                        + "{\"name\":\"default\",\"uv\":\"textures/default.png\"}] }},"
                        + "\"properties\":{\"default_texture\":\"default\"}}")
                        .getBytes(StandardCharsets.UTF_8),
                "textures/skin.png", skin,
                "textures/default.png", preferred,
                "textures/fallback.png", fallback));
        assertArrayEquals(preferred, archive.textureForPart("models/player.json"));

        var missingPreferred = new YsmResourceArchive(Map.of(
                "ysm.json", ("{\"files\":{\"player\":{\"model\":\"models/player.json\",\"texture\":["
                        + "{\"name\":\"missing\",\"uv\":\"textures/missing.png\"},"
                        + "{\"name\":\"skin\",\"uv\":\"textures/skin.png\"}] }},"
                        + "\"properties\":{\"default_texture\":\"missing\"}}")
                        .getBytes(StandardCharsets.UTF_8),
                "textures/skin.png", skin,
                "textures/fallback.png", fallback));
        assertArrayEquals(skin, missingPreferred.textureForPart("models/player.json"));
    }
}
