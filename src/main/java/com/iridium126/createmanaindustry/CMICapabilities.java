package com.iridium126.createmanaindustry;

import net.minecraft.resources.ResourceLocation;

import com.iridium126.createmanaindustry.content.fluids.EsotericManaFluidHandler;
import com.iridium126.createmanaindustry.content.fluids.TricksterKnotFluidHandler;
import com.iridium126.createmanaindustry.content.items.TricksterKnotItemHandler;
import com.iridium126.createmanaindustry.content.kinetics.kineticatomizer.KineticAtomizerBlockEntity;
import com.iridium126.createmanaindustry.trickster.TricksterKnotUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class CMICapabilities {
	private CMICapabilities() {}

	public static void register(RegisterCapabilitiesEvent event) {
		// Kinetic Atomizer fluid handler — only accepts input from the bottom face.
		event.registerBlockEntity(
				Capabilities.FluidHandler.BLOCK,
				CMIBlockEntityTypes.KINETIC_ATOMIZER.get(),
				(be, side) -> ((KineticAtomizerBlockEntity) be).getFluidHandler(side));

		if (!CreateManaIndustry.TRICKSTER_ACTIVE)
			return;

		Item esotericMana = BuiltInRegistries.ITEM.get(EsotericManaFluidHandler.ESOTERIC_MANA_ID);
		if (esotericMana == Items.AIR)
			esotericMana = null;

		if (esotericMana != null)
			event.registerItem(Capabilities.FluidHandler.ITEM, (stack, ctx) -> new EsotericManaFluidHandler(stack),
					esotericMana);

		Item[] knotItems = BuiltInRegistries.ITEM.stream()
				.filter(TricksterKnotUtils::isKnotItem)
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
