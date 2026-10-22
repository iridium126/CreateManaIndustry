package com.iridium126.createmanaindustry.content.fluids.fueltank;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.ComparatorUtil;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.IFluidTank;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/** World/capability boundary. Only the controller owns a fluid state and group inventory. */
@SuppressWarnings("unchecked")
public class FuelTankBlockEntity extends CopycatBlockEntity
        implements IHaveGoggleInformation, IMultiBlockEntityContainer.Fluid {
    protected IFluidHandler fluidCapability;
    protected FluidTank tankInventory;
    protected BlockPos controller;
    protected int count = 1;
    protected BlockPos lastKnownPos;
    protected boolean updateConnectivity, updateCapability;
    boolean retryConnectivity, needsDeferredSplitHeal, settleAfterMerge;
    BlockPos deferredController;
    protected int luminosity;
    @Nullable public FuelRodStructure.RodData rodData;
    @Nullable FuelTankFluidState basins;
    @Nullable FuelTankFluidState savedBasins;
    Integer savedCount;
    private LerpedFloat fluidLevel;
    private FuelTankVisualState visuals;
    private int[] lightFloors;
    private boolean needsLightRefresh = true;
    private int quietInventory;
    private BlockPos transactionSeed;
    private static final int SYNC_RATE = 8;
    private int syncCooldown;
    private boolean queuedSync, forceFluidLevelUpdate = true;
    private boolean needsBasinSync, needsVolumeSync, needsNeighborRefresh;
    private boolean writingIncremental;
    private int publishedComparator = -1;

    public FuelTankBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        tankInventory = new SmartFluidTank(getCapacityPerBlock(), this::onFluidStackChanged);
        refreshCapability();
    }

    protected void updateConnectivity() {
        updateConnectivity = false;
        if (level.isClientSide || FuelTankContraptionBridge.isPlacing(level)) return;
        FuelTankConnectivity.updateConnectivity(this);
        FuelRodStructure.validateFor(level, worldPosition);
    }

    void refreshCapability() {
        fluidCapability = new SeedTrackingHandler(this, worldPosition);
        invalidateCapabilities();
    }
    public IFluidHandler getFluidCapability() { return fluidCapability; }

    @Override public void tick() {
        super.tick();
        if (syncCooldown > 0 && --syncCooldown == 0 && queuedSync) sendData();
        if (lastKnownPos == null) lastKnownPos = worldPosition;
        else if (!lastKnownPos.equals(worldPosition) && !level.isClientSide) {
            removeController(true); lastKnownPos = worldPosition;
        }
        if (level.isClientSide) tickVisuals();
        if (updateCapability) { updateCapability = false; refreshCapability(); }
        if (updateConnectivity) updateConnectivity();
        if (needsLightRefresh && !level.isClientSide && isController()) finishFluidChange(true);
    }

    @Override public void lazyTick() {
        super.lazyTick();
        if (!level.isClientSide) {
            if (isController() && needsDeferredSplitHeal) FuelTankConnectivity.healDeferredSplit(this);
            else if (retryConnectivity || (isController() && (basins == null || hasPendingGroupLoad())))
                FuelTankConnectivity.updateConnectivity(this);
            if (rodData != null) FuelRodStructure.validateFrom(level, rodData.center);
        }
    }

    public void tickVisuals() {
        if (fluidLevel != null) fluidLevel.tickChaser();
        if (basins != null) getVisualState().tick(basins);
    }
    public FuelTankVisualState getVisualState() {
        if (visuals == null || !visuals.matches(basins)) visuals = new FuelTankVisualState(basins);
        return visuals;
    }

    private static final class SeedTrackingHandler implements IFluidHandler {
        private final FuelTankBlockEntity part;
        private final BlockPos seed;
        SeedTrackingHandler(FuelTankBlockEntity part, BlockPos seed) { this.part = part; this.seed = seed; }
        private FuelTankBlockEntity owner() { return part.getControllerBE(); }
        private IFluidHandler delegate() { FuelTankBlockEntity o = owner(); return o == null ? EMPTY : o.tankInventory; }
        private static final FluidTank EMPTY = new FluidTank(0);
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) { return delegate().getFluidInTank(tank); }
        @Override public int getTankCapacity(int tank) { return delegate().getTankCapacity(tank); }
        @Override public boolean isFluidValid(int tank, FluidStack fluid) { return delegate().isFluidValid(tank, fluid); }
        private boolean ready(FuelTankBlockEntity o) {
            return o != null && !o.needsDeferredSplitHeal && !o.hasPendingGroupLoad() && !o.retryConnectivity;
        }
        @Override public int fill(FluidStack fluid, FluidAction action) {
            FuelTankBlockEntity o = owner(); if (!ready(o)) return 0;
            o.transactionSeed = seed;
            try { return o.tankInventory.fill(fluid, action); } finally { o.transactionSeed = null; }
        }
        @Override public FluidStack drain(FluidStack fluid, FluidAction action) {
            FuelTankBlockEntity o = owner(); if (!ready(o)) return FluidStack.EMPTY;
            o.transactionSeed = seed;
            try { return o.tankInventory.drain(fluid, action); } finally { o.transactionSeed = null; }
        }
        @Override public FluidStack drain(int amount, FluidAction action) {
            FuelTankBlockEntity o = owner(); if (!ready(o)) return FluidStack.EMPTY;
            o.transactionSeed = seed;
            try { return o.tankInventory.drain(amount, action); } finally { o.transactionSeed = null; }
        }
    }

    void setInventoryQuietly(int capacity, FluidStack fluid) {
        quietInventory++;
        try { tankInventory.setCapacity(capacity); tankInventory.setFluid(fluid.copy()); }
        finally { quietInventory--; }
    }

    public void restoreMounted(int capacity, FluidStack fluid) {
        setInventoryQuietly(capacity, fluid);
        if (basins != null && basins.total() != fluid.getAmount()) basins.settle(fluid.getAmount());
        finishFluidChange(false);
        if (level != null && !level.isClientSide) { markVolumesDirty(); setChanged(); sendData(); }
    }

    void restoreCell(FluidStack fluid, boolean rotated) {
        controller = null; count = 1; lastKnownPos = worldPosition;
        clearSavedGroupState(); needsDeferredSplitHeal = false; deferredController = null;
        basins = new FuelTankFluidState(FuelTankGeometry.build(Set.of(worldPosition), getCapacityPerBlock()));
        basins.settle(fluid.getAmount());
        setInventoryQuietly(getCapacityPerBlock(), fluid);
        settleAfterMerge = rotated; updateConnectivity = true; retryConnectivity = false;
    }

    protected void onFluidStackChanged(FluidStack fluid) {
        if (quietInventory > 0 || !hasLevel() || !isController()) return;
        if (basins != null) {
            long delta = (long) fluid.getAmount() - basins.total();
            if (transactionSeed == null) basins.settle(fluid.getAmount());
            else {
                long leftover = delta > 0 ? basins.fill(transactionSeed, delta) : basins.drain(transactionSeed, -delta);
                if (leftover != 0) throw new IllegalStateException("Fuel inventory/distribution diverged: " + leftover);
            }
        }
        finishFluidChange(false);
        markVolumesDirty(); setChanged(); sendData();
    }

    /** Publish effects only after inventory and integer basin volumes agree. */
    void finishFluidChange(boolean structural) {
        if (!hasLevel()) return;
        FluidStack fluid = tankInventory.getFluid();
        int newLum = (int) (fluid.getFluidType().getLightLevel(fluid) / 1.2f);
        boolean all = structural || needsLightRefresh || luminosity != newLum;
        luminosity = newLum;
        if (level instanceof ServerLevel server) {
            if (basins == null) {
                if (all) FuelTankBlock.refreshLitStates(server, List.of(worldPosition), this);
            } else {
                FuelTankGeometry geometry = basins.geometry();
                BitSet changed = basins.takeChanged();
                if (lightFloors == null || lightFloors.length != geometry.basins.size()) all = true;
                if (all) {
                    FuelTankBlock.refreshLitStates(server, geometry.basinByCell.keySet(), this);
                    lightFloors = new int[geometry.basins.size()];
                    for (int b = 0; b < lightFloors.length; b++) lightFloors[b] = (int) Math.floor(basins.surface(b));
                } else if (luminosity > 0) {
                    for (int b = changed.nextSetBit(0); b >= 0; b = changed.nextSetBit(b + 1)) {
                        int next = (int) Math.floor(basins.surface(b)), old = lightFloors[b];
                        if (next == old) continue;
                        for (var row : geometry.basins.get(b).cellsByLevel()
                                .subMap(Math.min(old, next), true, Math.max(old, next), true).values())
                            FuelTankBlock.refreshLitStates(server, row, this);
                        lightFloors[b] = next;
                    }
                }
            }
        }
        needsLightRefresh = false;
        if (fluidLevel == null) fluidLevel = LerpedFloat.linear().startWithValue(getFillState());
        fluidLevel.chase(getFillState(), .5, LerpedFloat.Chaser.EXP);
    }

    @Override public boolean isController() { return controller == null || worldPosition.equals(controller); }
    @Override public BlockPos getController() { return isController() ? worldPosition : controller; }
    @Override public FuelTankBlockEntity getControllerBE() {
        if (isController() || !hasLevel()) return this;
        if (!level.isLoaded(controller)) return null;
        BlockEntity be = level.getBlockEntity(controller);
        return be instanceof FuelTankBlockEntity tank && !tank.isRemoved() && tank.isController() ? tank : null;
    }
    @Override public void setController(BlockPos pos) {
        if (level != null && level.isClientSide && !isVirtual()) return;
        if (Objects.equals(getController(), pos)) return;
        controller = pos; basins = null; visuals = null;
        refreshCapability(); setChanged(); markBasinsDirty(); sendDataImmediately();
    }
    @Override public void removeController(boolean keepFluids) {
        if (level.isClientSide) return;
        controller = null; count = 1; basins = null; visuals = null; clearSavedGroupState();
        if (!keepFluids) setInventoryQuietly(getCapacityPerBlock(), FluidStack.EMPTY);
        updateConnectivity = true; markBasinsDirty(); refreshCapability(); setChanged(); sendData();
    }
    @Override public void preventConnectivityUpdate() { updateConnectivity = false; }
    @Override public BlockPos getLastKnownPos() { return lastKnownPos; }
    @Override public void notifyMultiUpdated() { setChanged(); }
	@Override
	public Direction.Axis getMainConnectionAxis() {
		return Direction.Axis.Y;
	}

	@Override
	public int getMaxLength(Direction.Axis longAxis, int width) {
		return ServerConfig.fuelTankMaxBlocks;
	}

	@Override
	public int getMaxWidth() {
		return ServerConfig.fuelTankMaxBlocks;
	}

	@Override
	public int getHeight() {
		return count;
	}

	@Override
	public void setHeight(int height) {
		this.count = height;
	}

	@Override
	public int getWidth() {
		return 1;
	}

	@Override
	public void setWidth(int width) {
	}

	@Override
	public boolean hasTank() {
		return true;
	}

	@Override
	public int getTankSize(int tank) {
		return getCapacityPerBlock();
	}

	@Override
	public void setTankSize(int tank, int blocks) {
		applyFluidTankSize(blocks);
	}

	@Override
	public IFluidTank getTank(int tank) {
		return tankInventory;
	}

	@Override
	public FluidStack getFluid(int tank) {
		return tankInventory.getFluid().copy();
	}
    public static int getCapacityPerBlock() { return ServerConfig.fuelTankCapacity * 1000; }
    public void applyFluidTankSize(int blocks) {
        int capacity = blocks * getCapacityPerBlock();
        tankInventory.setCapacity(capacity);
        if (tankInventory.getFluidAmount() > capacity) tankInventory.drain(tankInventory.getFluidAmount() - capacity, IFluidHandler.FluidAction.EXECUTE);
        forceFluidLevelUpdate = true;
    }
    public FluidTank getTankInventory() { return tankInventory; }
    public int getCount() { FuelTankBlockEntity o = getControllerBE(); return o == null ? count : o.count; }
    public float getFillState() { return tankInventory.getCapacity() == 0 ? 0 : (float) tankInventory.getFluidAmount() / tankInventory.getCapacity(); }
    public boolean hasPendingGroupLoad() { return savedCount != null; }
    void clearSavedGroupState() { savedCount = null; savedBasins = null; }
    public int getFallbackHeight() { return basins == null ? 1 : basins.geometry().maxCell.getY() - basins.geometry().minCell.getY() + 1; }
    @Nullable public BlockPos getFallbackMin() { return basins == null ? null : basins.geometry().minCell; }
    @Nullable public BlockPos getFallbackMax() { return basins == null ? null : basins.geometry().maxCell; }
    @Nullable public LerpedFloat getFluidLevel() { return fluidLevel; }
    public void setFluidLevel(LerpedFloat level) { fluidLevel = level; }
    @Override public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        FuelTankBlockEntity o = getControllerBE();
        return o != null && containedFluidTooltip(tooltip, sneaking, o.tankInventory);
    }

    @Override public void setMaterial(BlockState material) {
        super.setMaterial(material); needsLightRefresh = true;
        FuelTankBlockEntity o = getControllerBE(); if (o != null) o.needsLightRefresh = true;
    }

    @Override public void sendData() {
        if (syncCooldown > 0) { queuedSync = true; return; }
        super.sendData(); queuedSync = false; syncCooldown = SYNC_RATE;
        if (needsNeighborRefresh && level != null && !level.isClientSide) {
            needsNeighborRefresh = false;
            int signal = ComparatorUtil.fractionToRedstoneLevel(getFillState());
            for (BlockPos pos : basins == null ? Set.of(worldPosition) : basins.geometry().basinByCell.keySet()) {
                if (!level.isLoaded(pos)) continue;
                FuelTankBlockEntity part = FuelTankConnectivity.partAt(getType(), level, pos);
                if (part != null) level.updateNeighbourForOutputSignal(pos, part.getBlockState().getBlock());
            }
            publishedComparator = signal;
        }
    }
    public void sendDataImmediately() { syncCooldown = 0; sendData(); }
    public void markBasinsDirty() {
        needsBasinSync = true; needsVolumeSync = true; forceFluidLevelUpdate = true;
        needsNeighborRefresh = true; needsLightRefresh = true; lightFloors = null;
    }
    public void markVolumesDirty() {
        needsVolumeSync = true;
        if (publishedComparator != ComparatorUtil.fractionToRedstoneLevel(getFillState())) needsNeighborRefresh = true;
    }
    public void setRodData(@Nullable FuelRodStructure.RodData rod, boolean sync) {
        if (Objects.equals(rodData, rod)) return;
        rodData = rod; setChanged(); if (sync) sendDataImmediately();
    }
    @Override public void initialize() {
        super.initialize(); needsLightRefresh = true;
        if (level.isClientSide) invalidateRenderBoundingBox();
    }

    /** Initial tags are complete and side-effect free, independent of incremental dirty flags. */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag(); write(tag, registries, true); return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this, (be, registries) -> {
            CompoundTag tag = new CompoundTag(); writingIncremental = true;
            try { write(tag, registries, true); }
            finally { writingIncremental = false; }
            needsBasinSync = false; needsVolumeSync = false; forceFluidLevelUpdate = false;
            if (basins != null) basins.clearSyncChanges();
            return tag;
        });
    }

    private CompoundTag writeBasins() {
        CompoundTag tag = basins.write(); tag.put("Anchor", NbtUtils.writeBlockPos(worldPosition)); return tag;
    }
    private FuelTankFluidState readBasins(CompoundTag tag) {
        int[] stored = tag.getIntArray("Origin");
        BlockPos origin = new BlockPos(stored[0], stored[1], stored[2]);
        BlockPos anchor = NBTHelper.readBlockPos(tag, "Anchor");
        return FuelTankFluidState.read(tag, origin.offset(worldPosition.subtract(anchor)));
    }
    @Override protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        BlockPos previous = getController();
        controller = tag.contains("Controller") ? NBTHelper.readBlockPos(tag, "Controller") : null;
        lastKnownPos = tag.contains("LastKnownPos") ? NBTHelper.readBlockPos(tag, "LastKnownPos") : null;
        luminosity = tag.getInt("Luminosity");
        if (!Objects.equals(previous, getController()) || !isController()) { basins = null; visuals = null; }
        if (isController()) {
            count = Math.max(1, tag.getInt("Count"));
            quietInventory++;
            try {
                tankInventory.setCapacity(count * getCapacityPerBlock());
                tankInventory.readFromNBT(registries, tag.getCompound("TankContent"));
            } finally { quietInventory--; }
            if (tag.contains("GeometryCleared")) { basins = null; visuals = null; }
            String key = clientPacket ? "BasinGeometry" : "Basins";
            if (tag.contains(key)) {
                basins = readBasins(tag.getCompound(key)); visuals = null;
                if (!clientPacket && (level == null || !level.isClientSide)) { savedBasins = basins; savedCount = count; }
            } else if (tag.contains("Volumes") && basins != null) basins.readVolumeUpdate(tag);
        }
        FuelRodStructure.RodData previousRod = rodData;
        if (tag.contains("Rod")) rodData = FuelRodStructure.RodData.readFromNBT(tag.getCompound("Rod"));
        else if (!clientPacket || tag.contains("RodCleared")) rodData = null;
        if (clientPacket && level != null && level.isClientSide && !Objects.equals(previousRod, rodData))
            FuelRodSync.notifyClientSync(worldPosition, rodData);
        if (!clientPacket) {
            updateConnectivity = true; needsLightRefresh = true;
            needsDeferredSplitHeal = tag.getBoolean("DeferredSplit");
            deferredController = tag.contains("DeferredController") ? NBTHelper.readBlockPos(tag, "DeferredController") : null;
        }
        if (tag.contains("ForceFluidLevel") || fluidLevel == null)
            fluidLevel = LerpedFloat.linear().startWithValue(getFillState());
        fluidLevel.chase(getFillState(), tag.contains("LazySync") ? .125 : .5, LerpedFloat.Chaser.EXP);
        if (clientPacket && level != null && level.isClientSide) {
            invalidateRenderBoundingBox();
            if (!Objects.equals(previous, getController())) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        updateCapability = true;
    }

    @Override protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putInt("Luminosity", luminosity);
        if (lastKnownPos != null) tag.put("LastKnownPos", NbtUtils.writeBlockPos(lastKnownPos));
        if (!isController()) tag.put("Controller", NbtUtils.writeBlockPos(controller));
        else {
            tag.putInt("Count", count); tag.put("TankContent", tankInventory.writeToNBT(registries, new CompoundTag()));
            if (!clientPacket) {
                if (basins != null) tag.put("Basins", writeBasins());
                if (needsDeferredSplitHeal) tag.putBoolean("DeferredSplit", true);
                if (deferredController != null) tag.put("DeferredController", NbtUtils.writeBlockPos(deferredController));
            } else if (basins == null) tag.putBoolean("GeometryCleared", true);
            else if (!writingIncremental || needsBasinSync) tag.put("BasinGeometry", writeBasins());
            else if (needsVolumeSync) basins.writeVolumeUpdate(tag);
        }
        if (rodData != null) tag.put("Rod", rodData.writeToNBT());
        else if (clientPacket) tag.putBoolean("RodCleared", true);
        if (clientPacket) {
            if (!writingIncremental || forceFluidLevelUpdate) tag.putBoolean("ForceFluidLevel", true);
            if (queuedSync) tag.putBoolean("LazySync", true);
        }
    }
    @Override public void writeSafe(CompoundTag tag, HolderLookup.Provider registries) {
        super.writeSafe(tag, registries); if (isController()) tag.putInt("Count", count);
    }
    @Override protected AABB createRenderBoundingBox() {
        if (isController() && basins != null) {
            BlockPos min = basins.geometry().minCell, max = basins.geometry().maxCell;
            return new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1d, max.getY() + 1d, max.getZ() + 1d);
        }
        return super.createRenderBoundingBox();
    }
}
