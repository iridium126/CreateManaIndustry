package com.iridium126.createmanaindustry.dimension.gen;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;
import com.iridium126.createmanaindustry.dimension.gen.worldtree.EpicRedwoodGenerator;
import com.iridium126.createmanaindustry.dimension.gen.worldtree.EpicRedwoodPalette;
import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.dimension.storage.AllvrCubeSerializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class EpicRedwoodGameTests {
    @GameTest(template = "worldgen_test", timeoutTicks = 1200)
    public static void hybridTreeAndEditedCubeRoundtrip(GameTestHelper helper) throws Exception {
        var level = helper.getLevel().getServer().getLevel(AllvrDimensions.ALLAY_LEVEL);
        helper.assertTrue(level != null, "Allay test dimension missing");
        var chunk = level.getChunk(0, 0);
        var epicRedwood = new EpicRedwoodGenerator(level.getSeed());
        boolean nativeTreeFound = false;
        for (int y = 96; y < 384 && !nativeTreeFound; y++) for (int z = 0; z < 16 && !nativeTreeFound; z++) for (int x = 0; x < 16; x++) {
            var expected = epicRedwood.sample(x, y, z);
            if (expected != null) {
                var actual = chunk.getBlockState(new BlockPos(x, y, z));
                helper.assertTrue(actual == expected,
                    "Native FEATURES stage mismatch at " + x + "," + y + "," + z
                        + " expected=" + expected + " actual=" + actual);
                nativeTreeFound = true;
            }
        }
        helper.assertTrue(nativeTreeFound, "Shifted EpicRedwood has no material in the native height band");
        var map = ((AllvrServerLevelDuck) level).allvr$getCubeMap();
        var registry = level.registryAccess().registryOrThrow(Registries.BIOME);
        var sync = new AllvrCube(AllvrCubePos.of(0, 13, 0), registry);
        var async = new AllvrCube(sync.getPos(), registry);
        helper.assertTrue(map.generator().intersectsIsland(0, 13, 0), "EpicRedwood-only cube was filtered out");
        map.generator().generate(sync);
        map.generator().generateAsync(async).join();
        var pos = new BlockPos.MutableBlockPos();
        for (int y = 416; y < 448; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            pos.set(x, y, z);
            var state = sync.getBlockState(pos);
            helper.assertTrue(state == async.getBlockState(pos), "Sync/async mismatch at " + pos);
            var expected = epicRedwood.sample(x, y, z);
            if (expected != null) helper.assertTrue(state == expected, "Cube lost EpicRedwood material at " + pos);
            helper.assertTrue(!state.isAir() || expected == null, "Unexpected empty EpicRedwood material at " + pos);
        }
        BlockPos cut = new BlockPos(8, 416, 8), built = new BlockPos(8, 417, 8);
        sync.setBlockState(cut, Blocks.AIR.defaultBlockState(), false);
        sync.setBlockState(built, Blocks.GOLD_BLOCK.defaultBlockState(), false);
        var light = new com.iridium126.createmanaindustry.dimension.light.AllvrLightEngine(level,
            new com.iridium126.createmanaindustry.dimension.light.AllvrLightEngine.Access() {
                public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p) { return sync.getBlockState(p); }
                public boolean isLoaded(BlockPos p) { return AllvrCubePos.of(p.getX() >> 5, p.getY() >> 5, p.getZ() >> 5).equals(sync.getPos()); }
            });
        var snapshot = AllvrCubeSerializer.snapshot(sync, level, light);
        var restored = AllvrCubeSerializer.load(sync.getPos(), snapshot.tag(), level);
        helper.assertTrue(restored.getBlockState(cut).isAir(), "Save/load restored chopped wood");
        helper.assertTrue(restored.getBlockState(built).is(Blocks.GOLD_BLOCK), "Save/load lost player construction");
        // Model top and negative horizontal coordinates exercise distant sparse pages.
        var top = new AllvrCube(AllvrCubePos.of(-1, 98, -1), registry);
        map.generator().generate(top);
        for (int y = 3136; y < 3168; y++) for (int z = -32; z < 0; z++) for (int x = -32; x < 0; x++) {
            var expected = epicRedwood.sample(x, y, z);
            if (expected != null) helper.assertTrue(top.getBlockState(pos.set(x, y, z)) == expected, "Top/negative coordinate mismatch");
        }
        try (var input = EpicRedwoodGameTests.class.getResourceAsStream("/data/createmanaindustry/markov/epic_redwood_palette.json")) {
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var bundledPalette = new EpicRedwoodPalette(json);
            boolean sawDarkOak = false, sawSpruce = false;
            for (long seed = 0; seed < 128; seed++) {
                var state = bundledPalette.state(1, seed);
                sawDarkOak |= state.is(net.minecraft.world.level.block.Blocks.DARK_OAK_WOOD);
                sawSpruce |= state.is(net.minecraft.world.level.block.Blocks.SPRUCE_WOOD);
            }
            helper.assertTrue(sawDarkOak && sawSpruce, "Weighted D variants did not mix");
            json.add("D", com.google.gson.JsonParser.parseString("{\"Name\":\"createmanaindustry:prismarine_quartz_block\"}"));
            helper.assertTrue(new EpicRedwoodPalette(json).state(1).is(CMIBlocks.PRISMARINE_QUARTZ_BLOCK.get()), "Cross-namespace mapping rejected");
            json.remove("N");
            boolean rejected = false;
            try { new EpicRedwoodPalette(json); } catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "Incomplete palette accepted");
        }
        System.out.println("EPIC_REDWOOD_SMOKE nativeSeam=383 cubeSeam=416 comparedBlocks=32768 editedRoundtrip=ok");
        helper.succeed();
    }
}
