package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Consumer;

/**
 * Continuous storm-center snapshot, server → clients (~24 bytes), broadcast
 * every {@code AllayStormManager.CENTER_INTERVAL_TICKS} (20 ticks) and once
 * immediately on activation. The chased center integrates as doubles
 * server-side at {@code AllayStormManager.CHASE_SPEED}; clients lerp the last
 * TWO snapshots one interval behind (entity-style interpolation), so the
 * center every client feeds its servo targets is stream-derivable and
 * identical across clients — no per-client divergence, and the packet rate
 * stays at 1 Hz while the center moves continuously.
 * <p>
 * The chase velocity rides along so a late/missed packet can be reasoned
 * about (and future paths may extrapolate); the current client interpolation
 * clamps at the newest snapshot — a stalled center moves 0 blocks/s versus
 * the truth's 2 b/s for under a second, which is invisible.
 * <p>
 * The packet also carries the storm's GROWTH state: the generated member
 * population (count, varint). The client keeps it in a double-snapshot
 * interpolation (same scheme as the center) and derives the storm radius
 * ({@code sqrt(count)/8}) and angular velocity ({@code SPIN_K/radius}, see
 * {@code AllayStormData.vortexOmega}) from it
 * CONTINUOUSLY every frame — the radius rides no wire at all, so the
 * expanding shell never jumps at packet or quantization steps.
 * {@code ClientboundStormStatePacket} remains the lifecycle authority; this
 * stream is the per-second delta channel.
 */
public record ClientboundStormCenterPacket(
        net.minecraft.resources.ResourceLocation dimension, long epoch,
        float x, float y, float z, float velX, float velZ,
        int count, long gameTime) implements CustomPacketPayload {

    public com.iridium126.createmanaindustry.content.allaystorm.StormIdentity identity() {
        return new com.iridium126.createmanaindustry.content.allaystorm.StormIdentity(dimension, epoch);
    }

    private static final ClientPayloadHandler<ClientboundStormCenterPacket> CLIENT_HANDLER =
            new ClientPayloadHandler<>();

    public static final CustomPacketPayload.Type<ClientboundStormCenterPacket> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry", "storm_center"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormCenterPacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormCenterPacket::encode, ClientboundStormCenterPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundStormCenterPacket p) {
        buffer.writeResourceLocation(p.dimension);
        buffer.writeLong(p.epoch);
        buffer.writeFloat(p.x);
        buffer.writeFloat(p.y);
        buffer.writeFloat(p.z);
        buffer.writeFloat(p.velX);
        buffer.writeFloat(p.velZ);
        ByteBufCodecs.VAR_INT.encode(buffer, p.count);
        buffer.writeLong(p.gameTime);
    }

    private static ClientboundStormCenterPacket decode(RegistryFriendlyByteBuf buffer) {
        var dimension = buffer.readResourceLocation();
        long epoch = buffer.readLong();
        float x = buffer.readFloat();
        float y = buffer.readFloat();
        float z = buffer.readFloat();
        float velX = buffer.readFloat();
        float velZ = buffer.readFloat();
        int count = ByteBufCodecs.VAR_INT.decode(buffer);
        long gameTime = buffer.readLong();
        return new ClientboundStormCenterPacket(dimension, epoch, x, y, z, velX, velZ, count, gameTime);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Installs the client receiver during client startup. */
    public static void installClientHandler(Consumer<? super ClientboundStormCenterPacket> handler) {
        CLIENT_HANDLER.install(handler);
    }

    /** Called on the client. */
    public static void handle(ClientboundStormCenterPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CLIENT_HANDLER.dispatch(packet));
    }
}
