package com.iridium126.createmanaindustry.dimension.gen;

import java.util.List;
import java.util.Optional;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Empty vanilla column shell, with an optional fully data-driven cube terrain source. */
public final class AllvrChunkGenerator extends FlatLevelSource {
    public static final MapCodec<AllvrChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        NoiseBasedChunkGenerator.CODEC.codec().optionalFieldOf("terrain").forGetter(g -> g.terrain),
        RegistryOps.retrieveElement(Biomes.PLAINS)
    ).apply(instance, AllvrChunkGenerator::new));
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> TYPES =
        DeferredRegister.create(Registries.CHUNK_GENERATOR, "createmanaindustry");
    static { TYPES.register("allay_islands", () -> CODEC); }

    private final Optional<NoiseBasedChunkGenerator> terrain;

    public AllvrChunkGenerator(Optional<NoiseBasedChunkGenerator> terrain, Holder<Biome> plains) {
        super(new FlatLevelGeneratorSettings(Optional.of(HolderSet.direct()), plains, List.of()));
        this.terrain = terrain;
    }

    public Optional<NoiseBasedChunkGenerator> terrain() { return terrain; }
    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }
    public static void register(IEventBus bus) { TYPES.register(bus); }
}
