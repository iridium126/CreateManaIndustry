package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrLodClientState;

/**
 * One LOD node's voxel section payload — the only far-terrain wire format
 * after the legacy-LOD removal (sodium-parity plan §6.2). The {@code payload}
 * body is the {@code AllvrLodSectionCodec} encoding (palette + per-cell
 * light); positions stay ABSOLUTE — the client maps them into its virtual Y
 * window, never the server.
 * <p>
 * {@code requestId} echoes the client ticket of the request it fulfills
 * (plan F26) — the client only consumes a response whose id matches the
 * pending entry it is holding, so a stale re-send can never satisfy a newer
 * request. {@code generation} is the node's edit counter at build time so
 * the client can drop stale re-sends. {@code protocolVersion} is bumped to 3
 * for the ticket protocol — the decode rejects any other version outright,
 * so mismatched client/server builds fail closed instead of guessing
 * formats.
 */
public record ClientboundAllvrLodSectionPacket(int protocolVersion, long sessionEpoch, long requestId, int level,
                                               long cellLong, int generation, byte[] payload)
    implements CustomPacketPayload {

    public static final int PROTOCOL_VERSION = 4;

    /**
     * Hard payload byte cap (plan F03): derived from the real legal encoding
     * domain — a fully populated worst-case section (palette of 32767
     * non-air ids as 3-byte varints + 32768 16-bit indices + 32768 light
     * bytes) encodes to ≈195 KB; anything larger can only be malformed. The
     * cap stops a hostile stream from requesting the transport maximum
     * before the codec's own validation runs, and the codec's encoder
     * refuses to emit an oversized payload, so a conforming server can never
     * produce a packet the client is required to drop.
     */
    public static final int MAX_PAYLOAD_BYTES =
        com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionCodec.MAX_PAYLOAD_BYTES;

    public static final CustomPacketPayload.Type<ClientboundAllvrLodSectionPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_section"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrLodSectionPacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrLodSectionPacket::encode, ClientboundAllvrLodSectionPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ClientboundAllvrLodSectionPacket p) {
        buf.writeVarInt(p.protocolVersion);
        buf.writeLong(p.sessionEpoch);
        buf.writeLong(p.requestId);
        buf.writeVarInt(p.level);
        buf.writeLong(p.cellLong);
        buf.writeVarInt(p.generation);
        buf.writeByteArray(p.payload);
    }

    private static ClientboundAllvrLodSectionPacket decode(RegistryFriendlyByteBuf buf) {
        int protocol = buf.readVarInt();
        if (protocol != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unknown LOD section protocol version " + protocol);
        }
        long sessionEpoch = buf.readLong();
        long requestId = buf.readLong();
        int level = buf.readVarInt();
        long cellLong = buf.readLong();
        int generation = buf.readVarInt();
        byte[] payload = buf.readByteArray(MAX_PAYLOAD_BYTES);
        return new ClientboundAllvrLodSectionPacket(protocol, sessionEpoch, requestId, level,
            cellLong, generation, payload);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrLodSectionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrLodClientState.applySection(packet));
    }
}
