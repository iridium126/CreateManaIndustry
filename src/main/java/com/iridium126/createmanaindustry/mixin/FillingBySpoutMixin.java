package com.iridium126.createmanaindustry.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.iridium126.createmanaindustry.content.recipes.IncompleteKnotAssembly;
import com.simibubi.create.content.fluids.spout.FillingBySpout;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

@Mixin(value = FillingBySpout.class, remap = false)
public class FillingBySpoutMixin {

    @Inject(method = "getRequiredAmountForItem", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$useConfiguredIncompleteKnotFluidAmount(Level world, ItemStack stack,
            FluidStack availableFluid, CallbackInfoReturnable<Integer> cir) {
        int requiredAmount = IncompleteKnotAssembly.getRequiredFluidAmount(stack, availableFluid);
        if (requiredAmount >= 0)
            cir.setReturnValue(requiredAmount);
    }

    @Inject(method = "fillItem", at = @At("HEAD"), cancellable = true)
    private static void createmanaindustry$fillIncompleteKnot(Level level, int requiredAmount, ItemStack stack,
            FluidStack availableFluid, CallbackInfoReturnable<ItemStack> cir) {
        ItemStack result = IncompleteKnotAssembly.fillIncompleteKnot(stack);
        if (!result.isEmpty()) {
            availableFluid.shrink(requiredAmount);
            stack.shrink(1);
            cir.setReturnValue(result);
        }
    }
}
