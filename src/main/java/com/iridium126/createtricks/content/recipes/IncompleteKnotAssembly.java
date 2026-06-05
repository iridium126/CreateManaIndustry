package com.iridium126.createtricks.content.recipes;

import java.util.List;

import com.iridium126.createtricks.CreateTricksItems;
import com.iridium126.createtricks.content.fluids.CreateTricksFluidConversions;
import com.iridium126.createtricks.trickster.TricksterReflection;
import com.tterrag.registrate.util.entry.ItemEntry;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

public final class IncompleteKnotAssembly {
	private static final List<KnotAssembly> ASSEMBLIES = List.of(
			new KnotAssembly(CreateTricksItems.INCOMPLETE_EMERALD_KNOT, knot("emerald_knot"), 512),
			new KnotAssembly(CreateTricksItems.INCOMPLETE_DIAMOND_KNOT, knot("diamond_knot"), 8192),
			new KnotAssembly(CreateTricksItems.INCOMPLETE_ECHO_KNOT, knot("echo_knot"), 65536),
			new KnotAssembly(CreateTricksItems.INCOMPLETE_ASTRAL_KNOT, knot("astral_knot"), 524288));

	private IncompleteKnotAssembly() {
	}

	public static int getRequiredFluidAmount(Ingredient ingredient) {
		for (KnotAssembly assembly : ASSEMBLIES) {
			if (ingredient.test(new ItemStack(assembly.incompleteKnot().get())))
				return assembly.requiredFluidAmount();
		}
		return -1;
	}

	private static ResourceLocation knot(String path) {
		return ResourceLocation.fromNamespaceAndPath("trickster", path);
	}

	private record KnotAssembly(ItemEntry<?> incompleteKnot, ResourceLocation knotId, float fallbackCreationCost) {
		private int requiredFluidAmount() {
			Item knotItem = BuiltInRegistries.ITEM.get(knotId);
			if (knotItem == Items.AIR)
				return CreateTricksFluidConversions.manaToFluidAmount(fallbackCreationCost);
			return CreateTricksFluidConversions.manaToFluidAmount(
					TricksterReflection.getCreationCost(knotItem, fallbackCreationCost));
		}
	}
}
