package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import com.iridium126.createtricks.CreateTricks;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber(modid = CreateTricks.MODID)
public final class CogwheelChainUseEvents {

	private CogwheelChainUseEvents() {}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.getLevel().isClientSide())
			return;
		if (!event.getEntity().getMainHandItem().is(Items.CHAIN))
			return;
		if (!CogwheelChainNodes.isValidInteractTarget(event.getLevel(), event.getPos()))
			return;

		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}
}
