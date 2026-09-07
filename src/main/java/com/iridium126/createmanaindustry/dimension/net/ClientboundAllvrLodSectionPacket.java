package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrLodClientState;

/**
 * One LOD node's voxel section payload (voxy integration plan §6.1) —
 * replaces the meshed quad stream for section-capable clients. The
 * {@code payload} body is the {@code AllvrLodSectionCodec} encoding
 * (palette + per-cell light); positions stay ABSOLUTE — the client maps
 * them into its virtual Y window, never the server.
 * <p>
 * {@code requestId} echoes the server-side request it fulfills (race
 * debugging / ownership attribution); {@code generation} is the node's
 * edit counter at build time so the client can drop stale re-sends.
 */
public record ClientboundAllvrLodSectionPacket(int protocolVersion, long requestId, int level,
                                               long cellLong, int generation, byte[] payload)
    implements CustomPacketPayload {

    public static final int PROTOCOL_VERSION = 1;

    public static final CustomPacketPayload.Type<ClientboundAllvrLodSectionPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_section"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrLodSectionPacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrLodSectionPacket::encode, ClientboundAllvrLodSectionPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ClientboundAllvrLodSectionPacket p) {
        buf.writeVarInt(p.protocolVersion);
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
        long requestId = buf.readLong();
        int level = buf.readVarInt();
        long cellLong = buf.readLong();
        int generation = buf.readVarInt();
        byte[] payload = buf.readByteArray();
        return new ClientboundAllvrLodSectionPacket(protocol, requestId, level, cellLong, generation, payload);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrLodSectionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrLodClientState.applySection(packet));
    }
}
