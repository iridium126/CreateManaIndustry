package com.iridium126.createtricks.content.kinetics.bnb;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.iridium126.createtricks.CreateTricks;
import com.iridium126.createtricks.content.kinetics.bnb.BnBKineticsCoreNodes.KineticsCoreCogwheelNode;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class BnBChainInteractionEvents {
	private static final int MAX_CHAIN_DISTANCE = 32;
	private static final Map<UUID, KineticsCoreCogwheelNode> PENDING_CORES = new ConcurrentHashMap<>();

	private BnBChainInteractionEvents() {}

	public static boolean isAvailable() {
		return BnBReflect.isAvailable();
	}

	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		Player player = event.getEntity();
		Level level = event.getLevel();

		if (!player.getItemInHand(event.getHand()).is(Items.CHAIN))
			return;

		KineticsCoreCogwheelNode core = BnBKineticsCoreNodes.tryCreate(level, event.getPos(), event.getHitVec());
		if (core != null) {
			cancelVanillaUse(event);
			PENDING_CORES.put(player.getUUID(), core);
			if (level.isClientSide()) {
				player.displayClientMessage(Component.translatable("createtricks.bnb_chain.started"), true);
			}
			return;
		}

		KineticsCoreCogwheelNode pendingCore = PENDING_CORES.get(player.getUUID());
		if (pendingCore == null)
			return;

		if (!BnBReflect.isValidLinkTarget(level, event.getPos()))
			return;

		cancelVanillaUse(event);
		PENDING_CORES.remove(player.getUUID());
		if (level.isClientSide()) {
			PacketDistributor.sendToServer(new BnBChainPayloads.LinkCorePayload(
				pendingCore.pos(), pendingCore.slot(), event.getPos(), event.getHand().ordinal()));
			return;
		}

		if (pendingCore.pos().distManhattan(event.getPos()) > MAX_CHAIN_DISTANCE) {
			player.displayClientMessage(Component.translatable("createtricks.bnb_chain.too_far"), true);
			return;
		}

		if (!placeOrExtendBnBChain(level, event.getPos(), pendingCore, player)) {
			player.displayClientMessage(Component.translatable("createtricks.bnb_chain.invalid_pair"), true);
			return;
		}

		if (!player.hasInfiniteMaterials())
			player.getItemInHand(event.getHand()).shrink(1);
		player.displayClientMessage(Component.translatable("createtricks.bnb_chain.placed"), true);
	}

	static void placeFromClient(ServerPlayer player, BlockPos corePos, int slot, BlockPos targetPos, InteractionHand hand) {
		Level level = player.level();
		if (!player.getItemInHand(hand).is(Items.CHAIN))
			return;

		KineticsCoreCogwheelNode core = BnBKineticsCoreNodes.create(level, corePos, slot);
		if (core == null)
			return;

		if (core.pos().distManhattan(targetPos) > MAX_CHAIN_DISTANCE) {
			player.displayClientMessage(Component.translatable("createtricks.bnb_chain.too_far"), true);
			return;
		}

		if (!BnBReflect.isValidLinkTarget(level, targetPos)) {
			player.displayClientMessage(Component.translatable("createtricks.bnb_chain.invalid_pair"), true);
			return;
		}

		if (!placeOrExtendBnBChain(level, targetPos, core, player)) {
			player.displayClientMessage(Component.translatable("createtricks.bnb_chain.invalid_pair"), true);
			return;
		}

		if (!player.hasInfiniteMaterials())
			player.getItemInHand(hand).shrink(1);
		player.displayClientMessage(Component.translatable("createtricks.bnb_chain.placed"), true);
	}

	static boolean trySendPendingCoreLink(Player player, Level level, BlockPos targetPos, int handOrdinal) {
		KineticsCoreCogwheelNode pendingCore = PENDING_CORES.get(player.getUUID());
		if (pendingCore == null)
			return false;
		if (!BnBReflect.isValidLinkTarget(level, targetPos))
			return false;

		PENDING_CORES.remove(player.getUUID());
		PacketDistributor.sendToServer(new BnBChainPayloads.LinkCorePayload(
			pendingCore.pos(), pendingCore.slot(), targetPos, handOrdinal));
		return true;
	}

	private static boolean placeOrExtendBnBChain(Level level, BlockPos cogwheelPos, KineticsCoreCogwheelNode core, Player player) {
		try {
			BlockEntity be = BnBReflect.getOrCreateChainBlockEntity(level, cogwheelPos);
			if (be == null)
				return false;

			if (BnBReflect.countCoreLinks(level, core.pos()) >= BnBKineticsCoreNodes.getKineticsCoreCount(level, core.pos())) {
				player.displayClientMessage(Component.translatable("createtricks.bnb_chain.already_linked"), true);
				return false;
			}

			Object controllerBE = BnBReflect.resolveController(level, cogwheelPos, be);
			BlockPos controllerPos = (controllerBE == be) ? cogwheelPos : BnBReflect.getControllerWorldPos(cogwheelPos, be);

			if (controllerBE != null && BnBReflect.isController(controllerBE)) {
				Object existingChain = BnBReflect.getChain(controllerBE);
				if (existingChain == null)
					return false;
				if (BnBReflect.containsWorldPosition(controllerPos, existingChain, core.pos()))
					return false;

				List<Object> nodes = new ArrayList<>(BnBReflect.getChainPathCogwheelNodes(existingChain));
				int insertAfter = BnBReflect.findNodeIndex(controllerPos, nodes, cogwheelPos);
				if (insertAfter == -1)
					return false;

				nodes.add(insertAfter + 1, BnBReflect.newPathedNode(
					BnBReflect.side(nodes.get(insertAfter)) * -1,
					false,
					core.rotationAxis(),
					core.pos().subtract(controllerPos),
					false));

				Object extendedChain = BnBReflect.newCogwheelChain(nodes);
				BnBReflect.setAsController(controllerBE, extendedChain);
				BnBReflect.setChainsUsed(controllerBE, BnBReflect.getChainsRequired(extendedChain));
				BnBReflect.sendData(controllerBE);
				return true;
			}

			return createTwoNodeChain(level, cogwheelPos, core, be);
		} catch (ReflectiveOperationException | RuntimeException e) {
			CreateTricks.LOGGER.warn("Failed to link kinetics core into BnB cogwheel chain", e);
			return false;
		}
	}

	private static boolean createTwoNodeChain(
		Level level,
		BlockPos cogwheelPos,
		KineticsCoreCogwheelNode core,
		Object blockEntity
	) throws ReflectiveOperationException {
		BlockState state = level.getBlockState(cogwheelPos);

		boolean isLarge = BnBReflect.isLargeBlockTarget(state);
		boolean hasOffset = BnBReflect.hasSmallCogwheelOffset(state);
		Direction.Axis axis = BnBReflect.getRotationAxis(state);

		List<Object> nodes = List.of(
			BnBReflect.newPathedNode(1, isLarge, axis, BlockPos.ZERO, hasOffset),
			BnBReflect.newPathedNode(-1, false, core.rotationAxis(), core.pos().subtract(cogwheelPos), false));
		Object chain = BnBReflect.newCogwheelChain(nodes);

		if (BnBReflect.isPartOfChain(blockEntity))
			BnBReflect.destroyChain(blockEntity, false);
		BnBReflect.setAsController(blockEntity, chain);
		BnBReflect.setChainsUsed(blockEntity, BnBReflect.getChainsRequired(chain));
		BnBReflect.sendData(blockEntity);
		return true;
	}

	private static void cancelVanillaUse(PlayerInteractEvent.RightClickBlock event) {
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}

	static final class BnBReflect {
		private static final String CHAIN_BE = "com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlockEntity";
		private static final String CHAIN = "com.kipti.bnb.content.cogwheel_chain.graph.CogwheelChain";
		private static final String PATHED_NODE = "com.kipti.bnb.content.cogwheel_chain.graph.PathedCogwheelNode";
		private static final String PLACING_CHAIN = "com.kipti.bnb.content.cogwheel_chain.graph.PlacingCogwheelChain";

		private static Boolean available;

		private BnBReflect() {}

		static boolean isAvailable() {
			if (available != null)
				return available;
			available = classExists(CHAIN_BE) && classExists(CHAIN) && classExists(PATHED_NODE) && classExists(PLACING_CHAIN);
			return available;
		}

		static boolean isValidBlockTarget(BlockState state) {
			try {
				return (Boolean) Class.forName(PLACING_CHAIN)
					.getMethod("isValidBlockTarget", BlockState.class)
					.invoke(null, state);
			} catch (ReflectiveOperationException e) {
				return false;
			}
		}

		static boolean isValidLinkTarget(Level level, BlockPos pos) {
			return isValidBlockTarget(level.getBlockState(pos)) || isCogwheelChainBlockEntity(level.getBlockEntity(pos));
		}

		static boolean isLargeBlockTarget(BlockState state) throws ReflectiveOperationException {
			Class<?> chainBlockClass = Class.forName("com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlock");
			if (chainBlockClass.isInstance(state.getBlock()))
				return (Boolean) chainBlockClass.getMethod("isLargeChainCog").invoke(state.getBlock());

			return (Boolean) Class.forName(PLACING_CHAIN)
				.getMethod("isLargeBlockTarget", BlockState.class)
				.invoke(null, state);
		}

		static boolean hasSmallCogwheelOffset(BlockState state) throws ReflectiveOperationException {
			Class<?> chainBlockClass = Class.forName("com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlock");
			if (chainBlockClass.isInstance(state.getBlock())) {
				BlockState sourceState = (BlockState) chainBlockClass.getMethod("getSourceBlockState").invoke(state.getBlock());
				return hasSmallCogwheelOffset(sourceState);
			}

			return (Boolean) Class.forName(PLACING_CHAIN)
				.getMethod("hasSmallCogwheelOffset", BlockState.class)
				.invoke(null, state);
		}

		static Direction.Axis getRotationAxis(BlockState state) {
			try {
				return state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS);
			} catch (IllegalArgumentException e) {
				return Direction.Axis.Y;
			}
		}

		static boolean isCogwheelChainBlockEntity(Object be) {
			if (be == null) return false;
			try {
				return Class.forName(CHAIN_BE).isInstance(be);
			} catch (ClassNotFoundException e) {
				return false;
			}
		}

		static BlockEntity getOrCreateChainBlockEntity(Level level, BlockPos pos) throws ReflectiveOperationException {
			BlockEntity be = level.getBlockEntity(pos);
			if (isCogwheelChainBlockEntity(be))
				return be;

			BlockState state = level.getBlockState(pos);
			if (!isValidBlockTarget(state))
				return null;

			Object chainState = Class.forName("com.kipti.bnb.content.cogwheel_chain.block.CogwheelChainBlock")
				.getMethod("getChainState", BlockState.class, boolean.class, Direction.Axis.class)
				.invoke(null, state, isLargeBlockTarget(state), getRotationAxis(state));
			if (!(chainState instanceof BlockState blockState))
				return null;

			level.setBlockAndUpdate(pos, blockState);
			be = level.getBlockEntity(pos);
			return isCogwheelChainBlockEntity(be) ? be : null;
		}

		static Object resolveController(Level level, BlockPos pos, Object be) throws ReflectiveOperationException {
			if (isController(be))
				return be;
			Object offset = be.getClass().getMethod("getControllerOffset").invoke(be);
			if (!(offset instanceof Vec3i vec3i))
				return null;
			BlockPos controllerPos = pos.offset(vec3i);
			BlockEntity controllerBE = level.getBlockEntity(controllerPos);
			if (controllerBE == null || !isCogwheelChainBlockEntity(controllerBE))
				return null;
			return controllerBE;
		}

		static BlockPos getControllerWorldPos(BlockPos myPos, Object be) throws ReflectiveOperationException {
			if (isController(be))
				return myPos;
			Object offset = be.getClass().getMethod("getControllerOffset").invoke(be);
			if (offset instanceof Vec3i vec3i)
				return myPos.offset(vec3i);
			return myPos;
		}

		static int countCoreLinks(Level level, BlockPos corePos) {
			int count = 0;
			int searchRadius = 16;
			for (BlockPos checkPos : BlockPos.betweenClosed(
				corePos.offset(-searchRadius, -searchRadius, -searchRadius),
				corePos.offset(searchRadius, searchRadius, searchRadius))) {
				BlockEntity be = level.getBlockEntity(checkPos);
				if (be == null || !isCogwheelChainBlockEntity(be))
					continue;
				try {
					if (!isController(be))
						continue;
					Object chain = getChain(be);
					if (chain == null)
						continue;
					if (containsWorldPosition(checkPos, chain, corePos))
						count++;
				} catch (ReflectiveOperationException ignored) {}
			}
			return count;
		}

		static boolean containsWorldPosition(BlockPos controllerPos, Object chain, BlockPos worldPos) throws ReflectiveOperationException {
			for (Object node : getChainPathCogwheelNodes(chain)) {
				if (controllerPos.offset(localPos(node)).equals(worldPos))
					return true;
			}
			return false;
		}

		static int findNodeIndex(BlockPos controllerPos, List<Object> nodes, BlockPos worldPos) throws ReflectiveOperationException {
			for (int i = 0; i < nodes.size(); i++) {
				if (controllerPos.offset(localPos(nodes.get(i))).equals(worldPos))
					return i;
			}
			return -1;
		}

		static Object newPathedNode(
			int side,
			boolean isLarge,
			Direction.Axis axis,
			BlockPos localPos,
			boolean offsetForSmallCogwheel
		) throws ReflectiveOperationException {
			Constructor<?> constructor = Class.forName(PATHED_NODE)
				.getConstructor(int.class, boolean.class, Direction.Axis.class, BlockPos.class, boolean.class);
			return constructor.newInstance(side, isLarge, axis, localPos, offsetForSmallCogwheel);
		}

		static Object newCogwheelChain(List<Object> nodes) throws ReflectiveOperationException {
			return Class.forName(CHAIN).getConstructor(List.class).newInstance(nodes);
		}

		@SuppressWarnings("unchecked")
		static List<Object> getChainPathCogwheelNodes(Object chain) throws ReflectiveOperationException {
			return new ArrayList<>((List<Object>) chain.getClass().getMethod("getChainPathCogwheelNodes").invoke(chain));
		}

		static int getChainsRequired(Object chain) throws ReflectiveOperationException {
			return (Integer) chain.getClass().getMethod("getChainsRequired").invoke(chain);
		}

		static Object getChain(Object be) throws ReflectiveOperationException {
			return be.getClass().getMethod("getChain").invoke(be);
		}

		static boolean isController(Object be) throws ReflectiveOperationException {
			return (Boolean) be.getClass().getMethod("isController").invoke(be);
		}

		static boolean isPartOfChain(Object be) throws ReflectiveOperationException {
			return isController(be) || be.getClass().getMethod("getControllerOffset").invoke(be) != null;
		}

		static void destroyChain(Object be, boolean dropItems) throws ReflectiveOperationException {
			be.getClass().getMethod("destroyChain", boolean.class).invoke(be, dropItems);
		}

		static void setAsController(Object be, Object chain) throws ReflectiveOperationException {
			be.getClass().getMethod("setAsController", Class.forName(CHAIN)).invoke(be, chain);
		}

		static void setChainsUsed(Object be, int chainsUsed) throws ReflectiveOperationException {
			be.getClass().getMethod("setChainsUsed", int.class).invoke(be, chainsUsed);
		}

		static void sendData(Object be) throws ReflectiveOperationException {
			if (be instanceof BlockEntity blockEntity) {
				blockEntity.setChanged();
				if (blockEntity.getLevel() != null) {
					BlockState state = blockEntity.getBlockState();
					blockEntity.getLevel().sendBlockUpdated(blockEntity.getBlockPos(), state, state, 3);
				}
			}
			be.getClass().getMethod("sendData").invoke(be);
		}

		static int side(Object node) throws ReflectiveOperationException {
			return (Integer) node.getClass().getMethod("side").invoke(node);
		}

		static BlockPos localPos(Object node) throws ReflectiveOperationException {
			return (BlockPos) node.getClass().getMethod("localPos").invoke(node);
		}

		private static boolean classExists(String className) {
			try {
				Class.forName(className, false, Thread.currentThread().getContextClassLoader());
				return true;
			} catch (ClassNotFoundException e) {
				return false;
			}
		}
	}
}
