package com.iridium126.createtricks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createtricks.content.recipes.IncompleteKnotAssembly;
import com.simibubi.create.content.fluids.transfer.FillingRecipe;

import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

@Mixin(value = FillingRecipe.class, remap = false)
public class FillingRecipeMixin {
	@Inject(method = "getRequiredFluid", at = @At("RETURN"), cancellable = true)
	private void createtricks$useConfiguredIncompleteKnotFluidAmount(
			CallbackInfoReturnable<SizedFluidIngredient> cir) {
		FillingRecipe recipe = (FillingRecipe) (Object) this;
		if (recipe.getIngredients().isEmpty())
			return;

		Ingredient ingredient = recipe.getIngredients().get(0);
		int requiredAmount = IncompleteKnotAssembly.getRequiredFluidAmount(ingredient);
		if (requiredAmount < 0)
			return;

		SizedFluidIngredient original = cir.getReturnValue();
		cir.setReturnValue(new SizedFluidIngredient(original.ingredient(), requiredAmount));
	}
}
