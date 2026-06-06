package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.ArrayList;
import java.util.List;

import com.iridium126.createtricks.CreateTricks;
import com.iridium126.createtricks.content.items.KineticsSpellCoreItem;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = CreateTricks.MODID, value = Dist.CLIENT)
public final class CogwheelChainHandler {

	private static final int MAX_NODES = 64;
	private static final int MAX_CHAIN_DISTANCE = 32;
	private static final String MODULAR_SPELL_CONSTRUCT_BLOCK = "dev.enjarai.trickster.block.ModularSpellConstructBlock";

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
		BlockState state = level.getBlockState(pos);

		// Wrench + sneak: remove chain
		if (player.isShiftKeyDown() && isWrench(player)) {
			List<CogwheelChainData> chains = CogwheelChainClientData.getChainsAt(pos);
			if (!chains.isEmpty()) {
				PacketDistributor.sendToServer(
					new CogwheelChainPayloads.BreakChainPayload(chains.get(0).getControllerPos()));
				event.setCanceled(true);
				cancelBuilding();
				return;
			}
		}

		// Chain item: build chain
		if (!player.getMainHandItem().is(Items.CHAIN))
			return;

		CogwheelChainNode node = tryCreateNode(level, pos, state);
		if (node == null)
			return;

		event.setCanceled(true);

		if (!isBuilding) {
			buildingNodes.clear();
			buildingNodes.add(node);
			isBuilding = true;
			player.displayClientMessage(
				Component.translatable("createtricks.cogwheel_chain.started"), true);
			return;
		}

		// Check if completing loop
		if (buildingNodes.size() >= 2 && node.pos().equals(buildingNodes.get(0).pos())) {
			// Complete the loop - send to server
			PacketDistributor.sendToServer(
				new CogwheelChainPayloads.PlaceChainPayload(new ArrayList<>(buildingNodes)));
			player.displayClientMessage(
				Component.translatable("createtricks.cogwheel_chain.placed"), true);
			cancelBuilding();
			return;
		}

		// Check duplicates
		for (CogwheelChainNode existing : buildingNodes) {
			if (existing.pos().equals(node.pos()))
				return;
		}

		// Check distance
		BlockPos lastPos = buildingNodes.get(buildingNodes.size() - 1).pos();
		if (lastPos.distManhattan(node.pos()) > MAX_CHAIN_DISTANCE) {
			player.displayClientMessage(
				Component.translatable("createtricks.cogwheel_chain.too_far"), true);
			return;
		}

		// Check max nodes
		if (buildingNodes.size() >= MAX_NODES) {
			player.displayClientMessage(
				Component.translatable("createtricks.cogwheel_chain.too_many"), true);
			return;
		}

		buildingNodes.add(node);
		player.displayClientMessage(
			Component.translatable("createtricks.cogwheel_chain.added",
				buildingNodes.size()), true);
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

	private static CogwheelChainNode tryCreateNode(Level level, BlockPos pos, BlockState state) {
		// Check Create cogwheel
		if (ICogWheel.isSmallCog(state) || ICogWheel.isLargeCog(state)) {
			Direction.Axis axis = getAxis(state);
			boolean isLarge = ICogWheel.isLargeCog(state);
			return new CogwheelChainNode(pos, axis, isLarge);
		}

		// Check spell construct with kinetics core
		if (isSpellConstructWithCore(level, pos)) {
			return new CogwheelChainNode(pos, Direction.Axis.Y, false);
		}

		return null;
	}

	private static Direction.Axis getAxis(BlockState state) {
		try {
			return state.getValue(BlockStateProperties.AXIS);
		} catch (IllegalArgumentException e) {
			return Direction.Axis.Y;
		}
	}

	private static boolean isSpellConstructWithCore(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		try {
			if (!Class.forName(MODULAR_SPELL_CONSTRUCT_BLOCK).isInstance(state.getBlock()))
				return false;
		} catch (ClassNotFoundException e) {
			return false;
		}

		BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof Container container))
			return false;

		for (int slot = 1; slot < container.getContainerSize(); slot++) {
			if (KineticsSpellCoreItem.is(container.getItem(slot)))
				return true;
		}
		return false;
	}

	private static boolean isWrench(LocalPlayer player) {
		return AllItems.WRENCH.isIn(player.getMainHandItem());
	}
}
