package com.iridium126.createmanaindustry.mixin;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.iridium126.createmanaindustry.hexcasting.HexCompat;
import com.simibubi.create.content.processing.recipe.ProcessingRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
 * of the Hexcasting media config and {@code mediaPerBucket}.
 * <p>
 * Items are identified via {@link BuiltInRegistries#ITEM} lookups rather than
 * direct Hexcasting class references, so the mixin loads safely even when
 * Hexcasting is absent. Config values come from {@link HexCompat}, which
 * provides safe fallback defaults.
 */
@Mixin(value = ProcessingRecipe.class, remap = false)
public class ProcessingRecipeFluidMixin {

    private static final ResourceLocation AMETHYST_DUST_ID =
            ResourceLocation.fromNamespaceAndPath("hexcasting", "amethyst_dust");
    private static final ResourceLocation CHARGED_AMETHYST_ID =
            ResourceLocation.fromNamespaceAndPath("hexcasting", "charged_amethyst");

    // Fallback media amounts when Hexcasting is absent
    private static final long FALLBACK_DUST_MEDIA = 10000L;
    private static final long FALLBACK_SHARD_MEDIA = 50000L;
    private static final long FALLBACK_CHARGED_MEDIA = 100000L;

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
     * Inspects the recipe's item ingredients to determine which media item is
     * being processed. Uses registry-name lookups (not Hexcasting class
     * references) so the mixin loads safely when Hexcasting is absent.
     *
     * @return the media amount for the recognised item, or 0 if none matches
     */
    private static long getMediaAmountFromIngredients(ProcessingRecipe<?, ?> recipe) {
        Item amethystDust = BuiltInRegistries.ITEM.get(AMETHYST_DUST_ID);
        Item chargedAmethyst = BuiltInRegistries.ITEM.get(CHARGED_AMETHYST_ID);

        for (Ingredient ingredient : recipe.getIngredients()) {
            for (ItemStack stack : ingredient.getItems()) {
                Item item = stack.getItem();
                if (amethystDust != Items.AIR && item == amethystDust)
                    return CreateManaIndustry.HEX_ACTIVE
                            ? HexCompat.getDustMediaAmount()
                            : FALLBACK_DUST_MEDIA;
                if (item == Items.AMETHYST_SHARD)
                    return CreateManaIndustry.HEX_ACTIVE
                            ? HexCompat.getShardMediaAmount()
                            : FALLBACK_SHARD_MEDIA;
                if (chargedAmethyst != Items.AIR && item == chargedAmethyst)
                    return CreateManaIndustry.HEX_ACTIVE
                            ? HexCompat.getChargedCrystalMediaAmount()
                            : FALLBACK_CHARGED_MEDIA;
            }
        }
        return 0;
    }
}
