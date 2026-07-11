package com.iridium126.createmanaindustry.content.kinetics.kineticatomizer;

import java.util.List;
import java.util.function.BiConsumer;

import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

public class KineticAtomizerBlockEntity extends KineticBlockEntity {

	static final int TANK_CAPACITY = 1000;

	// Client-side callback: (blockPos, fluidStack) — set by ClientMistHandler on client init.
	// FluidStack.EMPTY means inactive, non-empty means active with that fluid.
	private static BiConsumer<BlockPos, FluidStack> mistSyncCallback = null;

	private final FluidTank tank = new FluidTank(TANK_CAPACITY) {
		@Override
		public boolean isFluidValid(FluidStack stack) {
			return !stack.is(FluidTags.LAVA);
		}

		@Override
		protected void onContentsChanged() {
			if (hasLevel() && !level.isClientSide) {
				setChanged();
				sendData();
			}
		}
	};

	private boolean wasActive = false;

	public KineticAtomizerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public IFluidHandler getFluidHandler(Direction side) {
		Direction facing = getBlockState().getValue(BlockStateProperties.FACING);
		Direction opposite = facing.getOpposite();
		if (side != opposite)
			return null;
		return new IFluidHandler() {
			@Override
			public int getTanks() { return tank.getTanks(); }

			@Override
			public FluidStack getFluidInTank(int t) { return tank.getFluidInTank(t); }

			@Override
			public int getTankCapacity(int t) { return tank.getTankCapacity(t); }

			@Override
			public boolean isFluidValid(int t, FluidStack stack) { return tank.isFluidValid(t, stack); }

			@Override
			public int fill(FluidStack resource, IFluidHandler.FluidAction action) { return tank.fill(resource, action); }

			@Override
			public FluidStack drain(FluidStack resource, IFluidHandler.FluidAction action) { return FluidStack.EMPTY; }

			@Override
			public FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) { return FluidStack.EMPTY; }
		};
	}

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		super.addToGoggleTooltip(tooltip, isPlayerSneaking);
		return containedFluidTooltip(tooltip, isPlayerSneaking, tank);
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		tank.readFromNBT(registries, tag.getCompound("Tank"));
		if (clientPacket) {
			wasActive = tag.getBoolean("MistActive");
			if (mistSyncCallback != null)
				mistSyncCallback.accept(worldPosition, wasActive ? tank.getFluid() : FluidStack.EMPTY);
		}
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
		if (clientPacket)
			tag.putBoolean("MistActive", wasActive);
	}

	/**
	 * @return {@code true} if this atomizer is currently producing mist (has fluid
	 *         and rotation). Synced to the client for visual rendering.
	 */
	public boolean isMistActive() {
		return wasActive;
	}

	/**
	 * Sets a callback invoked on the client whenever the active state is synced
	 * from the server. Used by the rendering layer to track which atomizers are
	 * producing mist.
	 */
	public static void setMistSyncCallback(BiConsumer<BlockPos, FluidStack> callback) {
		mistSyncCallback = callback;
	}

	@Override
	public void tick() {
		super.tick();

		if (level == null || level.isClientSide)
			return;

		float speed = Math.abs(getSpeed());
		boolean hasFluid = !tank.isEmpty();
		boolean isActive = speed > 0 && hasFluid;

		if (isActive != wasActive) {
			MistFieldStore.setActive(level, worldPosition, isActive, Config.mistMaxRadius);
			wasActive = isActive;
			sendData();
		}

		if (isActive) {
			float speedFactor = speed / 16f;
			int toConsume = Math.max(1, (int) (Config.mistFluidPerTick * speedFactor));
			tank.drain(toConsume, IFluidHandler.FluidAction.EXECUTE);
		}
	}

	@Override
	public void invalidate() {
		super.invalidate();
		if (level != null && !level.isClientSide)
			MistFieldStore.setActive(level, worldPosition, false, 0);
	}
}
