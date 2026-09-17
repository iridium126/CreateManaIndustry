package com.iridium126.createmanaindustry.dimension.gen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class AllvrSanctuaryGameTests {
    @GameTest(template = "worldgen_test", timeoutTicks = 1200)
    public static void terrainBridgesPlantsAndChunkOrder(GameTestHelper helper) {
        var registry = helper.getLevel().registryAccess().registryOrThrow(Registries.BIOME);
        var generator = new AllvrSanctuaryGenerator(137, 63, Blocks.WATER.defaultBlockState());
        var field = new AllvrSanctuary(137);
        var pos = new BlockPos.MutableBlockPos();
        int air = 0, masonry = 0, plants = 0, checked = 0;
        for (int[] point : new int[][]{{0,0},{5,0},{6,0},{9,0},{12,0},{-10,-1},{-10,0},{30,0},{43,0},{44,0}}) {
            var chunkPos = new ChunkPos(point[0], point[1]);
            var forward = new ProtoChunk(chunkPos, UpgradeData.EMPTY, LevelHeightAccessor.create(-128, 512), registry, null);
            var reverse = new ProtoChunk(chunkPos, UpgradeData.EMPTY, LevelHeightAccessor.create(-128, 512), registry, null);
            // Deliberately different initial terrain and an obsolete fluid pocket inside the excavation.
            for (var chunk : new ProtoChunk[]{forward, reverse}) {
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = -16; y <= 120; y++)
                    chunk.setBlockState(pos.set(chunkPos.getMinBlockX()+x,y,chunkPos.getMinBlockZ()+z),
                        (y == 30 ? Blocks.WATER : Blocks.STONE).defaultBlockState(), false);
                Heightmap.primeHeightmaps(chunk, java.util.Set.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG));
                generator.sculpt(chunk, false);
            }
            generator.sculpt(forward, true); generator.decorate(forward);
            // Interleave an unrelated chunk operation; generation must not consume shared random state.
            generator.sculpt(reverse, true); generator.decorate(reverse);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int wx = chunkPos.getMinBlockX()+x, wz = chunkPos.getMinBlockZ()+z;
                var c = field.column(wx,wz);
                for (int y = -16; y < 150; y++) {
                    var state = forward.getBlockState(pos.set(wx,y,wz));
                    helper.assertTrue(state == reverse.getBlockState(pos), "Order-dependent block at " + pos);
                    checked++;
                    if (c.radius() <= 90 && y == 95) helper.assertTrue(state.is(Blocks.GRASS_BLOCK), "Root platform not Y95");
                    if (c.radius() >= 91 && c.radius() <= 214 && y <= 95) {
                        helper.assertTrue(state.getFluidState().isEmpty(), "Fluid in sanctuary excavation");
                        if (state.isAir()) air++;
                        if (state.is(Blocks.STONE_BRICKS) || state.is(Blocks.MOSSY_STONE_BRICKS)) masonry++;
                        if (state.is(Blocks.VINE) || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)
                            || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.FERN)) plants++;
                        if (c.masonry(y)) helper.assertTrue(!state.isAir(), "Missing structural block");
                    }
                    if (c.bridge() && c.pathCenter() && y == 96)
                        helper.assertTrue(state.isAir(), "Vegetation blocks walkway");
                }
                if (c.radius() >= 700) helper.assertTrue(forward.getBlockState(pos.set(wx,120,wz)).is(Blocks.STONE), "Outside modified");
            }
        }
        helper.assertTrue(air > 1000 && masonry > 100 && plants > 50, "Missing excavation/bridge/vegetation");
        System.out.println("SANCTUARY_SMOKE blocks="+checked+" air="+air+" masonry="+masonry+" plants="+plants);
        helper.succeed();
    }
}
