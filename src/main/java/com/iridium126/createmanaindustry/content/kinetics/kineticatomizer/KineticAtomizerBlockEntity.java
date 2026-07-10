package com.iridium126.createmanaindustry.content.kinetics.kineticatomizer;

import java.util.List;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

public class KineticAtomizerBlockEntity extends KineticBlockEntity {

	static final int TANK_CAPACITY = 1000;

	private final FluidTank tank = new FluidTank(TANK_CAPACITY) {
		@Override
		protected void onContentsChanged() {
			if (hasLevel() && !level.isClientSide) {
				setChanged();
				sendData();
			}
		}
	};

	public KineticAtomizerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	/**
	 * Returns a fluid handler for the given side. Only the bottom face accepts fluid
	 * input; other sides allow draining only.
	 */
	public IFluidHandler getFluidHandler(Direction side) {
		if (side == Direction.DOWN)
			return tank;
		return null;
	}

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		super.addToGoggleTooltip(tooltip, isPlayerSneaking);
		FluidStack fluid = tank.getFluid();
		tooltip.add(Component.literal(fluid.isEmpty()
				? "Empty"
				: fluid.getHoverName().getString() + " " + fluid.getAmount() + " / " + TANK_CAPACITY + " mB"));
		return true;
	}

	@Override
	protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.read(tag, registries, clientPacket);
		tank.readFromNBT(registries, tag.getCompound("Tank"));
	}

	@Override
	protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
		super.write(tag, registries, clientPacket);
		tag.put("Tank", tank.writeToNBT(registries, new CompoundTag()));
	}

	@Override
	public void tick() {
		super.tick();
	}
}
