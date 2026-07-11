package com.iridium126.createmanaindustry.mixin;

import at.petrak.hexcasting.api.mod.HexConfig;
import at.petrak.hexcasting.common.lib.HexItems;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dynamically recalculates fluid output amounts for {@code create:compacting}
 * recipes that convert Hexcasting media items into Liquid Media.
 * <p>
 * Recipe JSONs contain fluid amounts calculated with default config values.
 * This mixin replaces those amounts at runtime using the actual current values
 * of {@link HexConfig.CommonConfigAccess#dustMediaAmount()},
 * {@link HexConfig.CommonConfigAccess#shardMediaAmount()},
 * {@link HexConfig.CommonConfigAccess#chargedCrystalMediaAmount()},
 * and {@link com.iridium126.createmanaindustry.config.Config#mediaPerBucket}.
 */
@Mixin(value = ProcessingRecipe.class, remap = false)
public class ProcessingRecipeFluidMixin {

    @Inject(method = "getFluidResults", at = @At("RETURN"), cancellable = true)
    private void modifyMediaFluidResults(CallbackInfoReturnable<NonNullList<FluidStack>> cir) {
        NonNullList<FluidStack> original = cir.getReturnValue();

        // Only intercept recipes producing liquid_media
        boolean hasLiquidMedia = false;
        for (FluidStack fs : original) {
            if (!fs.isEmpty() && fs.getFluid().isSame(CMIFluids.LIQUID_MEDIA.get())) {
                hasLiquidMedia = true;
                break;
            }
        }
        if (!hasLiquidMedia)
            return;

        ProcessingRecipe<?, ?> recipe = (ProcessingRecipe<?, ?>) (Object) this;
        long mediaAmount = getMediaAmountFromIngredients(recipe);
        if (mediaAmount <= 0)
            return;

        int newAmount = CMIFluidConversions.mediaToFluidAmount(mediaAmount);
        if (newAmount <= 0)
            return;

        NonNullList<FluidStack> modified = NonNullList.create();
        for (FluidStack fs : original) {
            if (!fs.isEmpty() && fs.getFluid().isSame(CMIFluids.LIQUID_MEDIA.get())) {
                modified.add(new FluidStack(CMIFluids.LIQUID_MEDIA.get(), newAmount));
            } else {
                modified.add(fs.copy());
            }
        }
        cir.setReturnValue(modified);
    }

    /**
     * Inspects the recipe's item ingredients to determine which Hexcasting media
     * item is being processed, then returns its media value from the current
     * Hexcasting config.
     *
     * @return the media amount from config, or 0 if no recognized media item is found
     */
    private static long getMediaAmountFromIngredients(ProcessingRecipe<?, ?> recipe) {
        HexConfig.CommonConfigAccess config = HexConfig.common();
        if (config == null)
            return 0;

        for (Ingredient ingredient : recipe.getIngredients()) {
            for (ItemStack stack : ingredient.getItems()) {
                Item item = stack.getItem();
                if (item == HexItems.AMETHYST_DUST)
                    return config.dustMediaAmount();
                if (item == Items.AMETHYST_SHARD)
                    return config.shardMediaAmount();
                if (item == HexItems.CHARGED_AMETHYST)
                    return config.chargedCrystalMediaAmount();
            }
        }
        return 0;
    }
}
