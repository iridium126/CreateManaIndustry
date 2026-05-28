package com.iridium126.createtricks.content.fluids;

import com.iridium126.createtricks.Config;
import com.iridium126.createtricks.CreateTricksFluids;
import com.iridium126.createtricks.trickster.TricksterReflection;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;

public class TricksterKnotFluidHandler implements IFluidHandlerItem {
	private ItemStack container;

	public TricksterKnotFluidHandler(ItemStack container) {
		this.container = container;
	}

	@Override
	public ItemStack getContainer() {
		return container;
	}

	@Override
	public int getTanks() {
		return 1;
	}

	@Override
	public FluidStack getFluidInTank(int tank) {
		if (tank != 0)
			return FluidStack.EMPTY;
		int amount = getDrainableAmount();
		if (amount <= 0)
			return FluidStack.EMPTY;
		return new FluidStack(CreateTricksFluids.LIQUID_MANA.get(), amount);
	}

	@Override
	public int getTankCapacity(int tank) {
		return tank == 0 ? getDrainableAmount() : 0;
	}

	@Override
	public boolean isFluidValid(int tank, FluidStack stack) {
		return false;
	}

	@Override
	public int fill(FluidStack resource, IFluidHandler.FluidAction action) {
		return 0;
	}

	@Override
	public FluidStack drain(FluidStack resource, IFluidHandler.FluidAction action) {
		FluidStack containedFluid = getFluidInTank(0);
		if (resource.isEmpty() || containedFluid.isEmpty())
			return FluidStack.EMPTY;
		if (!FluidStack.isSameFluidSameComponents(resource, containedFluid))
			return FluidStack.EMPTY;
		return drain(resource.getAmount(), action);
	}

	@Override
	public FluidStack drain(int maxDrain, IFluidHandler.FluidAction action) {
		if (container.getCount() != 1 || maxDrain <= 0 || !TricksterReflection.isKnotStack(container))
			return FluidStack.EMPTY;

		int drainable = getDrainableAmount();
		if (drainable <= 0)
			return FluidStack.EMPTY;

		int drainedAmount = Math.min(maxDrain, drainable);
		FluidStack drainedFluid = new FluidStack(CreateTricksFluids.LIQUID_MANA.get(), drainedAmount);

		if (!action.execute() || TricksterReflection.hasInfiniteMana(container))
			return drainedFluid;

		Level level = CreateTricksFluidTransferContext.getLevel();
		if (level == null)
			return FluidStack.EMPTY;

		float currentMana = TricksterReflection.getMana(container, level);
		float manaToDrain = Math.min(currentMana, drainedAmount * Config.manaPerBucket / 1000f);
		if (manaToDrain <= 0)
			return FluidStack.EMPTY;

		float drainedMana = TricksterReflection.drainMana(container, level, manaToDrain);
		return drainedMana > 0 ? drainedFluid : FluidStack.EMPTY;
	}

	private int getDrainableAmount() {
		if (!TricksterReflection.isKnotStack(container))
			return 0;
		if (TricksterReflection.hasInfiniteMana(container))
			return 1000;

		Level level = CreateTricksFluidTransferContext.getLevel();
		if (level == null)
			return 0;

		float mana = TricksterReflection.getMana(container, level);
		if (mana <= 0)
			return 0;

		double amount = Math.ceil(mana * 1000.0 / Config.manaPerBucket);
		return (int) Math.min(Integer.MAX_VALUE, Math.max(1, amount));
	}
}
