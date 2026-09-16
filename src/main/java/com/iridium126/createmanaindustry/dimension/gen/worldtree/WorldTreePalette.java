package com.iridium126.createmanaindustry.dimension.gen.worldtree;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.iridium126.createmanaindustry.worldgen.markov.EpicRedwoodModel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** Developer-owned classpath resource, loaded once after block registration. */
public final class WorldTreePalette {
    private final BlockState[] states;

    public WorldTreePalette(com.google.gson.JsonObject json) {
        states = new BlockState[EpicRedwoodModel.VALUES.length()];
        for (String key : json.keySet()) {
            if (key.length() != 1 || EpicRedwoodModel.VALUES.indexOf(key) <= 0)
                throw new IllegalArgumentException("Unknown world tree material: " + key);
        }
        for (int i = 1; i < states.length; i++) {
            String key = String.valueOf(EpicRedwoodModel.VALUES.charAt(i));
            if (!json.has(key)) throw new IllegalArgumentException("Missing world tree material: " + key);
            BlockState state = BlockState.CODEC.parse(JsonOps.INSTANCE, json.get(key)).getOrThrow();
            String namespace = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace();
            if ((!namespace.equals("minecraft") && !namespace.equals("create"))
                    || state.isAir() || !state.getFluidState().isEmpty() || state.hasBlockEntity())
                throw new IllegalArgumentException("World tree materials require non-air, fluid-free minecraft/create blocks without block entities: " + key);
            states[i] = state;
        }
    }

    public BlockState state(int material) { return states[material]; }

    static WorldTreePalette bundled() { return Holder.INSTANCE; }
    private static final class Holder {
        static final WorldTreePalette INSTANCE = load();
        private static WorldTreePalette load() {
            String path = "/data/createmanaindustry/markov/epic_redwood_palette.json";
            try (var stream = WorldTreePalette.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalStateException("Missing " + path);
                return new WorldTreePalette(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read world tree palette", e); }
        }
    }
}
