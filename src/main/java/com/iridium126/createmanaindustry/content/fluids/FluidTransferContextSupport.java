package com.iridium126.createmanaindustry.content.fluids;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

public final class FluidTransferContextSupport {
    private FluidTransferContextSupport() {}

    public static void captureLevel(Level level) {
        CMIFluidTransferContext.setLevel(level);
    }

    public static void clearLevel() {
        CMIFluidTransferContext.clear();
    }

    public static int getEsotericManaRequiredAmount(ItemStack stack, FluidStack availableFluid) {
        return EsotericManaFluidHandler.getRequiredAmountForFilling(stack, availableFluid);
    }

    public static ItemStack fillEsotericMana(ItemStack stack, FluidStack availableFluid) {
        return EsotericManaFluidHandler.fillItem(stack, availableFluid);
    }
}
