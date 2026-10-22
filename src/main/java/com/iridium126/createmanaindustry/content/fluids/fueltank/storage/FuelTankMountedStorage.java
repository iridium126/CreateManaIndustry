package com.iridium126.createmanaindustry.content.fluids.fueltank.storage;

import org.jetbrains.annotations.Nullable;

import com.iridium126.createmanaindustry.CMIMountedStorageTypes;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankBlockEntity;
import com.iridium126.createmanaindustry.content.fluids.fueltank.FuelTankContraptionBridge;
import com.iridium126.createmanaindustry.content.fluids.fueltank.storage.FuelTankMountedStorage.Handler;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.simibubi.create.api.contraption.storage.SyncedMountedStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorageType;
import com.simibubi.create.api.contraption.storage.fluid.WrapperMountedFluidStorage;
import com.simibubi.create.content.contraptions.Contraption;

import net.minecraft.core.BlockPos;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * Mounted fluid storage for the fuel tank, so pipes can access its contents
 * while it rides a contraption (mirrors Create's {@code FluidTankMountedStorage}).
 */
public class FuelTankMountedStorage extends WrapperMountedFluidStorage<Handler> implements SyncedMountedStorage {

	public static final MapCodec<FuelTankMountedStorage> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		ExtraCodecs.NON_NEGATIVE_INT.fieldOf("capacity").forGetter(FuelTankMountedStorage::getCapacity),
		FluidStack.OPTIONAL_CODEC.fieldOf("fluid").forGetter(FuelTankMountedStorage::getFluid)
	).apply(i, FuelTankMountedStorage::new));

	private boolean dirty;

	protected FuelTankMountedStorage(MountedFluidStorageType<?> type, int capacity, FluidStack stack) {
		super(type, new Handler(capacity, stack));
		this.wrapped.onChange = () -> this.dirty = true;
	}

	protected FuelTankMountedStorage(int capacity, FluidStack stack) {
		this(CMIMountedStorageTypes.MOLTEN_SALT_FUEL_TANK.get(), capacity, stack);
	}

	@Override
	public void unmount(Level level, BlockState state, BlockPos pos, @Nullable BlockEntity be) {
		if (FuelTankContraptionBridge.isPlacing(level)) return;
		if (be instanceof FuelTankBlockEntity tank && tank.isController()) {
			tank.restoreMounted(this.wrapped.getCapacity(), this.wrapped.getFluid());
		}
	}

	public FluidStack getFluid() {
		return this.wrapped.getFluid();
	}

	public int getCapacity() {
		return this.wrapped.getCapacity();
	}

	@Override
	public boolean isDirty() {
		return this.dirty;
	}

	@Override
	public void markClean() {
		this.dirty = false;
	}

	@Override
	public void afterSync(Contraption contraption, BlockPos localPos) {
		BlockEntity be = contraption.getBlockEntityClientSide(localPos);
		if (!(be instanceof FuelTankBlockEntity tank))
			return;

		tank.restoreMounted(getCapacity(), getFluid());
	}

	public static FuelTankMountedStorage fromTank(FuelTankBlockEntity tank) {
		FluidTank inventory = tank.getTankInventory();
		return new FuelTankMountedStorage(inventory.getCapacity(), inventory.getFluid().copy());
	}

	public static final class Handler extends FluidTank {
		private Runnable onChange = () -> {
		};

		public Handler(int capacity, FluidStack stack) {
			super(capacity);
			this.setFluid(stack);
		}

		@Override
		protected void onContentsChanged() {
			this.onChange.run();
		}
	}
}
