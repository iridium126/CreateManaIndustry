package com.iridium126.createmanaindustry.content.recipes;

import at.petrak.hexcasting.api.casting.iota.Iota;
import at.petrak.hexcasting.api.item.HexHolderItem;
import at.petrak.hexcasting.api.item.IotaHolderItem;
import at.petrak.hexcasting.api.pigment.FrozenPigment;
import at.petrak.hexcasting.common.lib.HexDataComponents;

import com.iridium126.createmanaindustry.content.items.IncompleteHexItem;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared data-transfer helpers for the incomplete-hex-item pipeline.
 * <p>
 * Used by the MechanicalPress mixin (convert incomplete → final)
 * and the BeltDeployer mixin (append Iota from held item).
 */
public final class HexItemDataTransfer {

    private HexItemDataTransfer() {}

    // ---- Press: incomplete → final -----------------------------------------

    /**
     * Transfers accumulated hex data from an incomplete hex item to a
     * freshly-pressed final Hexcasting item.
     *
     * @param level the current level (may be needed for context)
     * @param input the incomplete hex item being pressed
     * @param output the recipe result (bare final item)
     * @return the output stack with hex data applied, or the original output
     *         if the input was not an incomplete hex item
     */
    public static ItemStack applyPressTransfer(Level level, ItemStack input, ItemStack output) {
        if (!(input.getItem() instanceof IncompleteHexItem hexItem))
            return output;

        if (!(output.getItem() instanceof HexHolderItem outputHolder))
            return output;

        List<Iota> patterns = input.get(HexDataComponents.HEX_HOLDER_PATTERNS);
        FrozenPigment pigment = input.get(HexDataComponents.PIGMENT);
        long media = hexItem.getMedia(input);

        if (patterns != null && !patterns.isEmpty()) {
            outputHolder.writeHex(output, patterns, pigment, media);
        } else if (media > 0) {
            // No patterns but has media — still transfer the media
            outputHolder.writeHex(output, List.of(), pigment, media);
        }

        return output;
    }

    // ---- Deployer: append Iota ---------------------------------------------

    /**
     * Appends the Iota from a held IotaHolderItem to the incomplete hex item
     * produced by the deployer recipe.
     * <p>
     * Preserves all existing data (patterns, media) from the input stack
     * and adds the new Iota from the held item.
     *
     * @param recipeOutput the bare recipe result (before data transfer)
     * @param beltInput the original item on the belt before processing
     * @param heldItem the item held by the deployer (checks IotaHolderItem)
     * @return the recipe result with all input data preserved and Iota appended
     */
    public static ItemStack applyDeployerIotaAppend(ItemStack recipeOutput, ItemStack beltInput,
                                                    ItemStack heldItem) {
        if (!(recipeOutput.getItem() instanceof IncompleteHexItem hexItem))
            return recipeOutput;

        // Copy all relevant data from the belt input to the recipe output
        copyHexData(beltInput, recipeOutput);

        // Ensure max media is set (for fresh → incomplete transitions)
        hexItem.ensureMaxMedia(recipeOutput);

        // Append Iota from the held item
        if (heldItem.getItem() instanceof IotaHolderItem iotaHolder) {
            Iota iota = iotaHolder.readIota(heldItem);
            if (iota != null) {
                List<Iota> existing = recipeOutput
                        .getOrDefault(HexDataComponents.HEX_HOLDER_PATTERNS, List.of());
                List<Iota> updated = new ArrayList<>(existing);
                updated.add(iota);
                recipeOutput.set(HexDataComponents.HEX_HOLDER_PATTERNS, updated);
            }
        }

        return recipeOutput;
    }

    // ---- internal ----------------------------------------------------------

    /**
     * Copies hex-related data components from source to target, preserving
     * accumulated patterns, media, and pigment across recipe processing.
     */
    private static void copyHexData(ItemStack source, ItemStack target) {
        // Copy hex patterns
        List<Iota> patterns = source.get(HexDataComponents.HEX_HOLDER_PATTERNS);
        if (patterns != null) {
            target.set(HexDataComponents.HEX_HOLDER_PATTERNS, patterns);
        }

        // Copy media
        Long media = source.get(HexDataComponents.MEDIA);
        if (media != null) {
            target.set(HexDataComponents.MEDIA, media);
        }

        // Copy max media
        Long maxMedia = source.get(HexDataComponents.MEDIA_MAX);
        if (maxMedia != null) {
            target.set(HexDataComponents.MEDIA_MAX, maxMedia);
        }

        // Copy pigment
        FrozenPigment pigment = source.get(HexDataComponents.PIGMENT);
        if (pigment != null) {
            target.set(HexDataComponents.PIGMENT, pigment);
        }
    }
}
