package com.iridium126.createmanaindustry.worldgen.markov;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/** Developer-facing datapack configuration. B/empty voxels never replace world blocks. */
public record MarkovTreeConfiguration(ResourceLocation model, int width, int depth, int height,
                                      int maxSteps, Map<String, BlockState> palette) implements FeatureConfiguration {
    private static final Codec<String> SYMBOL = Codec.STRING.validate(s -> s.length() == 1
            ? DataResult.success(s) : DataResult.error(() -> "Palette keys must be single MarkovJunior symbols"));
    public static final Codec<MarkovTreeConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("model").forGetter(MarkovTreeConfiguration::model),
            Codec.intRange(1, 31).optionalFieldOf("width", 19).forGetter(MarkovTreeConfiguration::width),
            Codec.intRange(1, 31).optionalFieldOf("depth", 19).forGetter(MarkovTreeConfiguration::depth),
            Codec.intRange(1, 64).optionalFieldOf("height", 18).forGetter(MarkovTreeConfiguration::height),
            Codec.intRange(1, 10000).optionalFieldOf("max_steps", 1000).forGetter(MarkovTreeConfiguration::maxSteps),
            Codec.unboundedMap(SYMBOL, BlockState.CODEC).fieldOf("palette").forGetter(MarkovTreeConfiguration::palette)
    ).apply(instance, MarkovTreeConfiguration::new));

    private record ModelKey(ResourceLocation id, int width, int depth, int height) {}
    private static final Map<ModelKey, MarkovModel> MODELS = new ConcurrentHashMap<>();

    public MarkovTreeConfiguration {
        palette = Map.copyOf(palette);
        if (width < 1 || width > 31 || depth < 1 || depth > 31 || height < 1 || height > 64
                || maxSteps < 1 || maxSteps > 10000) throw new IllegalArgumentException("Invalid tree grid/budget");
        if (palette.isEmpty() || palette.keySet().stream().anyMatch(key -> key.length() != 1))
            throw new IllegalArgumentException("Expected a nonempty symbol palette");
    }

    /** XML is a bundled developer asset, compiled once; palette remains ordinary datapack data. */
    public MarkovModel compiledModel() {
        return MODELS.computeIfAbsent(new ModelKey(model, width, depth, height), key -> {
            String path = "/data/" + model.getNamespace() + "/markov/" + model.getPath() + ".xml";
            try (var stream = MarkovTreeConfiguration.class.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalArgumentException("Missing bundled model: " + path);
                return MarkovModel.load(stream, width, depth, height);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read model " + model, e);
            }
        });
    }
}
