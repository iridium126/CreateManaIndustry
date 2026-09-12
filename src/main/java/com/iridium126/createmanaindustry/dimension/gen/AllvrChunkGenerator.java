package com.iridium126.createmanaindustry.dimension.gen;

import java.util.Optional;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Native noise generator for the central chunk band. Chunk status work uses the vanilla implementation. */
public final class AllvrChunkGenerator extends NoiseBasedChunkGenerator {
    public static final ResourceKey<NoiseGeneratorSettings> SETTINGS = ResourceKey.create(
        Registries.NOISE_SETTINGS, ResourceLocation.fromNamespaceAndPath("createmanaindustry", "allay"));
    public static final MapCodec<AllvrChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        NoiseBasedChunkGenerator.CODEC.codec().optionalFieldOf("terrain").forGetter(g -> g.terrain),
        NoiseBasedChunkGenerator.CODEC.codec().optionalFieldOf("chunks").forGetter(g -> g.chunks),
        RegistryOps.retrieveElement(SETTINGS),
        RegistryOps.retrieveElement(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
    ).apply(instance, AllvrChunkGenerator::new));
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> TYPES =
        DeferredRegister.create(Registries.CHUNK_GENERATOR, "createmanaindustry");
    static { TYPES.register("allay_islands", () -> CODEC); }

    private final Optional<NoiseBasedChunkGenerator> terrain;
    private final Optional<NoiseBasedChunkGenerator> chunks;

    public AllvrChunkGenerator(Optional<NoiseBasedChunkGenerator> terrain,
                               Optional<NoiseBasedChunkGenerator> chunks,
                               Holder<NoiseGeneratorSettings> settings,
                               Holder<MultiNoiseBiomeSourceParameterList> biomes) {
        super(chunks.map(NoiseBasedChunkGenerator::getBiomeSource)
                .orElseGet(() -> MultiNoiseBiomeSource.createFromPreset(biomes)),
            chunks.map(NoiseBasedChunkGenerator::generatorSettings).orElse(settings));
        this.terrain = terrain;
        this.chunks = chunks;
        var noise = generatorSettings().value().noiseSettings();
        if (noise.minY() != AllvrDimensionLimits.VANILLA_MIN_Y
            || noise.height() != AllvrDimensionLimits.VANILLA_MAX_Y - AllvrDimensionLimits.VANILLA_MIN_Y) {
            throw new IllegalArgumentException("Allay chunks noise settings must span [-128, 384)");
        }
    }

    public Optional<NoiseBasedChunkGenerator> terrain() { return terrain; }
    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }
    public static void register(IEventBus bus) { TYPES.register(bus); }
}
