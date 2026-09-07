package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrLodClientState;

/**
 * Tells the client to drop one LOD node's mesh and pending request state (doc
 * §13 4c): sent on player-edit invalidation (the node will be re-requested if
 * still in band), on stale build results, and as a rejection for
 * out-of-range/malformed requests so the client's pending set cannot leak.
 * <p>
 * {@code requestId} (plan F26): {@code -1} marks the broadcast flavour —
 * "this node's server truth changed, drop whatever you hold" — which applies
 * unconditionally. Any other value is a <b>ticketed</b> forget: it settles
 * exactly the client request that carried that id. A late ticketed forget
 * can therefore never consume a NEWER re-request's pending entry (the
 * re-request was issued under a different id).
 */
public record ClientboundAllvrLodForgetPacket(int level, long cellLong, long sessionEpoch,
                                               long requestId, boolean retryable)
    implements CustomPacketPayload {

    /** requestId for the broadcast (unconditional) flavour. */
    public static final long BROADCAST = -1L;

    public ClientboundAllvrLodForgetPacket(int level, long cellLong) {
        this(level, cellLong, 0L, BROADCAST, false);
    }

    public ClientboundAllvrLodForgetPacket(int level, long cellLong, long requestId) {
        this(level, cellLong, 0L, requestId, false);
    }

    public static ClientboundAllvrLodForgetPacket ticketed(int level, long cellLong,
                                                            long sessionEpoch, long requestId,
                                                            boolean retryable) {
        return new ClientboundAllvrLodForgetPacket(level, cellLong, sessionEpoch, requestId,
            retryable);
    }

    public static final CustomPacketPayload.Type<ClientboundAllvrLodForgetPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_forget"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrLodForgetPacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrLodForgetPacket::encode, ClientboundAllvrLodForgetPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ClientboundAllvrLodForgetPacket p) {
        buf.writeVarInt(p.level);
        buf.writeLong(p.cellLong);
        buf.writeLong(p.sessionEpoch);
        buf.writeLong(p.requestId);
        buf.writeBoolean(p.retryable);
    }

    private static ClientboundAllvrLodForgetPacket decode(RegistryFriendlyByteBuf buf) {
        return new ClientboundAllvrLodForgetPacket(buf.readVarInt(), buf.readLong(), buf.readLong(),
            buf.readLong(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrLodForgetPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrLodClientState.applyForget(packet));
    }
}
