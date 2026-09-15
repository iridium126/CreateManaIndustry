package com.iridium126.createmanaindustry.worldgen.markov;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import java.util.HashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class MarkovTreeGameTests {
    @GameTest(template = "markov_test", timeoutTicks = 200)
    public static void registrationPlacementPaletteAndObstructions(GameTestHelper helper) {
        var world = helper.getLevel();
        var id = CreateManaIndustry.modLoc("natural_small_tree");
        var registry = world.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE);
        var configured = registry.get(id);
        helper.assertTrue(configured != null && configured.config() instanceof MarkovTreeConfiguration, "Configured feature not registered");
        helper.assertTrue(world.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).containsKey(id), "Placed feature not registered");
        var config = (MarkovTreeConfiguration) configured.config();
        var feature = (MarkovTreeFeature) configured.feature();
        var origin = helper.absolutePos(new BlockPos(24, 4, 24));
        clear(helper, origin);
        world.setBlock(origin.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        int seed = 2026;
        helper.assertTrue(configured.place(world, world.getChunkSource().getGenerator(), RandomSource.create(seed), origin), "Tree failed to place");
        byte[] expected = config.compiledModel().generate(RandomSource.create(seed).nextInt(), 1000);
        for (int i = 0; i < expected.length; i++) if (expected[i] != 0) {
            var pos = origin.offset(i % 19 - 9, i / 361, i / 19 % 19 - 9);
            var actual = world.getBlockState(pos);
            var mapped = config.palette().get("" + config.compiledModel().values().charAt(expected[i]));
            helper.assertTrue(actual.is(mapped.getBlock()), "Voxel axes or palette mismatch at " + pos);
            if (actual.hasProperty(LeavesBlock.DISTANCE)) helper.assertTrue(actual.getValue(LeavesBlock.DISTANCE) <= 6, "Unsupported leaves");
        }

        clear(helper, origin);
        var palette = new HashMap<>(config.palette());
        palette.put("N", Blocks.BIRCH_LOG.defaultBlockState());
        palette.put("D", Blocks.BIRCH_LOG.defaultBlockState());
        var custom = new MarkovTreeConfiguration(config.model(), 19, 19, 18, 1000, palette);
        var altered = new ConfiguredFeature<>(feature, custom);
        helper.assertTrue(altered.place(world, world.getChunkSource().getGenerator(), RandomSource.create(seed), origin), "Custom palette failed");
        helper.assertTrue(world.getBlockState(origin).is(Blocks.BIRCH_LOG), "Palette override was ignored");

        clear(helper, origin);
        world.setBlock(origin.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        world.setBlock(origin.above(), Blocks.STONE.defaultBlockState(), 2);
        helper.assertTrue(!configured.place(world, world.getChunkSource().getGenerator(), RandomSource.create(seed), origin), "Tree overwrote obstruction");
        helper.assertTrue(world.getBlockState(origin.below()).is(Blocks.GRASS_BLOCK), "Failed placement changed soil");
        int nonAir = 0;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9,0,-9), origin.offset(9,17,9)))
            if (!world.getBlockState(pos).isAir()) nonAir++;
        helper.assertTrue(nonAir == 1, "Partial tree left after obstruction");
        clear(helper, origin);
        world.setBlock(origin, Blocks.WATER.defaultBlockState(), 2);
        helper.assertTrue(!configured.place(world, world.getChunkSource().getGenerator(), RandomSource.create(seed), origin), "Underwater tree placed");
        clear(helper, origin);
        var high = new BlockPos(origin.getX(), world.getMaxBuildHeight() - 2, origin.getZ());
        world.setBlock(high.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
        helper.assertTrue(!configured.place(world, world.getChunkSource().getGenerator(), RandomSource.create(seed), high), "Height-clipped tree placed");
        helper.assertTrue(world.getBlockState(high).isAir(), "Partial high tree");
        world.setBlock(high.below(), Blocks.AIR.defaultBlockState(), 2);
        helper.succeed();
    }

    private static void clear(GameTestHelper helper, BlockPos origin) {
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9, 0, -9), origin.offset(9, 17, 9)))
            helper.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(origin.below(), Blocks.DIRT.defaultBlockState(), 2);
    }
}
