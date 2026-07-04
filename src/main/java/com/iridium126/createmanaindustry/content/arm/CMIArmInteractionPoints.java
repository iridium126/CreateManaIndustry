package com.iridium126.createmanaindustry.content.arm;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.simibubi.create.api.registry.CreateRegistries;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.registries.RegisterEvent;

@EventBusSubscriber(modid = CreateManaIndustry.MODID)
public final class CMIArmInteractionPoints {
	private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,
			"trickster_knot");

	private CMIArmInteractionPoints() {}

	@SubscribeEvent
	public static void registerArmInteractionPointType(RegisterEvent event) {
		if (!CreateManaIndustry.TRICKSTER_ACTIVE)
			return;
		if (!event.getRegistryKey().equals(CreateRegistries.ARM_INTERACTION_POINT_TYPE))
			return;

		event.register(CreateRegistries.ARM_INTERACTION_POINT_TYPE, ID,
				TricksterKnotArmInteractionPointType::new);
	}
}
