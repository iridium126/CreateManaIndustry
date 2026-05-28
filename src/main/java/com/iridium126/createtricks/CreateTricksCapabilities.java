package com.iridium126.createtricks;

import com.iridium126.createtricks.content.fluids.TricksterKnotFluidHandler;
import com.iridium126.createtricks.content.fluids.SpellInkFluidHandler;
import com.iridium126.createtricks.trickster.TricksterReflection;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class CreateTricksCapabilities {
	private CreateTricksCapabilities() {}

	public static void register(RegisterCapabilitiesEvent event) {
		Item spellInk = BuiltInRegistries.ITEM.get(SpellInkFluidHandler.SPELL_INK_ID);
		if (spellInk == Items.AIR)
			spellInk = null;

		if (spellInk != null)
			event.registerItem(Capabilities.FluidHandler.ITEM, (stack, ctx) -> new SpellInkFluidHandler(stack), spellInk);

		Item[] knotItems = BuiltInRegistries.ITEM.stream()
				.filter(TricksterReflection::isKnotItem)
				.toArray(Item[]::new);
		if (knotItems.length > 0)
			event.registerItem(Capabilities.FluidHandler.ITEM, (stack, ctx) -> new TricksterKnotFluidHandler(stack), knotItems);
	}
}
