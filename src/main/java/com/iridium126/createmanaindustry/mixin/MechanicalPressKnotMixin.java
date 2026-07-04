package com.iridium126.createmanaindustry.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.iridium126.createmanaindustry.trickster.TricksterManaAccess;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;
import com.simibubi.create.foundation.recipe.RecipeApplier;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

@Mixin(value = MechanicalPressBlockEntity.class, remap = false)
public class MechanicalPressKnotMixin {

	@Redirect(method = "tryProcessInWorld",
			at = @At(value = "INVOKE",
					target = "Lcom/simibubi/create/foundation/recipe/RecipeApplier;applyRecipeOn(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)V"))
	private void createtricks$transferKnotOnEntityPress(ItemEntity entity, Recipe<?> recipe,
			boolean respectChances) {
		ItemStack inputCopy = entity.getItem().copy();
		RecipeApplier.applyRecipeOn(entity, recipe, respectChances);
		ItemStack result = entity.getItem();
		ItemStack transferred = TricksterManaAccess.applyKnotTransfer(entity.level(), inputCopy, result);
		if (transferred != result) {
			entity.setItem(transferred);
		}
	}

	@Redirect(method = {"tryProcessInWorld", "tryProcessOnBelt"},
			at = @At(value = "INVOKE",
					target = "Lcom/simibubi/create/foundation/recipe/RecipeApplier;applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;"))
	private List<ItemStack> createtricks$transferKnotOnListPress(Level level, ItemStack stack, Recipe<?> recipe,
			boolean respectChances) {
		ItemStack inputCopy = stack.copy();
		List<ItemStack> results = RecipeApplier.applyRecipeOn(level, stack, recipe, respectChances);
		for (int i = 0; i < results.size(); i++) {
			ItemStack result = results.get(i);
			ItemStack transferred = TricksterManaAccess.applyKnotTransfer(level, inputCopy, result);
			if (transferred != result) {
				results.set(i, transferred);
			}
		}
		return results;
	}
}
