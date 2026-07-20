package com.iridium126.createmanaindustry.content.recipes;

import java.util.List;

import com.iridium126.createmanaindustry.CMIComponents;
import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CMIItems;
import com.iridium126.createmanaindustry.config.Config;
import com.iridium126.createmanaindustry.content.fluids.CMIFluidConversions;
import com.iridium126.createmanaindustry.trickster.TricksterManaAccess;
import com.tterrag.registrate.util.entry.ItemEntry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Tracks incomplete knot → final knot mapping and manages the multi-step
 * liquid-mana filling process via the {@code filled_mana} data component.
 * <p>
 * Each incomplete knot stores cumulative Trickster mana already infused
 * ({@link CMIComponents#FILLED_MANA}). When the total reaches the knot's
 * {@code creationCost}, the item is ready to become the final knot.
 */
public final class IncompleteKnotAssembly {

    private static final List<KnotAssembly> ASSEMBLIES = List.of(
            new KnotAssembly(CMIItems.INCOMPLETE_EMERALD_KNOT, knot("emerald_knot"), 512),
            new KnotAssembly(CMIItems.INCOMPLETE_DIAMOND_KNOT, knot("diamond_knot"), 8192),
            new KnotAssembly(CMIItems.INCOMPLETE_ECHO_KNOT, knot("echo_knot"), 65536),
            new KnotAssembly(CMIItems.INCOMPLETE_ASTRAL_KNOT, knot("astral_knot"), 524288),
            new KnotAssembly(CMIItems.INCOMPLETE_PRISMATIC_KNOT, knot("prismatic_knot"), 8192));

    private IncompleteKnotAssembly() {}

    // ---- public: fluid amount ------------------------------------------------

    /**
     * Returns the mB of liquid_mana to consume for the next fill operation on
     * this incomplete knot, capped at the Spout's per-operation limit (1 bucket).
     *
     * @return the required fluid amount in mB, or -1 if this is not an
     *         incomplete knot or the knot is already fully charged
     */
    public static int getRequiredFluidAmount(ItemStack stack, FluidStack availableFluid) {
        if (stack.isEmpty() || availableFluid.isEmpty()
                || !availableFluid.getFluid().isSame(CMIFluids.LIQUID_MANA.get()))
            return -1;

        KnotAssembly assembly = findAssembly(stack);
        if (assembly == null)
            return -1;

        float creationCost = resolveCreationCost(assembly);
        float currentProgress = getFilledMana(stack);
        float remaining = creationCost - currentProgress;
        if (remaining <= 0)
            return -1;

        float toAdd = Math.min(remaining, Config.manaPerBucket);
        return CMIFluidConversions.manaToFluidAmount(toAdd);
    }

    // ---- public: fill operation ----------------------------------------------

    /**
     * Performs one fill step on an incomplete knot, consuming liquid mana and
     * advancing the {@code filled_mana} component. Returns the resulting stack:
     * either the incomplete knot with updated progress, or the final knot item
     * when the creation cost is reached.
     *
     * @param stack the incomplete knot being filled (not modified in place)
     * @return the result stack (incomplete knot or final knot), or
     *         {@link ItemStack#EMPTY} if this is not a valid incomplete knot
     */
    public static ItemStack fillIncompleteKnot(ItemStack stack) {
        KnotAssembly assembly = findAssembly(stack);
        if (assembly == null)
            return ItemStack.EMPTY;

        float creationCost = resolveCreationCost(assembly);
        float currentProgress = getFilledMana(stack);
        float remaining = creationCost - currentProgress;
        if (remaining <= 0)
            return ItemStack.EMPTY;

        float toAdd = Math.min(remaining, Config.manaPerBucket);
        float newProgress = currentProgress + toAdd;

        if (newProgress >= creationCost) {
            Item knotItem = BuiltInRegistries.ITEM.get(assembly.knotId());
            if (knotItem == Items.AIR)
                return ItemStack.EMPTY;
            return new ItemStack(knotItem);
        }

        ItemStack result = stack.copy();
        result.set(CMIComponents.FILLED_MANA.get(), newProgress);
        return result;
    }

    // ---- private helpers -----------------------------------------------------

    private static float getFilledMana(ItemStack stack) {
        Float value = stack.get(CMIComponents.FILLED_MANA.get());
        return value != null ? value : 0f;
    }

    private static KnotAssembly findAssembly(ItemStack stack) {
        if (stack.isEmpty())
            return null;
        for (KnotAssembly assembly : ASSEMBLIES) {
            if (stack.is(assembly.incompleteKnot().get()))
                return assembly;
        }
        return null;
    }

    private static float resolveCreationCost(KnotAssembly assembly) {
        Item knotItem = BuiltInRegistries.ITEM.get(assembly.knotId());
        if (knotItem == Items.AIR)
            return assembly.fallbackCreationCost();
        return TricksterManaAccess.getCreationCost(knotItem, assembly.fallbackCreationCost());
    }

    private static ResourceLocation knot(String path) {
        return ResourceLocation.fromNamespaceAndPath("trickster", path);
    }

    private record KnotAssembly(ItemEntry<?> incompleteKnot, ResourceLocation knotId, float fallbackCreationCost) {}
}
