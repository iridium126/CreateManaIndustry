package com.iridium126.createmanaindustry.content.allaystorm.network;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Storm position snapshot, authority client → server, at the configured
 * {@code stormCorrectionHz}. Flat entry stride 8 floats:
 * {@code (memberIdx, pad, px, py, pz, vx, vy, vz)} with positions RELATIVE to
 * the storm anchor and velocities in blocks/s. memberIdx rides a float slot —
 * exact for all values below 2^24, and 131072 is comfortably inside; the pad
 * slot at offset 1 stays zero so the GPU staging layout maps 1:1, and every
 * coordinate/velocity consumer MUST skip it (it is never a value).
 * <p>
 * Wire quantization: position 1/16-block steps (short, ±2048 blocks around
 * the anchor), velocity 1/16-blocks-per-second steps (signed short, validated to ±32 b/s).
 * Each member uses up to 15 bytes; a 256-member snapshot is about 4 KB.
 * <p>
 * The server cannot validate these positions (no GPU) — it applies a coarse
 * bounds check and relays the payload to the other active clients as a
 * {@link ClientboundStormPositionsPacket} (documented trust surface, same
 * tier as client-reported member hit candidates).
 */
public record ServerboundStormPositionsPacket(net.minecraft.resources.ResourceLocation dimension, long epoch,
        long gameTime, float[] entries) implements CustomPacketPayload {

    public com.iridium126.createmanaindustry.content.allaystorm.StormIdentity identity() {
        return new com.iridium126.createmanaindustry.content.allaystorm.StormIdentity(dimension, epoch);
    }

    public static final int STRIDE = 8;

    /**
     * Maximum snapshot entries the decoder accepts (mirrors the client-side
     * readback cap {@code ParticleBuffers.STORMPOS_CAP}, which is NOT
     * referenced here — network payloads must stay server-safe). The count is
     * a raw VAR_INT and drives the entry array allocation, so without this cap
     * a single malformed packet could make the server allocate gigabytes.
     * Anything outside [0, MAX_ENTRIES] is a protocol violation: the decoder
     * throws a {@link DecoderException} and the network layer drops the
     * connection (vanilla semantics for malformed packets — there is no way
     * to skip a length-prefixed payload without desyncing the stream).
     */
    public static final int MAX_ENTRIES = 256;

    public static final CustomPacketPayload.Type<ServerboundStormPositionsPacket> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry", "storm_positions_up"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundStormPositionsPacket> STREAM_CODEC =
            StreamCodec.of(ServerboundStormPositionsPacket::encode, ServerboundStormPositionsPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ServerboundStormPositionsPacket packet) {
        buffer.writeResourceLocation(packet.dimension());
        buffer.writeLong(packet.epoch());
        buffer.writeVarLong(packet.gameTime());
        StormPositionsCodec.write(buffer, packet.entries());
    }

    private static ServerboundStormPositionsPacket decode(RegistryFriendlyByteBuf buffer) {
        var dimension = buffer.readResourceLocation();
        long epoch = buffer.readLong(), time = buffer.readVarLong();
        return new ServerboundStormPositionsPacket(dimension, epoch, time, StormPositionsCodec.read(buffer));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Called on the server. */
    public static void handle(ServerboundStormPositionsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager.relayPositions(packet, ctx));
    }
}
