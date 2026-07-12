package com.iridium126.createmanaindustry.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.CMIRecipeTypes;
import com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity;

import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

@Mixin(value = MechanicalPressBlockEntity.class, remap = false)
public class MechanicalPressHeatedCompactingMixin {

    @Inject(method = "matchStaticFilters", at = @At("RETURN"), cancellable = true)
    private void createmanaindustry$matchHeatedCompacting(RecipeHolder<? extends Recipe<?>> recipe,
            CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()
                && recipe.value().getType() == CMIRecipeTypes.HEATED_COMPACTING.getType()) {
            cir.setReturnValue(true);
        }
    }
}
