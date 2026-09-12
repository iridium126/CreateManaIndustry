package com.iridium126.createmanaindustry.dimension.gen;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
        verifyCentralChunks(helper, allay);
        var source = new AllvrTerrainSource(allay);
        boolean terralith = allay.registryAccess().registryOrThrow(Registries.BIOME).keySet().stream()
            .anyMatch(key -> key.getNamespace().equals("terralith"));
        helper.assertTrue(!Boolean.getBoolean("allvr.testTerralith") || terralith, "Terralith datapack was not loaded");
        helper.assertTrue(!terralith || source.biomeSource().possibleBiomes().stream()
            .anyMatch(biome -> biome.unwrapKey().orElseThrow().location().getNamespace().equals("terralith")),
            "Terralith's actual biome source must be used, not the vanilla preset");
        long start = System.nanoTime();
        var first = source.column(32768, -16384);
        long afterFirst = System.nanoTime();
        long fingerprint = fingerprint(first);
        source.column(32769, -16384);
        long afterSecond = System.nanoTime();
        var otherOrder = new AllvrTerrainSource(allay);
        otherOrder.column(32769, -16384);
        helper.assertTrue(fingerprint == fingerprint(otherOrder.column(32768, -16384)), "Decoration depends on request order");
        long afterOrder = System.nanoTime();
        helper.assertTrue(helper.getLevel().getChunkSource().getChunk(32768, -16384, ChunkStatus.FULL, false) == null,
            "Template generation loaded a real Overworld chunk");

        var settings = source.settings;
        var layout = new AllvrIslandLayout(allay.getSeed(), settings.noiseSettings().minY(), settings.noiseSettings().height(), settings.seaLevel());
        var island = layout.islandAt(0, 40001, 0);
        var generator = new AllvrIslandFieldGenerator(allay);
        var cube = new AllvrCube(AllvrCubePos.of((int) island.cx() >> 5, (island.cy() - 48) >> 5, (int) island.cz() >> 5),
            allay.registryAccess().registryOrThrow(Registries.BIOME));
        generator.generate(cube);
        long afterCube = System.nanoTime();
        int solid = 0;
        for (var section : cube.getSections()) if (!section.hasOnlyAir()) solid++;
        helper.assertTrue(solid > 0, "High island cube is entirely empty");
        var empty = new AllvrCube(AllvrCubePos.of(0, 10000, 0),
            allay.registryAccess().registryOrThrow(Registries.BIOME));
        generator.generate(empty);
        helper.assertTrue(java.util.Arrays.stream(empty.getSections()).allMatch(section -> section.hasOnlyAir()),
            "Void cube unexpectedly contains terrain");
        int qx = cube.getPos().minBlockX() >> 2, qy = cube.getPos().minBlockY() >> 2, qz = cube.getPos().minBlockZ() >> 2;
        helper.assertTrue(cube.getNoiseBiome(qx, qy, qz).equals(generator.biome(qx, qy, qz)), "Cube biome palette disagrees with generator");
        System.out.println("ALLVR_WORLDGEN_TIMING firstMs=" + (afterFirst - start) / 1_000_000
            + " secondMs=" + (afterSecond - afterFirst) / 1_000_000
            + " orderMs=" + (afterOrder - afterSecond) / 1_000_000
            + " cubeMs=" + (afterCube - afterOrder) / 1_000_000);
        System.out.println("ALLVR_WORLDGEN_SMOKE terralith=" + terralith + " possibleBiomes=" + source.biomeSource().possibleBiomes().size()
            + " fingerprint=" + fingerprint + " highY=" + cube.getPos().minBlockY() + " nonemptySections=" + solid
            + " elapsedMs=" + (System.nanoTime() - start) / 1_000_000);
        helper.succeed();
    }

    private static void verifyCentralChunks(GameTestHelper helper, net.minecraft.server.level.ServerLevel allay) {
        helper.assertTrue(allay.getMinBuildHeight() == -128 && allay.getMaxBuildHeight() == 384,
            "Native chunk height is not [-128, 384)");
        helper.assertTrue(allay.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator,
            "ChunkMap must recognize the native noise generator and initialize its real RandomState");
        int cx = helper.absolutePos(net.minecraft.core.BlockPos.ZERO).getX() >> 4;
        int cz = helper.absolutePos(net.minecraft.core.BlockPos.ZERO).getZ() >> 4;
        var chunk = allay.getChunk(cx, cz);
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        allay.getChunk(cx + 1, cz);
        allay.getChunk(cx - 1, cz);
        allay.getChunk(cx, cz + 1);
        allay.getChunk(cx, cz - 1);
        helper.assertTrue(chunk.getSectionsCount() == 32, "Native chunk must contain 32 sections");
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        int terrain = 0;
        for (int y = -128; y < 384; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            pos.set(minX + x, y, minZ + z);
            var state = chunk.getBlockState(pos);
            helper.assertTrue(!state.is(Blocks.BEDROCK), "Central terrain generated bedrock at " + pos);
            if (!state.isAir()) terrain++;
            helper.assertTrue(allay.getBlockState(pos) == state, "Level reads bypass native chunk at " + pos);
        }
        helper.assertTrue(terrain > 256, "Central chunk is an empty shell");
        var map = ((com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck) allay).allvr$getCubeMap();
        for (int y : new int[] {-128, -1, 0, 383}) {
            var target = new net.minecraft.core.BlockPos(minX + 1, y, minZ + 1);
            allay.setBlock(target, Blocks.CHEST.defaultBlockState(), 3);
            helper.assertTrue(chunk.getBlockState(target).is(Blocks.CHEST), "Write missed native chunk at " + y);
            helper.assertTrue(allay.getBlockEntity(target) == chunk.getBlockEntity(target)
                && chunk.getBlockEntity(target) != null, "Native block entity missing at " + y);
            helper.assertTrue(map.peek(target) == null, "Central block duplicated in cube map");
        }
        allay.getChunkSource().save(true);
        var saved = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(allay, chunk);
        var restored = net.minecraft.world.level.chunk.storage.ChunkSerializer.read(allay, allay.getPoiManager(),
            new net.minecraft.world.level.chunk.storage.RegionStorageInfo("allvr-test", allay.dimension(), "chunk"), chunk.getPos(), saved);
        helper.assertTrue(saved.getInt("yPos") == -8, "Native serializer used wrong minimum section");
        for (int y : new int[] {-128, -1, 0, 383}) {
            var target = new net.minecraft.core.BlockPos(minX + 1, y, minZ + 1);
            helper.assertTrue(restored.getBlockState(target).is(Blocks.CHEST), "Native save roundtrip lost boundary block");
        }
        for (int y : new int[] {-129, -160, -25600000}) {
            var cube = new AllvrCube(AllvrCubePos.of(0, y >> 5, 0),
                allay.registryAccess().registryOrThrow(Registries.BIOME));
            map.generator().generate(cube);
            for (var section : cube.getSections()) for (int ly = 0; ly < 16; ly++)
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                    helper.assertTrue(section.getBlockState(x, ly, z).is(Blocks.DEEPSLATE), "Lower cube is not solid deepslate");
        }
        for (int y : new int[] {-129, 384}) {
            var target = new net.minecraft.core.BlockPos(minX + 2, y, minZ + 2);
            allay.setBlock(target, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
            helper.assertTrue(map.peek(target) != null && map.peek(target).getBlockState(target).is(Blocks.DIAMOND_BLOCK),
                "Outside-boundary write missed the cube at " + y);
            helper.assertTrue(allay.getBlockState(target).is(Blocks.DIAMOND_BLOCK), "Cube boundary read failed at " + y);
            helper.assertTrue(chunk.getBlockState(target).isAir(), "Cube write leaked into native chunk");
        }
        // Configuration preserves arbitrary biome sources for future Allay biomes.
        var biome = allay.registryAccess().registryOrThrow(Registries.BIOME)
            .getHolderOrThrow(net.minecraft.world.level.biome.Biomes.DESERT);
        var settings = allay.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(AllvrChunkGenerator.SETTINGS);
        var configured = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(
            new net.minecraft.world.level.biome.FixedBiomeSource(biome), settings);
        var biomes = allay.registryAccess().registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
            .getHolderOrThrow(net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        var generator = new AllvrChunkGenerator(java.util.Optional.empty(), java.util.Optional.of(configured), settings, biomes);
        helper.assertTrue(generator.getBiomeSource() == configured.getBiomeSource(), "Configured biome source was replaced");
        var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, allay.registryAccess());
        var encoded = AllvrChunkGenerator.CODEC.codec().encodeStart(ops, generator).getOrThrow();
        var decoded = AllvrChunkGenerator.CODEC.codec().parse(ops, encoded).getOrThrow();
        helper.assertTrue(decoded.getBiomeSource().possibleBiomes().equals(java.util.Set.of(biome)),
            "Custom biome source did not survive dimension codec roundtrip");
        System.out.println("ALLVR_HYBRID_SMOKE nativeSections=32 terrainBlocks=" + terrain + " lowerCubes=3");
    }

    private static long fingerprint(AllvrTerrainSource.Column column) {
        long hash = 1;
        for (var section : column.sections()) for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                hash = hash * 31 + Block.getId(section.getBlockState(x, y, z));
        return hash;
    }
}
