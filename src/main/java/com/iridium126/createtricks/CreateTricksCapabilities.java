package com.iridium126.createtricks;

import com.iridium126.createtricks.content.fluids.TricksterKnotFluidHandler;
import com.iridium126.createtricks.content.fluids.SpellInkFluidHandler;
import com.iridium126.createtricks.content.items.TricksterKnotItemHandler;
import com.iridium126.createtricks.trickster.TricksterReflection;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
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

		registerTricksterKnotItemHandler(event, "charging_array", TricksterKnotItemHandler.Mode.ALL_SLOTS);
		registerTricksterKnotItemHandler(event, "spell_construct", TricksterKnotItemHandler.Mode.FIRST_SLOT);
		registerTricksterKnotItemHandler(event, "modular_spell_construct", TricksterKnotItemHandler.Mode.FIRST_SLOT);
	}

	private static void registerTricksterKnotItemHandler(RegisterCapabilitiesEvent event, String path,
			TricksterKnotItemHandler.Mode mode) {
		ResourceLocation id = ResourceLocation.fromNamespaceAndPath("trickster", path);
		BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id);
		if (type != null)
			registerTricksterKnotItemHandler(event, type, mode);
	}

	@SuppressWarnings("unchecked")
	private static void registerTricksterKnotItemHandler(RegisterCapabilitiesEvent event, BlockEntityType<?> type,
			TricksterKnotItemHandler.Mode mode) {
		BlockEntityType<BlockEntity> blockEntityType = (BlockEntityType<BlockEntity>) type;
		event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, blockEntityType,
				(blockEntity, side) -> TricksterKnotItemHandler.create(blockEntity, mode));
	}
}
