package com.iridium126.createmanaindustry.dimension.gen;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Strict, named block-state palette shared by terrain features and Markov structures.
 *
 * <p>Each entry accepts either a normal block-state object or a weighted {@code variants}
 * object understood by {@link BlockStateVariants}. Selection is deterministic for a supplied
 * seed, so the same palette can be used by chunk, cube and preview generators.</p>
 */
public final class BlockStatePalette {
    private final Map<String, BlockStateVariants> entries;

    private BlockStatePalette(Map<String, BlockStateVariants> entries) {
        this.entries = Map.copyOf(entries);
    }

    public static BlockStatePalette parse(JsonObject json, Collection<String> requiredKeys,
            String label, BiConsumer<String, BlockState> validator) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(requiredKeys, "requiredKeys");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(validator, "validator");
        var orderedKeys = new ArrayList<>(requiredKeys);
        var uniqueKeys = new LinkedHashSet<>(orderedKeys);
        if (orderedKeys.size() != uniqueKeys.size()) {
            throw new IllegalArgumentException(label + " contains duplicate required keys");
        }
        for (String key : json.keySet()) {
            if (!uniqueKeys.contains(key)) {
                throw new IllegalArgumentException("Unknown " + label + " material: " + key);
            }
        }
        var parsed = new LinkedHashMap<String, BlockStateVariants>();
        for (String key : orderedKeys) {
            if (!json.has(key)) {
                throw new IllegalArgumentException("Missing " + label + " material: " + key);
            }
            parsed.put(key, BlockStateVariants.parse(json.get(key), label + " material: " + key,
                state -> validator.accept(key, state)));
        }
        return new BlockStatePalette(parsed);
    }

    public static BlockStatePalette parse(JsonObject json, Collection<String> requiredKeys,
            BiConsumer<String, BlockState> validator) {
        return parse(json, requiredKeys, "palette", validator);
    }

    public BlockState get(String key) {
        return get(key, 0L);
    }

    public BlockState get(String key, long randomSeed) {
        return Objects.requireNonNull(entries.get(key), key).choose(randomSeed);
    }

    public Set<String> keys() {
        return Set.copyOf(new LinkedHashSet<>(entries.keySet()));
    }
}
