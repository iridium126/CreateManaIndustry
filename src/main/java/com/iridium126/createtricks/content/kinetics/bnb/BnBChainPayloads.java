package com.iridium126.createtricks.content.kinetics.bnb;

import com.iridium126.createtricks.CreateTricks;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class BnBChainPayloads {
	private BnBChainPayloads() {}

	public static void register(RegisterPayloadHandlersEvent event) {
		PayloadRegistrar registrar = event.registrar(CreateTricks.MODID).versioned("1");
		registrar.playToServer(LinkCorePayload.TYPE, LinkCorePayload.STREAM_CODEC, BnBChainPayloads::handleLinkCore);
	}

	private static void handleLinkCore(LinkCorePayload payload, IPayloadContext context) {
		context.enqueueWork(() -> {
			if (!(context.player() instanceof ServerPlayer player))
				return;
			if (!BnBChainCompatibility.isLoaded() || !BnBChainInteractionEvents.isAvailable())
				return;

			InteractionHand[] hands = InteractionHand.values();
			InteractionHand hand = payload.handOrdinal >= 0 && payload.handOrdinal < hands.length
				? hands[payload.handOrdinal]
				: InteractionHand.MAIN_HAND;
			BnBChainInteractionEvents.placeFromClient(player, payload.corePos, payload.coreSlot, payload.targetPos, hand);
		});
	}

	public record LinkCorePayload(BlockPos corePos, int coreSlot, BlockPos targetPos, int handOrdinal)
		implements CustomPacketPayload {
		public static final Type<LinkCorePayload> TYPE =
			new Type<>(ResourceLocation.fromNamespaceAndPath(CreateTricks.MODID, "bnb_link_core"));

		public static final StreamCodec<ByteBuf, LinkCorePayload> STREAM_CODEC = StreamCodec.of(
			(buf, payload) -> {
				buf.writeLong(payload.corePos.asLong());
				ByteBufCodecs.VAR_INT.encode(buf, payload.coreSlot);
				buf.writeLong(payload.targetPos.asLong());
				ByteBufCodecs.VAR_INT.encode(buf, payload.handOrdinal);
			},
			buf -> new LinkCorePayload(
				BlockPos.of(buf.readLong()),
				ByteBufCodecs.VAR_INT.decode(buf),
				BlockPos.of(buf.readLong()),
				ByteBufCodecs.VAR_INT.decode(buf))
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}
