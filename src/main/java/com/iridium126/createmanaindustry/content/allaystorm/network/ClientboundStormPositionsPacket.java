package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Consumer;

/**
 * Storm position snapshot relay, server → clients (every active player
 * EXCEPT the authority). Content and wire format are identical to
 * {@link ServerboundStormPositionsPacket} — the server relays the authority's
 * payload without re-quantization; the {@code gameTime} stamp lets receivers
 * extrapolate targets by the shared clock instead of trusting stale
 * positions.
 * <p>
 * Clients write each entry into the per-member correction-slot buffer; the
 * update compute pass eases members toward the extrapolated target with a
 * strength that ramps up as the snapshot ages (early window = local physics
 * rules, late window = converge). Corrections are soft: if the stream stops
 * (handoff gap, packet loss) local simulation simply continues.
 */
public record ClientboundStormPositionsPacket(net.minecraft.resources.ResourceLocation dimension, long epoch,
        long gameTime, float[] entries) implements CustomPacketPayload {

    public com.iridium126.createmanaindustry.content.allaystorm.StormIdentity identity() {
        return new com.iridium126.createmanaindustry.content.allaystorm.StormIdentity(dimension, epoch);
    }

    private static final ClientPayloadHandler<ClientboundStormPositionsPacket> CLIENT_HANDLER =
            new ClientPayloadHandler<>();

    public static final CustomPacketPayload.Type<ClientboundStormPositionsPacket> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry", "storm_positions_down"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormPositionsPacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormPositionsPacket::encode, ClientboundStormPositionsPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundStormPositionsPacket packet) {
        buffer.writeResourceLocation(packet.dimension());
        buffer.writeLong(packet.epoch());
        buffer.writeVarLong(packet.gameTime());
        StormPositionsCodec.write(buffer, packet.entries());
    }

    private static ClientboundStormPositionsPacket decode(RegistryFriendlyByteBuf buffer) {
        var dimension = buffer.readResourceLocation();
        long epoch = buffer.readLong(), time = buffer.readVarLong();
        return new ClientboundStormPositionsPacket(dimension, epoch, time, StormPositionsCodec.read(buffer));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Installs the client receiver during client startup. */
    public static void installClientHandler(Consumer<? super ClientboundStormPositionsPacket> handler) {
        CLIENT_HANDLER.install(handler);
    }

    /** Called on the client. */
    public static void handle(ClientboundStormPositionsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CLIENT_HANDLER.dispatch(packet));
    }
}
