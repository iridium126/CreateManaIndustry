package com.iridium126.createmanaindustry.dimension.gen.worldtree;

import com.google.gson.JsonParser;
import com.iridium126.createmanaindustry.dimension.gen.BlockStateVariants;
import com.iridium126.createmanaindustry.worldgen.markov.EpicRedwoodModel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.world.level.block.state.BlockState;

/** Developer-owned classpath palette for EpicRedwood material symbols. */
public final class EpicRedwoodPalette {
    private final BlockStateVariants[] states;

    public EpicRedwoodPalette(com.google.gson.JsonObject json) {
        states = new BlockStateVariants[EpicRedwoodModel.VALUES.length()];
        for (String key : json.keySet()) {
            if (key.length() != 1 || EpicRedwoodModel.VALUES.indexOf(key) <= 0)
                throw new IllegalArgumentException("Unknown EpicRedwood material: " + key);
        }
        for (int i = 1; i < states.length; i++) {
            String key = String.valueOf(EpicRedwoodModel.VALUES.charAt(i));
            if (!json.has(key)) throw new IllegalArgumentException("Missing EpicRedwood material: " + key);
            states[i] = BlockStateVariants.parse(json.get(key), "EpicRedwood material: " + key, state -> {
                if (state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity())
                    throw new IllegalArgumentException("EpicRedwood materials require a registered, non-air, fluid-free block without a block entity: " + key);
            });
        }
    }

    public BlockState state(int material) { return state(material, 0L); }

    public BlockState state(int material, long randomSeed) {
        return states[material] == null ? null : states[material].choose(randomSeed);
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
