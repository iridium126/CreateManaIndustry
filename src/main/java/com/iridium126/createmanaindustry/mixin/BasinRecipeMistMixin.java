package com.iridium126.createmanaindustry.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.fluids.mist.MistFieldStore;
import com.iridium126.createmanaindustry.content.recipes.MistCompactingRecipe;
import com.iridium126.createmanaindustry.content.recipes.MistRequirement;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.content.processing.basin.BasinRecipe;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

/**
 * Adds mist_requirement check to basin recipe matching — if the recipe
 * requires a specific mist type, it only matches when that mist is present
 * at the basin position.
 */
@Mixin(value = BasinRecipe.class, remap = false)
public class BasinRecipeMistMixin {

    @Inject(method = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z",
            at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$checkMistRequirement(BasinBlockEntity basin, Recipe<?> recipe,
            boolean test, CallbackInfoReturnable<Boolean> cir) {
        if (!(recipe instanceof MistCompactingRecipe mistRecipe))
            return;

        MistRequirement req = mistRecipe.getMistRequirement();
        if (req == null)
            return;

        ResourceLocation presentFluid = MistFieldStore.getFluidType(basin.getLevel(), basin.getBlockPos());
        float conc = MistFieldStore.getConcentration(basin.getLevel(), basin.getBlockPos());

        if (!req.fluidId().equals(presentFluid) || conc < req.minConcentration()) {
            cir.setReturnValue(false);
        }
    }
}
