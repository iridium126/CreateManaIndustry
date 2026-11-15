package com.iridium126.createmanaindustry.kinetics;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.iridium126.createmanaindustry.mixin.kinetics.TorquePropagatorAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllBlockEntityTypes;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class TemporaryKineticsGameTests {
    private static final String TEMPLATE = "temporary_kinetics_test";
    private static final BlockPos CENTER = new BlockPos(8, 2, 8);
    private static final BlockState SHAFT = AllBlocks.SHAFT.getDefaultState()
            .setValue(RotatedPillarKineticBlock.AXIS, Direction.Axis.X);

    @GameTest(template = TEMPLATE)
    public static void savedNetworkCountsTemporaryStressOnce(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(CENTER);
        KineticBlockEntity shaft = place(helper, pos, SHAFT);
        TemporaryKinetics.apply(shaft, 64, 16, 40);
        CompoundTag tag = snapshot(helper, shaft);
        equal(helper, tag.getCompound("Network").getFloat("Capacity"), 0, "saved native capacity");
        equal(helper, clientCapacity(helper, shaft), 64, "client capacity");
        var network = shaft.getOrCreateNetwork();
        equal(helper, network.getActualCapacityOf(shaft), 64, "per-source capacity");
        Float lastStress = network.members.remove(shaft);
        try {
            equal(helper, snapshot(helper, shaft).getCompound("Network").getFloat("Capacity"), 0,
                    "native capacity snapshot after membership is pruned");
        } finally {
            network.members.put(shaft, lastStress);
        }
        unload(helper, shaft);
        forgetNetwork(helper, shaft);
        KineticBlockEntity restored = reload(helper, pos, tag);
        helper.runAfterDelay(3, () -> {
            capacity(helper, restored, 64);
            equal(helper, clientCapacity(helper, restored), 64, "published restored capacity");
            equal(helper, restored.getTheoreticalSpeed(), 16, "restored speed");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void synchronizedEffectBelongsToTheReceivingEntity(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(CENTER);
        var shaft = place(helper, pos, SHAFT);
        TemporaryKinetics.apply(shaft, 64, 16, 100);
        CompoundTag packet = shaft.getUpdateTag(helper.getLevel().registryAccess());
        var mirror = AllBlockEntityTypes.BRACKETED_KINETIC.get().create(pos, SHAFT);
        var replacement = AllBlockEntityTypes.BRACKETED_KINETIC.get().create(pos, SHAFT);
        mirror.handleUpdateTag(packet, helper.getLevel().registryAccess());
        helper.assertTrue(TemporaryKinetics.isActive(mirror) && !TemporaryKinetics.isActive(replacement),
                "a packet changed another entity at the same coordinates");
        mirror.onChunkUnloaded();
        helper.assertTrue(!TemporaryKinetics.isActive(mirror), "unload retained the packet state");
        replacement.handleUpdateTag(packet, helper.getLevel().registryAccess());
        replacement.handleUpdateTag(new CompoundTag(), helper.getLevel().registryAccess());
        helper.assertTrue(!TemporaryKinetics.isActive(replacement), "expiry packet retained the effect");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void dryWheelKeepsRotationThroughValidation(GameTestHelper helper) {
        KineticBlockEntity wheel = place(helper, helper.absolutePos(CENTER), AllBlocks.WATER_WHEEL.getDefaultState());
        TemporaryKinetics.apply(wheel, 128, 32, 95);
        helper.runAfterDelay(70, () -> {
            equal(helper, wheel.getGeneratedSpeed(), 0, "native water-wheel output");
            equal(helper, wheel.getTheoreticalSpeed(), 32, "temporary speed");
            capacity(helper, wheel, 128);
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void motorRetainsNativeCapacityAndRestoresItsNewSpeed(GameTestHelper helper) {
        BlockState state = AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.EAST);
        var motor = (CreativeMotorBlockEntity) place(helper, helper.absolutePos(CENTER), state);
        helper.runAfterDelay(2, () -> {
            float coefficient = motor.calculateAddedStressCapacity();
            TemporaryKinetics.apply(motor, 128, 32, 7);
            capacity(helper, motor, coefficient * 16 + 128);
            motor.generatedSpeed.setValue(64);
            equal(helper, motor.getTheoreticalSpeed(), 32, "speed after native settings change");
            capacity(helper, motor, coefficient * 64 + 128);
            equal(helper, snapshot(helper, motor).getCompound("Network").getFloat("Capacity"),
                    coefficient * 64, "saved native capacity");
        });
        helper.runAfterDelay(12, () -> {
            equal(helper, motor.getTheoreticalSpeed(), 64, "native speed after expiry");
            capacity(helper, motor, motor.calculateAddedStressCapacity() * 64);
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void replacingTargetEndsEffect(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(CENTER);
        KineticBlockEntity shaft = place(helper, pos, SHAFT);
        TemporaryKinetics.apply(shaft, 64, 16, 100);
        helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var replacement = place(helper, pos, AllBlocks.COGWHEEL.getDefaultState());
        helper.assertTrue(!TemporaryKinetics.isActive(shaft) && !TemporaryKinetics.isActive(replacement)
                && !replacement.isSource(), "replacement inherited state from the old shaft");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void assemblyStripsCopiedEffectAndDisassemblyDoesNotRestoreIt(GameTestHelper helper)
            throws Exception {
        BlockPos pos = helper.absolutePos(CENTER);
        ServerLevel level = helper.getLevel();
        BlockState bearingState = AllBlocks.MECHANICAL_BEARING.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.EAST);
        var bearing = (MechanicalBearingBlockEntity) place(helper, pos.west(), bearingState);
        KineticBlockEntity shaft = place(helper, pos, SHAFT);
        TemporaryKinetics.apply(shaft, 64, 16, 100);
        var contraption = new BearingContraption(false, Direction.EAST);
        helper.assertTrue(contraption.assemble(level, pos.west()), "bearing must capture the shaft");
        CompoundTag captured = contraption.getBlocks().get(BlockPos.ZERO).nbt();
        helper.assertTrue(captured != null && !captured.contains(TemporaryKinetics.SAVED_EFFECT_KEY)
                && TemporaryKinetics.isActive(shaft), "capture must strip only the copied effect");
        contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
        ControlledContraptionEntity.create(level, bearing, contraption);
        helper.assertTrue(!TemporaryKinetics.isActive(shaft), "assembly left the source active");
        BlockPos destination = pos.south(2);
        contraption.addBlocksToWorld(level, new StructureTransform(destination, 0, 0, 0));
        helper.runAfterDelay(2, () -> {
            var placed = (KineticBlockEntity) level.getBlockEntity(destination);
            helper.assertTrue(placed != null && !TemporaryKinetics.isActive(placed), "disassembly restored the effect");
            equal(helper, placed.getTheoreticalSpeed(), 0, "speed after disassembly");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void unloadWithdrawsCapacityAndResumeUsesOriginalDeadline(GameTestHelper helper) {
        BlockPos sourcePos = chunkEdge(helper);
        var source = place(helper, sourcePos, SHAFT);
        var member = place(helper, sourcePos.east(), SHAFT);
        TemporaryKinetics.apply(source, 64, 16, 16);
        var network = source.getOrCreateNetwork();
        helper.assertTrue(member.getOrCreateNetwork() == network, "shafts must connect across a chunk boundary");
        CompoundTag saved = snapshot(helper, source);
        equal(helper, snapshot(helper, member).getCompound("Network").getFloat("Capacity"), 0,
                "saved capacity on the member in the other chunk");
        unload(helper, source);
        equal(helper, network.calculateCapacity(), 0, "unloaded contribution");
        helper.runAfterDelay(2, () -> {
            equal(helper, clientCapacity(helper, member), 0, "published capacity after unload");
            reload(helper, sourcePos, saved);
        });
        helper.runAfterDelay(5, () -> equal(helper, clientCapacity(helper, member), 64, "capacity after reload"));
        helper.runAfterDelay(19, () -> {
            var restored = (KineticBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            helper.assertTrue(!TemporaryKinetics.isActive(restored), "reload restarted the full duration");
            equal(helper, member.getTheoreticalSpeed(), 0, "member speed after expiry");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void expiredUnloadedSourceCannotRestoreCapacityAfterNetworkRestart(GameTestHelper helper) {
        BlockPos sourcePos = chunkEdge(helper);
        BlockPos memberPos = sourcePos.east();
        var source = place(helper, sourcePos, SHAFT);
        var member = place(helper, memberPos, SHAFT);
        TemporaryKinetics.apply(source, 64, 16, 4);
        CompoundTag savedSource = snapshot(helper, source);
        CompoundTag savedMember = snapshot(helper, member);
        unload(helper, source);
        unload(helper, member);
        forgetNetwork(helper, source);
        helper.runAfterDelay(6, () -> reload(helper, memberPos, savedMember));
        helper.runAfterDelay(8, () -> {
            var first = (KineticBlockEntity) helper.getLevel().getBlockEntity(memberPos);
            equal(helper, clientCapacity(helper, first), 0, "member loaded ahead of expired source");
            reload(helper, sourcePos, savedSource);
        });
        helper.runAfterDelay(11, () -> {
            var expired = (KineticBlockEntity) helper.getLevel().getBlockEntity(sourcePos);
            var restored = (KineticBlockEntity) helper.getLevel().getBlockEntity(memberPos);
            helper.assertTrue(!expired.hasNetwork() && !TemporaryKinetics.isActive(expired),
                    "expired source retained its old network");
            equal(helper, restored.getTheoreticalSpeed(), 0, "member speed after reconciliation");
            helper.assertTrue(!snapshot(helper, expired).contains(TemporaryKinetics.SAVED_EFFECT_KEY),
                    "reconciled snapshot kept the expired effect");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void takeoverTransfersCapacityAcrossTheChunkBoundary(GameTestHelper helper) {
        BlockPos motorPos = chunkEdge(helper);
        BlockState motorState = AllBlocks.CREATIVE_MOTOR.getDefaultState()
                .setValue(DirectionalKineticBlock.FACING, Direction.EAST);
        var motor = (CreativeMotorBlockEntity) place(helper, motorPos, motorState);
        var shaft = place(helper, motorPos.east(), SHAFT);
        helper.runAfterDelay(2, () -> {
            float nativeStress = motor.calculateAddedStressCapacity() * 16;
            TemporaryKinetics.apply(shaft, 64, 8, 20);
            var original = motor.getOrCreateNetwork();
            helper.assertTrue(shaft.getOrCreateNetwork() == original && shaft.hasSource(),
                    "weak spell should follow the motor's network");
            capacity(helper, shaft, nativeStress + 64);

            TemporaryKinetics.apply(shaft, 128, 32, 5);
            var takeover = shaft.getOrCreateNetwork();
            helper.assertTrue(takeover != original && takeover == motor.getOrCreateNetwork() && !shaft.hasSource(),
                    "stronger spell did not transfer control to the shaft");
            equal(helper, original.calculateCapacity(), 0, "temporary capacity left in the old network");
            capacity(helper, shaft, nativeStress + 128);
        });
        helper.runAfterDelay(11, () -> {
            helper.assertTrue(shaft.hasSource() && motor.getOrCreateNetwork() == shaft.getOrCreateNetwork(),
                    "native motor did not take over after expiry");
            equal(helper, shaft.getTheoreticalSpeed(), 16, "speed of the recovered motor network");
            capacity(helper, shaft, motor.calculateAddedStressCapacity() * 16);
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE)
    public static void refreshOnlyRecalculatesWhenRotationChanges(GameTestHelper helper) {
        var shaft = place(helper, helper.absolutePos(CENTER), SHAFT);
        var network = new CountingNetwork();
        network.id = shaft.getBlockPos().asLong();
        TorquePropagatorAccessor.createmanaindustry$getNetworks().get(helper.getLevel()).put(network.id, network);
        TemporaryKinetics.apply(shaft, 64, 16, 20);
        network.recalculations = 0;
        network.syncs = 0;
        TemporaryKinetics.apply(shaft, 64, 16, 50);
        helper.assertTrue(network.recalculations == 0 && network.syncs == 0, "identical refresh visited the network");
        TemporaryKinetics.apply(shaft, 128, 16, 50);
        helper.assertTrue(network.recalculations == 0 && network.syncs == 1, "SU-only refresh did unnecessary work");
        TemporaryKinetics.apply(shaft, 128, 32, 50);
        helper.assertTrue(network.recalculations == 1, "one rotation change should trigger one recalculation");
        helper.succeed();
    }

    private static KineticBlockEntity place(GameTestHelper helper, BlockPos pos, BlockState state) {
        helper.getLevel().setBlock(pos, state, Block.UPDATE_ALL);
        return (KineticBlockEntity) helper.getLevel().getBlockEntity(pos);
    }

    private static CompoundTag snapshot(GameTestHelper helper, KineticBlockEntity be) {
        return be.saveWithFullMetadata(helper.getLevel().registryAccess());
    }

    private static void unload(GameTestHelper helper, KineticBlockEntity be) {
        // Match the callback order in LevelChunk.clearAllBlockEntities.
        be.onChunkUnloaded();
        helper.getLevel().removeBlockEntity(be.getBlockPos());
    }

    private static KineticBlockEntity reload(GameTestHelper helper, BlockPos pos, CompoundTag saved) {
        ServerLevel level = helper.getLevel();
        var restored = (KineticBlockEntity) BlockEntity.loadStatic(pos, SHAFT, saved, level.registryAccess());
        level.setBlockEntity(restored);
        return restored;
    }

    private static void forgetNetwork(GameTestHelper helper, KineticBlockEntity source) {
        // Simulate a fresh server network without touching any other test's topology.
        TorquePropagatorAccessor.createmanaindustry$getNetworks().get(helper.getLevel()).remove(source.network);
    }

    private static BlockPos chunkEdge(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 8));
        return pos.east(15 - Math.floorMod(pos.getX(), 16));
    }

    private static float clientCapacity(GameTestHelper helper, KineticBlockEntity be) {
        return be.getUpdateTag(helper.getLevel().registryAccess()).getCompound("Network").getFloat("Capacity");
    }

    private static void capacity(GameTestHelper helper, KineticBlockEntity be, float expected) {
        helper.assertTrue(be.hasNetwork(), "expected a kinetic network");
        equal(helper, be.getOrCreateNetwork().calculateCapacity(), expected, "network capacity");
    }

    private static void equal(GameTestHelper helper, float actual, float expected, String description) {
        helper.assertTrue(actual == expected, description + " expected " + expected + ", got " + actual);
    }

    private static final class CountingNetwork extends KineticNetwork {
        private int recalculations;
        private int syncs;

        @Override
        public void updateNetwork() {
            recalculations++;
            super.updateNetwork();
        }

        @Override
        public void sync() {
            syncs++;
            super.sync();
        }
    }
}
