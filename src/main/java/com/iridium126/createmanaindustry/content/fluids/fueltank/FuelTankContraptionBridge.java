package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.iridium126.createmanaindustry.content.fluids.fueltank.storage.FuelTankMountedStorage;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;

/** One disassembly transaction, independent of Create's rectangular multiblock relocation. */
public final class FuelTankContraptionBridge {
    private static final ThreadLocal<Deque<Placement>> PLACEMENTS = ThreadLocal.withInitial(ArrayDeque::new);
    private FuelTankContraptionBridge() {}

    public static boolean isPlacing(Level level) {
        for (Placement p : PLACEMENTS.get()) if (p.level == level) return true;
        return false;
    }

    public static Placement begin(Contraption contraption, Level level, StructureTransform transform) {
        List<MovingGroup> snapshots = new ArrayList<>();
        Map<BlockPos, Set<BlockPos>> groups = new HashMap<>();
        for (var info : contraption.getBlocks().values()) {
            if (!(info.state().getBlock() instanceof FuelTankBlock) || info.nbt() == null) continue;
            BlockPos controller = NBTHelper.readBlockPos(info.nbt(), "Controller");
            groups.computeIfAbsent(controller, key -> new HashSet<>()).add(info.pos());
        }
        for (var group : groups.entrySet()) {
            var storage = contraption.getStorage().getFluids().storages.get(group.getKey());
            var info = contraption.getBlocks().get(group.getKey());
            if (!(storage instanceof FuelTankMountedStorage mounted) || info == null) continue;
            FuelTankFluidState state;
            if (info.nbt().contains("Basins")) {
                CompoundTag tag = info.nbt().getCompound("Basins");
                int[] xyz = tag.getIntArray("Origin");
                BlockPos origin = new BlockPos(xyz[0], xyz[1], xyz[2]);
                BlockPos anchor = NBTHelper.readBlockPos(tag, "Anchor");
                state = FuelTankFluidState.read(tag, origin.offset(group.getKey().subtract(anchor)));
            } else state = new FuelTankFluidState(FuelTankGeometry.build(group.getValue(), FuelTankBlockEntity.getCapacityPerBlock()));
            snapshots.add(new MovingGroup(state, mounted.getFluid().copy(), group.getValue()));
        }
        for (var info : contraption.getBlocks().values())
            if (info.state().getBlock() instanceof FuelTankBlock && info.nbt() != null) prepareCellTag(info.nbt());
        return begin(level, transform, snapshots);
    }

    static Placement begin(Level level, StructureTransform transform, List<MovingGroup> snapshots) {
        Placement placement = new Placement(level, transform, snapshots);
        PLACEMENTS.get().push(placement); return placement;
    }

    static void prepareCellTag(CompoundTag tag) {
        for (String key : List.of("Controller", "Basins", "TankContent", "DeferredSplit", "DeferredController", "Rod")) tag.remove(key);
        tag.putInt("Count", 1);
    }

    record MovingGroup(FuelTankFluidState state, FluidStack fluid, Set<BlockPos> members) {}

    public static void placed(BlockEntity be) {
        Placement placement = PLACEMENTS.get().peek();
        if (placement != null && be instanceof FuelTankBlockEntity tank)
            placement.placed.put(be.getBlockPos(), tank);
    }

    public static void end(Placement placement, boolean success) {
        if (PLACEMENTS.get().pop() != placement) throw new IllegalStateException("Nested fuel placement mismatch");
        if (PLACEMENTS.get().isEmpty()) PLACEMENTS.remove();
        if (success) placement.restore();
    }

    public static final class Placement {
        private final Level level;
        private final StructureTransform transform;
        private final List<Source> sources = new ArrayList<>();
        private final Map<BlockPos, FuelTankBlockEntity> placed = new HashMap<>();

        private Placement(Level level, StructureTransform transform, List<MovingGroup> snapshots) {
            this.level = level; this.transform = transform;
            for (MovingGroup snapshot : snapshots) {
                if (snapshot.state.total() != snapshot.fluid.getAmount()) snapshot.state.settle(snapshot.fluid.getAmount());
                sources.add(new Source(snapshot.fluid.copy(), Set.copyOf(snapshot.members), snapshot.state.cellAmounts()));
            }
        }

        private void restore() {
            boolean rotated = transform.rotation != Rotation.NONE;
            Set<FuelTankBlockEntity> seeds = new HashSet<>();
            for (Source source : sources) for (BlockPos local : source.members) {
                BlockPos target = transform.apply(local);
                FuelTankBlockEntity tank = placed.get(target);
                if (tank == null || tank.isRemoved()) continue;
                long amount = source.shares.getOrDefault(local, 0L);
                tank.restoreCell(source.fluid.copyWithAmount(Math.toIntExact(amount)), rotated);
                seeds.add(tank);
            }
            for (FuelTankBlockEntity tank : placed.values()) if (!seeds.contains(tank)) tank.restoreCell(FluidStack.EMPTY, rotated);
            for (FuelTankBlockEntity tank : placed.values()) if (tank.updateConnectivity) tank.updateConnectivity();
        }
    }

    private record Source(FluidStack fluid, Set<BlockPos> members, Map<BlockPos, Long> shares) {}
}
