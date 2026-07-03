package com.iridium126.createmanaindustry.content.arm;

import com.iridium126.createmanaindustry.content.items.TricksterKnotItemHandler;
import com.iridium126.createmanaindustry.trickster.TricksterReflection;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPoint;
import com.simibubi.create.content.kinetics.mechanicalArm.ArmInteractionPointType;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;

public class TricksterKnotArmInteractionPoint extends ArmInteractionPoint {
	public TricksterKnotArmInteractionPoint(ArmInteractionPointType type, Level level, BlockPos pos,
			BlockState state) {
		super(type, level, pos, state);
	}

	@Override
	protected IItemHandler getHandler(ArmBlockEntity armBlockEntity) {
		BlockEntity be = level.getBlockEntity(pos);
		if (be == null)
			return null;
		TricksterKnotItemHandler.Mode mode = TricksterReflection.isModularSpellConstructBlockEntity(be)
				|| TricksterReflection.isSpellConstructBlockEntity(be) ? TricksterKnotItemHandler.Mode.FIRST_SLOT
						: TricksterKnotItemHandler.Mode.ALL_SLOTS;
		return TricksterKnotItemHandler.create(be, mode);
	}

	@Override
	public ItemStack insert(ArmBlockEntity armBlockEntity, ItemStack stack, boolean simulate) {
		IItemHandler handler = getHandler(armBlockEntity);
		if (handler == null)
			return stack;

		for (int i = 0; i < handler.getSlots(); i++) {
			stack = handler.insertItem(i, stack, simulate);
			if (stack.isEmpty())
				return ItemStack.EMPTY;
		}
		return stack;
	}

	@Override
	public ItemStack extract(ArmBlockEntity armBlockEntity, int slot, boolean simulate) {
		IItemHandler handler = getHandler(armBlockEntity);
		if (handler == null)
			return ItemStack.EMPTY;
		return handler.extractItem(slot, 64, simulate);
	}

	@Override
	public int getSlotCount(ArmBlockEntity armBlockEntity) {
		IItemHandler handler = getHandler(armBlockEntity);
		return handler != null ? handler.getSlots() : 0;
	}
}
