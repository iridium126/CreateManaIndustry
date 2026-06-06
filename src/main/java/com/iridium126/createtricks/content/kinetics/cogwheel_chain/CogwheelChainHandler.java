package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.ArrayList;
import java.util.List;

import com.iridium126.createtricks.CreateTricks;
import com.simibubi.create.AllItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = CreateTricks.MODID, value = Dist.CLIENT)
public final class CogwheelChainHandler {

	private static final int MAX_CHAIN_DISTANCE = 32;

	private static final List<CogwheelChainNode> buildingNodes = new ArrayList<>();
	private static boolean isBuilding = false;

	private CogwheelChainHandler() {}

	public static boolean isBuilding() {
		return isBuilding;
	}

	public static List<CogwheelChainNode> getBuildingNodes() {
		return buildingNodes;
	}

	@SubscribeEvent
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		if (event.getEntity() != Minecraft.getInstance().player)
			return;

		LocalPlayer player = (LocalPlayer) event.getEntity();
		Level level = player.level();
		BlockPos pos = event.getPos();

		// Wrench + sneak: remove chain
		if (player.isShiftKeyDown() && isWrench(player)) {
			List<CogwheelChainData> chains = CogwheelChainClientData.getChainsAt(pos);
			if (!chains.isEmpty()) {
				PacketDistributor.sendToServer(
					new CogwheelChainPayloads.BreakChainPayload(chains.get(0).getControllerPos()));
				cancelVanillaUse(event);
				cancelBuilding();
				return;
			}
		}

		// Chain item: build chain
		if (!player.getMainHandItem().is(Items.CHAIN))
			return;

		CogwheelChainNode node = CogwheelChainNodes.tryCreate(level, pos, event.getHitVec());
		if (node == null)
			return;

		cancelVanillaUse(event);

		if (!isBuilding) {
			buildingNodes.clear();
			buildingNodes.add(node);
			isBuilding = true;
			player.displayClientMessage(
				Component.translatable("createtricks.cogwheel_chain.started"), true);
			return;
		}

		if (buildingNodes.size() != 1) {
			cancelBuilding();
			return;
		}

		CogwheelChainNode first = buildingNodes.get(0);
		if (!first.canLinkTo(node)) {
			player.displayClientMessage(
				Component.translatable("createtricks.cogwheel_chain.invalid_pair"), true);
			return;
		}

		// Check distance
		if (first.pos().distManhattan(node.pos()) > MAX_CHAIN_DISTANCE) {
			player.displayClientMessage(
				Component.translatable("createtricks.cogwheel_chain.too_far"), true);
			return;
		}

		if (!CogwheelChainClientData.getChainsAt(first.pos()).isEmpty()
			|| !CogwheelChainClientData.getChainsAt(node.pos()).isEmpty()) {
			player.displayClientMessage(
				Component.translatable("createtricks.cogwheel_chain.already_linked"), true);
			return;
		}

		buildingNodes.add(node);
		player.displayClientMessage(
			Component.translatable("createtricks.cogwheel_chain.placed"), true);
		PacketDistributor.sendToServer(
			new CogwheelChainPayloads.PlaceChainPayload(new ArrayList<>(buildingNodes)));
		cancelBuilding();
	}

	@SubscribeEvent
	public static void onPlayerTick(PlayerTickEvent.Post event) {
		if (!isBuilding)
			return;
		if (!(event.getEntity() instanceof LocalPlayer player))
			return;
		if (Minecraft.getInstance().player != player)
			return;

		// Cancel if player no longer holds chain
		if (!player.getMainHandItem().is(Items.CHAIN)) {
			cancelBuilding();
		}
	}

	public static void cancelBuilding() {
		isBuilding = false;
		buildingNodes.clear();
	}

	private static void cancelVanillaUse(PlayerInteractEvent.RightClickBlock event) {
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}

	private static boolean isWrench(LocalPlayer player) {
		return AllItems.WRENCH.isIn(player.getMainHandItem());
	}
}
