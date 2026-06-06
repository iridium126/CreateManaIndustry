package com.iridium126.createtricks.content.kinetics.bnb;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;

public final class BnBChainClientEvents {
	private BnBChainClientEvents() {}

	public static void register() {
		NeoForge.EVENT_BUS.register(BnBChainClientEvents.class);
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.screen != null || !event.isUseItem())
			return;
		if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() == HitResult.Type.MISS)
			return;

		LocalPlayer player = minecraft.player;
		Level level = minecraft.level;
		if (player == null || level == null)
			return;

		if (!BnBChainInteractionEvents.trySendPendingCoreLink(player, level, hit.getBlockPos(), event.getHand().ordinal()))
			return;

		event.setSwingHand(true);
		event.setCanceled(true);
	}
}
