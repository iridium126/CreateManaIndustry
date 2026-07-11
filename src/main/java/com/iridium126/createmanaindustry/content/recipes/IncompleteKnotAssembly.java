package com.iridium126.createmanaindustry.content.recipes;

import java.util.List;

import com.iridium126.createmanaindustry.CMIFluids;
import com.iridium126.createmanaindustry.CMIItems;
import com.iridium126.createmanaindustry.content.fluids.CreateTricksFluidConversions;
import com.iridium126.createmanaindustry.trickster.TricksterManaAccess;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe;
import com.tterrag.registrate.util.entry.ItemEntry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;

public final class IncompleteKnotAssembly {
    private static final List<KnotAssembly> ASSEMBLIES = List.of(
            new KnotAssembly(CMIItems.INCOMPLETE_EMERALD_KNOT, knot("emerald_knot"), 512, 1),
            new KnotAssembly(CMIItems.INCOMPLETE_DIAMOND_KNOT, knot("diamond_knot"), 8192, 4),
            new KnotAssembly(CMIItems.INCOMPLETE_ECHO_KNOT, knot("echo_knot"), 65536, 32),
            new KnotAssembly(CMIItems.INCOMPLETE_ASTRAL_KNOT, knot("astral_knot"), 524288, 256));

    private IncompleteKnotAssembly() {
    }

    public static int getRequiredFluidAmount(ItemStack stack, FluidStack availableFluid) {
        if (stack.isEmpty() || availableFluid.isEmpty()
                || !availableFluid.getFluid().isSame(CMIFluids.LIQUID_MANA.get()))
            return -1;

        for (KnotAssembly assembly : ASSEMBLIES) {
            if (stack.is(assembly.incompleteKnot().get()))
                return assembly.requiredFluidAmount(stack);
        }
        return -1;
    }

    private static ResourceLocation knot(String path) {
        return ResourceLocation.fromNamespaceAndPath("trickster", path);
    }

    private record KnotAssembly(ItemEntry<?> incompleteKnot, ResourceLocation knotId, float fallbackCreationCost,
            int fillingSteps) {
        private int requiredFluidAmount(ItemStack stack) {
            int totalFluidAmount = totalFluidAmount();
            if (totalFluidAmount <= 0)
                return 0;

            int fillingStep = getFillingStep(stack);
            int baseAmount = totalFluidAmount / fillingSteps;
            int extraSteps = totalFluidAmount % fillingSteps;
            return baseAmount + (fillingStep <= extraSteps ? 1 : 0);
        }

        private int totalFluidAmount() {
            Item knotItem = BuiltInRegistries.ITEM.get(knotId);
            if (knotItem == Items.AIR)
                return CreateTricksFluidConversions.manaToFluidAmount(fallbackCreationCost);
            return CreateTricksFluidConversions.manaToFluidAmount(
                    TricksterManaAccess.getCreationCost(knotItem, fallbackCreationCost));
        }

        private int getFillingStep(ItemStack stack) {
            if (!stack.has(AllDataComponents.SEQUENCED_ASSEMBLY))
                return 1;

            SequencedAssemblyRecipe.SequencedAssembly assembly = stack.get(AllDataComponents.SEQUENCED_ASSEMBLY);
            if (assembly == null)
                return 1;

            return Math.max(1, Math.min(fillingSteps, assembly.step()));
        }
    }
}
