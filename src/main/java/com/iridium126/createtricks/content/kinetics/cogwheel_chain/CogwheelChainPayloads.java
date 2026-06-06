package com.iridium126.createtricks.content.kinetics.cogwheel_chain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.iridium126.createtricks.CreateTricks;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class CogwheelChainPayloads {

	private CogwheelChainPayloads() {}

	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(CreateTricks.MODID).versioned("1");
		registrar.playToServer(PlaceChainPayload.TYPE, PlaceChainPayload.STREAM_CODEC, CogwheelChainPayloads::handlePlace);
		registrar.playToServer(BreakChainPayload.TYPE, BreakChainPayload.STREAM_CODEC, CogwheelChainPayloads::handleBreak);
		registrar.playToClient(SyncChainsPayload.TYPE, SyncChainsPayload.STREAM_CODEC, CogwheelChainPayloads::handleSync);
	}

	// --- Place Chain C2S ---
	public record PlaceChainPayload(List<CogwheelChainNode> nodes) implements CustomPacketPayload {
		public static final Type<PlaceChainPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CreateTricks.MODID, "place_chain"));

		public static final StreamCodec<ByteBuf, PlaceChainPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.VAR_INT.encode(buf, payload.nodes.size());
				for (CogwheelChainNode node : payload.nodes) {
					buf.writeLong(node.pos().asLong());
					ByteBufCodecs.VAR_INT.encode(buf, node.axis().ordinal());
					buf.writeBoolean(node.isLarge());
				}
			},
			buf -> {
				int size = ByteBufCodecs.VAR_INT.decode(buf);
				List<CogwheelChainNode> nodes = new ArrayList<>();
				for (int i = 0; i < size; i++) {
					BlockPos pos = BlockPos.of(buf.readLong());
					Direction.Axis axis = Direction.Axis.values()[ByteBufCodecs.VAR_INT.decode(buf)];
					boolean isLarge = buf.readBoolean();
					nodes.add(new CogwheelChainNode(pos, axis, isLarge));
				}
				return new PlaceChainPayload(nodes);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// --- Break Chain C2S ---
	public record BreakChainPayload(BlockPos controllerPos) implements CustomPacketPayload {
		public static final Type<BreakChainPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CreateTricks.MODID, "break_chain"));

		public static final StreamCodec<ByteBuf, BreakChainPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> buf.writeLong(payload.controllerPos.asLong()),
			buf -> new BreakChainPayload(BlockPos.of(buf.readLong()))
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// --- Sync Chains S2C ---
	public record SyncChainsPayload(List<CogwheelChainData> chains) implements CustomPacketPayload {
		public static final Type<SyncChainsPayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CreateTricks.MODID, "sync_chains"));

		public static final StreamCodec<ByteBuf, SyncChainsPayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				ByteBufCodecs.VAR_INT.encode(buf, payload.chains.size());
				for (CogwheelChainData chain : payload.chains) {
					List<CogwheelChainNode> nodes = chain.getNodes();
					ByteBufCodecs.VAR_INT.encode(buf, nodes.size());
					for (CogwheelChainNode node : nodes) {
						buf.writeLong(node.pos().asLong());
						ByteBufCodecs.VAR_INT.encode(buf, node.axis().ordinal());
						buf.writeBoolean(node.isLarge());
					}
				}
			},
			buf -> {
				int chainCount = ByteBufCodecs.VAR_INT.decode(buf);
				List<CogwheelChainData> chains = new ArrayList<>();
				for (int c = 0; c < chainCount; c++) {
					int nodeCount = ByteBufCodecs.VAR_INT.decode(buf);
					List<CogwheelChainNode> nodes = new ArrayList<>();
					for (int i = 0; i < nodeCount; i++) {
						BlockPos pos = BlockPos.of(buf.readLong());
						Direction.Axis axis = Direction.Axis.values()[ByteBufCodecs.VAR_INT.decode(buf)];
						boolean isLarge = buf.readBoolean();
						nodes.add(new CogwheelChainNode(pos, axis, isLarge));
					}
					chains.add(new CogwheelChainData(nodes));
				}
				return new SyncChainsPayload(chains);
			}
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	// --- Server handlers ---
	private static void handlePlace(PlaceChainPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			if (!(player.level() instanceof ServerLevel serverLevel))
				return;
			if (payload.nodes().size() < 2)
				return;

			int needed = payload.nodes().size();
			if (!player.isCreative()) {
				int available = 0;
				for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
					if (player.getInventory().getItem(i).is(Items.CHAIN))
						available += player.getInventory().getItem(i).getCount();
				}
				if (available < needed)
					return;

				int toRemove = needed;
				for (int i = 0; i < player.getInventory().getContainerSize() && toRemove > 0; i++) {
					if (player.getInventory().getItem(i).is(Items.CHAIN)) {
						int remove = Math.min(toRemove, player.getInventory().getItem(i).getCount());
						player.getInventory().getItem(i).shrink(remove);
						toRemove -= remove;
					}
				}
			}

			CogwheelChainData chain = new CogwheelChainData(payload.nodes());
			CogwheelChainSavedData savedData = CogwheelChainSavedData.get(serverLevel);
			savedData.addChain(chain);
			syncToNearby(serverLevel, savedData);
		});
	}

	private static void handleBreak(BreakChainPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			if (!(player.level() instanceof ServerLevel serverLevel))
				return;

			CogwheelChainSavedData savedData = CogwheelChainSavedData.get(serverLevel);
			List<CogwheelChainData> chains = savedData.getChainsAt(payload.controllerPos());
			for (CogwheelChainData chain : chains) {
				savedData.removeChain(chain.getControllerPos());
				if (!player.isCreative()) {
					int returnCount = chain.getNodes().size();
					while (returnCount > 0) {
						int stack = Math.min(returnCount, 64);
						player.getInventory().placeItemBackInInventory(
							new net.minecraft.world.item.ItemStack(Items.CHAIN, stack));
						returnCount -= stack;
					}
				}
			}
			syncToNearby(serverLevel, savedData);
		});
	}

	private static void handleSync(SyncChainsPayload payload, IPayloadContext context) {
		context.enqueueWork(() -> CogwheelChainClientData.receiveSync(payload.chains()));
	}

	public static void syncToNearby(ServerLevel level, CogwheelChainSavedData savedData) {
		Collection<CogwheelChainData> allChains = savedData.getAllChains();
		SyncChainsPayload syncPayload = new SyncChainsPayload(new ArrayList<>(allChains));
		for (ServerPlayer player : level.players()) {
			PacketDistributor.sendToPlayer(player, syncPayload);
		}
	}

	public static void syncToPlayer(ServerPlayer player) {
		if (!(player.level() instanceof ServerLevel serverLevel))
			return;
		CogwheelChainSavedData savedData = CogwheelChainSavedData.get(serverLevel);
		Collection<CogwheelChainData> allChains = savedData.getAllChains();
		SyncChainsPayload syncPayload = new SyncChainsPayload(new ArrayList<>(allChains));
		PacketDistributor.sendToPlayer(player, syncPayload);
	}
}
