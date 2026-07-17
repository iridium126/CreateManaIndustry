package com.iridium126.createmanaindustry.trickster;

import dev.enjarai.trickster.block.ChargingArrayBlockEntity;
import dev.enjarai.trickster.block.ModularSpellConstructBlockEntity;
import dev.enjarai.trickster.block.SpellConstructBlockEntity;
import dev.enjarai.trickster.item.KnotItem;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Utility methods for identifying Trickster knot items and block entities.
 * <p>
 * All type checks use direct {@code instanceof} — no reflection.
 */
public final class TricksterKnotUtils {

    private TricksterKnotUtils() {}

    // ---- knot item checks ------------------------------------------------

    public static boolean isKnotItem(Item item) {
        return item instanceof KnotItem;
    }

    public static boolean isKnotStack(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof KnotItem;
    }

    // ---- block entity checks ---------------------------------------------

    public static boolean isTricksterKnotBlockEntity(BlockEntity be) {
        if (be == null)
            return false;
        return be instanceof ChargingArrayBlockEntity
                || be instanceof SpellConstructBlockEntity
                || be instanceof ModularSpellConstructBlockEntity;
    }

    public static boolean isSpellConstructBlockEntity(BlockEntity be) {
        return be instanceof SpellConstructBlockEntity;
    }

    public static boolean isModularSpellConstructBlockEntity(BlockEntity be) {
        return be instanceof ModularSpellConstructBlockEntity;
    }
}
