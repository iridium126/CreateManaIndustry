package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import com.iridium126.createtricks.CreateTricks;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = CreateTricks.MODID)
public final class CogwheelChainServerEvents {

	private CogwheelChainServerEvents() {}

	@SubscribeEvent
	public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			CogwheelChainPayloads.syncToPlayer(player);
		}
	}

	@SubscribeEvent
	public static void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			CogwheelChainPayloads.syncToPlayer(player);
		}
	}
}
