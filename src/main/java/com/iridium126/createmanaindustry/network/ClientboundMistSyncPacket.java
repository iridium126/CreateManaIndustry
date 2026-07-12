package com.iridium126.createmanaindustry.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.render.ClientMistHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Syncs mist state to tracking clients. Sent when a recipe-based mist
 * activates or deactivates (basin recipes don't have the atomizer's
 * built-in BE sync).
 */
public record ClientboundMistSyncPacket(BlockPos pos, FluidStack fluid) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientboundMistSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("mist_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundMistSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ClientboundMistSyncPacket::pos,
                    FluidStack.STREAM_CODEC, ClientboundMistSyncPacket::fluid,
                    ClientboundMistSyncPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the client. */
    public static void handle(ClientboundMistSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientMistHandler.setActive(packet.pos, packet.fluid));
    }

    /** Send to all players tracking the chunk containing {@code pos}. */
    public static void sendToTracking(Level level, BlockPos pos, FluidStack fluid) {
        if (level.isClientSide)
            return;
        PacketDistributor.sendToPlayersTrackingChunk(
                (net.minecraft.server.level.ServerLevel) level,
                level.getChunkAt(pos).getPos(),
                new ClientboundMistSyncPacket(pos.immutable(), fluid));
    }
}
