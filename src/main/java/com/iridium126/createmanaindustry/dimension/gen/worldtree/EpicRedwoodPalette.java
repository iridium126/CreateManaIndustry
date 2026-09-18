package com.iridium126.createmanaindustry.dimension.gen.worldtree;

import com.google.gson.JsonParser;
import com.iridium126.createmanaindustry.dimension.gen.BlockStatePalette;
import com.iridium126.createmanaindustry.dimension.gen.markov.EpicRedwoodModel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;
import net.minecraft.world.level.block.state.BlockState;

/** Developer-owned classpath palette for EpicRedwood material symbols. */
public final class EpicRedwoodPalette {
    private static final java.util.List<String> KEYS = IntStream.range(1, EpicRedwoodModel.VALUES.length())
        .mapToObj(i -> String.valueOf(EpicRedwoodModel.VALUES.charAt(i))).toList();
    private final BlockStatePalette palette;

    public EpicRedwoodPalette(com.google.gson.JsonObject json) {
        palette = BlockStatePalette.parse(json, KEYS, "EpicRedwood",
            (key, state) -> {
                if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity())
                    throw new IllegalArgumentException("EpicRedwood materials require a registered, non-air, fluid-free block without a block entity: " + key);
            });
    }

    public BlockState state(int material) { return state(material, 0L); }

    public BlockState state(int material, long randomSeed) {
        return material == 0 ? null : palette.get(String.valueOf(EpicRedwoodModel.VALUES.charAt(material)), randomSeed);
    }

    static EpicRedwoodPalette bundled() { return Holder.INSTANCE; }

    private static final class Holder {
        static final EpicRedwoodPalette INSTANCE = load();

        private static EpicRedwoodPalette load() {
            String path = "/data/createmanaindustry/markov/epic_redwood_palette.json";
            try (var stream = EpicRedwoodPalette.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing " + path);
                return new EpicRedwoodPalette(JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot read EpicRedwood palette", e);
            }
        }
    }
}
