package com.iridium126.createtricks.content.recipes;

import com.iridium126.createtricks.Config;
import com.iridium126.createtricks.CreateTricksFluids;
import com.iridium126.createtricks.CreateTricksRecipeTypes;
import com.iridium126.createtricks.trickster.TricksterReflection;
import com.simibubi.create.content.processing.recipe.ProcessingRecipeParams;
import com.simibubi.create.content.processing.recipe.StandardProcessingRecipe;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

public class SpellInkRecipe extends StandardProcessingRecipe<SingleRecipeInput> {
	public SpellInkRecipe(ProcessingRecipeParams params) {
		super(CreateTricksRecipeTypes.SPELL_INK_EMPTYING, params);
	}

	@Override
	protected int getMaxInputCount() {
		return 1;
	}

	@Override
	protected int getMaxOutputCount() {
		return 1;
	}

	@Override
	protected int getMaxFluidOutputCount() {
		return 1;
	}

	@Override
	public boolean matches(SingleRecipeInput input, Level level) {
		return ingredients.get(0).test(input.getItem(0));
	}

	public FluidStack getDynamicResult(ItemStack stack) {
		float mana = TricksterReflection.getMana(stack);
		int amount = (int) (mana / Config.manaPerBucket * 1000);
		return new FluidStack(CreateTricksFluids.LIQUID_MANA.get(), Math.max(1, amount));
	}
}
