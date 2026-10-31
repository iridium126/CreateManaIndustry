package com.iridium126.createmanaindustry.content.fluids.mist;

import java.util.function.BiConsumer;

import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class MistIrrigationGameTests {

    @GameTest(template = "mist_irrigation_test")
    public static void waterMistRequiresStrictlyMoreThanTenPercent(GameTestHelper helper) {
        withFarmland(helper, (level, pos) -> {
            assertPersistentMist(helper, pos, pos.east(19), 20, Fluids.WATER, 2,
                    "Water mist below 10% must not hydrate");
            assertPersistentMist(helper, pos, pos.east(9), 10, Fluids.WATER, 2,
                    "Water mist at exactly 10% must not hydrate");
            assertPersistentMist(helper, pos, pos.east(8), 10, Fluids.WATER, 7,
                    "Water mist above 10% must fully hydrate");
            assertPersistentMist(helper, pos, pos.east(8), 10, Fluids.FLOWING_WATER, 7,
                    "Flowing vanilla water must also count as water");
            assertPersistentMist(helper, pos, pos.east(8), 10, Fluids.LAVA, 2,
                    "Non-water mist must not hydrate");
            helper.succeed();
        });
    }

    @GameTest(template = "mist_irrigation_test")
    public static void onlyDominantMistCountsAndWaterSourcesDoNotAdd(GameTestHelper helper) {
        withFarmland(helper, (level, pos) -> {
            BlockPos water = pos.east(2);
            BlockPos other = pos.west(2);
            try {
                MistFieldStore.setActive(level, water, true, 3, new FluidStack(Fluids.WATER, 1000));
                MistFieldStore.setActive(level, other, true, 10, new FluidStack(Fluids.LAVA, 1000));
                resetAndTick(helper, pos, 3, 2, "Stronger non-water mist must prevent mist irrigation");

                MistFieldStore.updateRadius(level, water, 10);
                MistFieldStore.updateRadius(level, other, 3);
                resetAndTick(helper, pos, 3, 7, "Dominant water mist must hydrate");

                MistFieldStore.updateFluid(level, water, new FluidStack(Fluids.LAVA, 1000));
                resetAndTick(helper, pos, 3, 2, "A fluid switch must immediately affect irrigation");

                MistFieldStore.setActive(level, water, true, 0, new FluidStack(Fluids.WATER, 1000));
                MistFieldStore.setActive(level, other, false, 0);
                resetAndTick(helper, pos, 3, 2, "A zero-radius field must not hydrate");
            } finally {
                MistFieldStore.setActive(level, water, false, 0);
                MistFieldStore.setActive(level, other, false, 0);
            }

            BlockPos first = pos.east(9);
            BlockPos second = pos.west(9);
            try {
                MistFieldStore.setActive(level, first, true, 10, new FluidStack(Fluids.WATER, 1000));
                MistFieldStore.addTimed(level, second, new FluidStack(Fluids.WATER, 1000),
                        10, level.getGameTime() + 100);
                resetAndTick(helper, pos, 3, 2, "Two 10% water sources must not add up to irrigation");
            } finally {
                MistFieldStore.setActive(level, first, false, 0);
                MistFieldStore.removeTimed(level, second);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "mist_irrigation_test")
    public static void removalAndExpiryRestoreDryingWithoutConsumingCapacity(GameTestHelper helper) {
        withFarmland(helper, (level, pos) -> {
            BlockPos source = pos.east(2);
            var waterId = BuiltInRegistries.FLUID.getKey(Fluids.WATER);
            try {
                MistFieldStore.setActive(level, source, true, 10, new FluidStack(Fluids.WATER, 1000));
                MistFieldStore.addCapacity(level, source, 250);
                resetAndTick(helper, pos, 0, 7, "Persistent mist must hydrate dry, bare farmland");
                tickAndAssert(helper, pos, 7, "Continuous mist must maintain maximum moisture");
                helper.assertTrue(MistFieldStore.availableCapacity(level, pos, waterId, false) == 250,
                        "Irrigation must not consume mist capacity");

                MistFieldStore.setActive(level, source, false, 0);
                tickAndAssert(helper, pos, 6, "Removing the source must resume drying");

                MistFieldStore.emitOrExtendTimed(level, source, new FluidStack(Fluids.WATER, 1000),
                        10, level.getGameTime() + 100, 250);
                tickAndAssert(helper, pos, 7, "Timed water mist must hydrate");
                helper.assertTrue(MistFieldStore.availableCapacity(level, pos, waterId, false) == 250,
                        "Irrigation must not consume timed mist capacity");

                // Exercise the expiry cleanup used by MistFieldTicker without waiting on random ticks.
                MistFieldStore.addTimed(level, source, new FluidStack(Fluids.WATER, 1000),
                        10, level.getGameTime());
                MistFieldStore.tick(level, expired -> {});
                tickAndAssert(helper, pos, 6, "Expired mist must stop hydrating");
                for (int moisture = 5; moisture >= 0; moisture--)
                    tickAndAssert(helper, pos, moisture, "Unwatered farmland must continue drying");
                level.getBlockState(pos).randomTick(level, pos, level.random);
                helper.assertTrue(level.getBlockState(pos).is(Blocks.DIRT),
                        "Dry bare farmland must eventually revert to dirt");
            } finally {
                MistFieldStore.setActive(level, source, false, 0);
                MistFieldStore.removeTimed(level, source);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "mist_irrigation_test")
    public static void vanillaWaterStillHydratesAndMistHasNoExtraRange(GameTestHelper helper) {
        withFarmland(helper, (level, pos) -> {
            BlockPos nearby = pos.east(4);
            try {
                MistFieldStore.setActive(level, nearby, true, 1, new FluidStack(Fluids.WATER, 1000));
                resetAndTick(helper, pos, 3, 2, "Nearby water mist outside the farmland must not hydrate");

                MistFieldStore.setActive(level, nearby, true, 10, new FluidStack(Fluids.LAVA, 1000));
                level.setBlockAndUpdate(nearby, Blocks.WATER.defaultBlockState());
                resetAndTick(helper, pos, 0, 7, "Vanilla water must hydrate even with dominant lava mist");
            } finally {
                MistFieldStore.setActive(level, nearby, false, 0);
                level.setBlockAndUpdate(nearby, Blocks.AIR.defaultBlockState());
            }
            helper.succeed();
        });
    }

    private static void withFarmland(GameTestHelper helper, BiConsumer<ServerLevel, BlockPos> test) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(10, 2, 10));
        // A roof excludes rain; all checks invoke the real random tick synchronously.
        level.setBlockAndUpdate(pos.above(2), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
        double previous = ServerConfig.mistBaseConcentration;
        try {
            ServerConfig.mistBaseConcentration = 1.0;
            test.accept(level, pos);
        } finally {
            ServerConfig.mistBaseConcentration = previous;
        }
    }

    private static void assertPersistentMist(GameTestHelper helper, BlockPos pos, BlockPos source,
            int radius, Fluid fluid, int expected, String message) {
        ServerLevel level = helper.getLevel();
        try {
            MistFieldStore.setActive(level, source, true, radius, new FluidStack(fluid, 1000));
            resetAndTick(helper, pos, 3, expected, message);
        } finally {
            MistFieldStore.setActive(level, source, false, 0);
        }
    }

    private static void resetAndTick(GameTestHelper helper, BlockPos pos, int initial,
            int expected, String message) {
        helper.getLevel().setBlockAndUpdate(pos,
                Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, initial));
        tickAndAssert(helper, pos, expected, message);
    }

    private static void tickAndAssert(GameTestHelper helper, BlockPos pos, int expected, String message) {
        ServerLevel level = helper.getLevel();
        level.getBlockState(pos).randomTick(level, pos, level.random);
        var state = level.getBlockState(pos);
        helper.assertTrue(state.is(Blocks.FARMLAND) && state.getValue(FarmBlock.MOISTURE) == expected,
                message + "; expected moisture=" + expected + ", actual=" + state);
    }
}
