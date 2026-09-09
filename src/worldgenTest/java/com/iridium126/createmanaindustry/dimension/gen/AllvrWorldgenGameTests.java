package com.iridium126.createmanaindustry.dimension.gen;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionCodec;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Development-only integration checks; enabled by scripts/allvr-worldgen-test.init.gradle. */
@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class AllvrWorldgenGameTests {
    @GameTest(template = "worldgen_test", timeoutTicks = 1200)
    public static void datapackTerrainAndHighCubes(GameTestHelper helper) {
        try {
            run(helper);
        } catch (Throwable failure) {
            failure.printStackTrace();
            throw failure;
        }
    }

    private static void run(GameTestHelper helper) {
        var palette = new net.minecraft.world.level.chunk.PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
            Blocks.AIR.defaultBlockState(), net.minecraft.world.level.chunk.PalettedContainer.Strategy.SECTION_STATES);
        var materials = new java.util.ArrayList<net.minecraft.world.level.block.state.BlockState>();
        for (var material : Block.BLOCK_STATE_REGISTRY) {
            if (!material.isAir()) materials.add(material);
            if (materials.size() == 16) break;
        }
        for (int i = 0; i < 15; i++) palette.set(i, 0, 0, materials.get(i));
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var copy = AllvrTerrainSource.mutableCopy(palette, buffer);
            copy.set(15, 0, 0, materials.get(15));
            helper.assertTrue(palette.get(15, 0, 0).isAir(), "Growing a feature palette mutated the immutable source");
            helper.assertTrue(copy.get(15, 0, 0) == materials.get(15), "17th palette entry was lost");
        } finally { buffer.release(); }
        var allay = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        helper.assertTrue(allay != null, "Test preset must include Allay Dimension");
        var source = new AllvrTerrainSource(allay);
        boolean terralith = allay.registryAccess().registryOrThrow(Registries.BIOME).keySet().stream()
            .anyMatch(key -> key.getNamespace().equals("terralith"));
        helper.assertTrue(!Boolean.getBoolean("allvr.testTerralith") || terralith, "Terralith datapack was not loaded");
        helper.assertTrue(!terralith || source.biomeSource().possibleBiomes().stream()
            .anyMatch(biome -> biome.unwrapKey().orElseThrow().location().getNamespace().equals("terralith")),
            "Terralith's actual biome source must be used, not the vanilla preset");
        long start = System.nanoTime();
        var first = source.column(32768, -16384);
        long fingerprint = fingerprint(first);
        source.column(32769, -16384);
        var otherOrder = new AllvrTerrainSource(allay);
        otherOrder.column(32769, -16384);
        helper.assertTrue(fingerprint == fingerprint(otherOrder.column(32768, -16384)), "Decoration depends on request order");
        helper.assertTrue(helper.getLevel().getChunkSource().getChunk(32768, -16384, ChunkStatus.FULL, false) == null,
            "Template generation loaded a real Overworld chunk");

        var settings = source.settings;
        var layout = new AllvrIslandLayout(allay.getSeed(), settings.noiseSettings().minY(), settings.noiseSettings().height(), settings.seaLevel());
        var island = layout.islandAt(0, 40001, 0);
        var generator = new AllvrIslandFieldGenerator(allay);
        var cube = new AllvrCube(AllvrCubePos.of((int) island.cx() >> 5, (island.cy() - 48) >> 5, (int) island.cz() >> 5),
            allay.registryAccess().registryOrThrow(Registries.BIOME));
        generator.generate(cube);
        int solid = 0;
        for (var section : cube.getSections()) if (!section.hasOnlyAir()) solid++;
        helper.assertTrue(solid > 0, "High island cube is entirely empty");
        int qx = cube.getPos().minBlockX() >> 2, qy = cube.getPos().minBlockY() >> 2, qz = cube.getPos().minBlockZ() >> 2;
        helper.assertTrue(cube.getNoiseBiome(qx, qy, qz).equals(generator.biome(qx, qy, qz)), "Cube biome palette disagrees with generator");
        int[] indices = new int[AllvrLodSectionData.CELLS];
        int[] biomeIds = new int[AllvrLodSectionData.CELLS];
        var registry = allay.registryAccess().registryOrThrow(Registries.BIOME);
        int biomeCount = registry.size();
        for (int i = 0; i < indices.length; i++) { indices[i] = i % 3; biomeIds[i] = i % biomeCount; }
        var packetData = new AllvrLodSectionData(0, cube.getPos().asLong(), 1,
            new net.minecraft.world.level.block.state.BlockState[]{Blocks.AIR.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.GRASS_BLOCK.defaultBlockState()},
            indices, new byte[indices.length], biomeIds);
        byte[] packet = AllvrLodSectionCodec.encode(packetData);
        var decoded = AllvrLodSectionCodec.decode(0, cube.getPos().asLong(), 1, packet);
        helper.assertTrue(java.util.Arrays.equals(biomeIds, decoded.biomeIds()), "LOD biome data lost during round trip");
        helper.assertTrue(java.util.Arrays.equals(indices, decoded.indices()), "LOD fluid/state data lost during round trip");
        biomeIds[0] = (1 << 20) - 1;
        helper.assertTrue(packetData.biomeIds()[0] != biomeIds[0], "Published biome data aliases mutable input");
        System.out.println("ALLVR_WORLDGEN_SMOKE terralith=" + terralith + " possibleBiomes=" + source.biomeSource().possibleBiomes().size()
            + " fingerprint=" + fingerprint + " highY=" + cube.getPos().minBlockY() + " nonemptySections=" + solid
            + " elapsedMs=" + (System.nanoTime() - start) / 1_000_000);
        helper.succeed();
    }

    private static long fingerprint(AllvrTerrainSource.Column column) {
        long hash = 1;
        for (var section : column.sections()) for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                hash = hash * 31 + Block.getId(section.getBlockState(x, y, z));
        return hash;
    }
}
