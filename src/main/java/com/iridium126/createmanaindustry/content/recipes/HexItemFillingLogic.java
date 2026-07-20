package com.iridium126.createmanaindustry.content.recipes;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.iridium126.createmanaindustry.content.items.CMIHexItems;
import com.iridium126.createmanaindustry.content.items.IncompleteHexItem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Manages the multi-step liquid_media filling process for incomplete hex items
 * via Hexcasting's {@code MEDIA} data component.
 * <p>
 * Each incomplete hex item tracks accumulated media directly in
 * {@link at.petrak.hexcasting.common.lib.HexDataComponents#MEDIA}. Filling adds
 * media up to the per-item-type maximum defined in {@link Config}.
 */
public final class HexItemFillingLogic {

    private HexItemFillingLogic() {}

    /**
     * Resolves the corresponding {@link IncompleteHexItem} for a given stack.
     *
     * @return the incomplete hex item type, or {@code null} if the stack is not
     *         a recognised hex item (fresh-crafted or incomplete)
     */
    private static IncompleteHexItem resolve(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof IncompleteHexItem hexItem)
            return hexItem;

        // Check if it's a fresh-crafted hexcasting item
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (!"hexcasting".equals(id.getNamespace()))
            return null;

        String path = id.getPath();
        if ("cypher".equals(path) && CMIHexItems.INCOMPLETE_CYPHER != null)
            return CMIHexItems.INCOMPLETE_CYPHER.get();
        if ("trinket".equals(path) && CMIHexItems.INCOMPLETE_TRINKET != null)
            return CMIHexItems.INCOMPLETE_TRINKET.get();
        if ("artifact".equals(path) && CMIHexItems.INCOMPLETE_ARTIFACT != null)
            return CMIHexItems.INCOMPLETE_ARTIFACT.get();

        return null;
    }

    // ---- fluid amount ------------------------------------------------------

    /**
     * Returns the mB of liquid_media to consume for the next fill operation,
     * capped at the Spout's per-operation limit.
     *
     * @return the required fluid amount in mB, or -1 if this is not a
     *         recognised item or the item is already at max capacity
     */
    public static int getRequiredFluidAmount(ItemStack stack, FluidStack availableFluid) {
        if (stack.isEmpty() || availableFluid.isEmpty()
                || !availableFluid.getFluid().isSame(CMIFluids.LIQUID_MEDIA.get()))
            return -1;

        IncompleteHexItem hexItem = resolve(stack);
        if (hexItem == null)
            return -1;

        long maxMedia = hexItem.getMaxMedia(stack);
        long currentMedia = hexItem.getMedia(stack);
        long remaining = maxMedia - currentMedia;
        if (remaining <= 0)
            return -1;

        // Cap the media added per operation to what one bucket provides
        long maxPerOp = Config.mediaPerBucket;
        long toAdd = Math.min(remaining, maxPerOp);
        return CMIFluidConversions.mediaToFluidAmount(toAdd);
    }

    // ---- fill operation ----------------------------------------------------

    /**
     * Performs one fill step, adding media to the incomplete hex item.
     * <p>
     * If the input is a fresh-crafted hexcasting item (not yet incomplete),
     * it is converted to the corresponding incomplete item with
     * {@code MEDIA_MAX} set from config before media is added.
     *
     * @param stack the item being filled (not modified in place)
     * @return the result stack, or {@link ItemStack#EMPTY} if invalid
     */
    public static ItemStack fillIncompleteHexItem(ItemStack stack, int fluidAmount) {
        IncompleteHexItem hexItem = resolve(stack);
        if (hexItem == null)
            return ItemStack.EMPTY;

        long mediaToAdd = CMIFluidConversions.fluidAmountToMedia(fluidAmount);
        if (mediaToAdd <= 0)
            return ItemStack.EMPTY;

        ItemStack result;
        if (stack.getItem() instanceof IncompleteHexItem) {
            // Already incomplete — copy and add media
            result = stack.copy();
        } else {
            // Fresh hexcasting item — create incomplete copy with default max media.
            // Fresh-crafted hex items carry no useful data to preserve (no patterns, no media).
            result = new ItemStack(hexItem);
            hexItem.ensureMaxMedia(result);
        }

        long currentMedia = hexItem.getMedia(result);
        long maxMedia = hexItem.getMaxMedia(result);
        long newMedia = Math.min(currentMedia + mediaToAdd, maxMedia);
        hexItem.setMedia(result, newMedia);

        return result;
    }

    /**
     * Checks whether the given stack is a recognised hex item
     * (fresh-crafted or incomplete).
     */
    public static boolean isRecognised(ItemStack stack) {
        return resolve(stack) != null;
    }
}
