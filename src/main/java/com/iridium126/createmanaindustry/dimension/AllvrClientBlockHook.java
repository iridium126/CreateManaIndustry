package com.iridium126.createmanaindustry.dimension;

import java.util.function.Function;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Common-side bridge for client block reads. Common mixins (the collision
 * iterator) must not reference client classes in their bytecode — a dedicated
 * server applies them but never loads {@code client.*} classes. The client
 * registers its cube-cache resolver during mod construction; the hook is only
 * ever consulted from client-side levels, so on a dedicated server the
 * resolver is unreachable and the null fallback never fires.
 */
public final class AllvrClientBlockHook {

    private static volatile Function<BlockPos, BlockState> resolver;
    private static volatile Function<BlockPos, Boolean> loadedResolver;
    private static volatile net.minecraft.world.level.biome.BiomeManager.NoiseBiomeSource biomeResolver;
    private static volatile java.util.function.BiFunction<LightLayer, BlockPos, Integer> lightResolver;
    private static volatile java.util.function.BiFunction<BlockPos, Integer, Integer> rawLightResolver;

    public static void setBiomeResolver(net.minecraft.world.level.biome.BiomeManager.NoiseBiomeSource resolver) {
        biomeResolver = resolver;
    }

    public static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome(int x, int y, int z) {
        var current = biomeResolver;
        return current == null ? null : current.getNoiseBiome(x, y, z);
    }

    /** Registers the client-side resolver (called once from client mod init). */
    public static void setResolver(Function<BlockPos, BlockState> blockResolver) {
        resolver = blockResolver;
    }

    public static BlockState resolve(BlockPos pos) {
        Function<BlockPos, BlockState> current = resolver;
        return current == null ? Blocks.VOID_AIR.defaultBlockState() : current.apply(pos);
    }

    /** Client-side residency bridge used by common LevelReader methods. */
    public static Boolean isLoaded(BlockPos pos) {
        Function<BlockPos, Boolean> current = loadedResolver;
        return current == null ? null : current.apply(pos);
    }

    public static void setLoadedResolver(Function<BlockPos, Boolean> resolver) {
        loadedResolver = resolver;
    }

    public static void setLightResolver(java.util.function.BiFunction<LightLayer, BlockPos, Integer> resolver,
                                         java.util.function.BiFunction<BlockPos, Integer, Integer> rawResolver) {
        lightResolver = resolver;
        rawLightResolver = rawResolver;
    }

    public static Integer light(LightLayer type, BlockPos pos) {
        var current = lightResolver;
        return current == null ? null : current.apply(type, pos);
    }

    public static Integer rawLight(BlockPos pos, int amount) {
        var current = rawLightResolver;
        return current == null ? null : current.apply(pos, amount);
    }

    private AllvrClientBlockHook() {}
}
