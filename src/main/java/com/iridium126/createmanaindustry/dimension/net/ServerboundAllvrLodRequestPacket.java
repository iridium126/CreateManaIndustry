package com.iridium126.createmanaindustry.dimension.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap;

/**
 * Batched C2S LOD section requests (sodium-parity plan §6.2): triples of
 * {@code (level byte, cellLong, requestId)} — batching 16 per packet keeps
 * the 64-requests/tick pacing at 4 packets/tick instead of 64. Invalid
 * entries (wrong dimension, out of range, over the in-flight cap) are
 * answered with a forget carrying the entry's own requestId so the client's
 * pending set cannot leak.
 * <p>
 * Plan F26: {@code sessionEpoch} rides the batch header and {@code requestId}
 * rides every entry. The client owns both values — ids are strictly
 * monotonic client-side, and the server never invents one (a cache hit
 * echoes the ORIGINAL ticket of the live request). Old answers therefore
 * cannot consume a newer request's pending slot: the ids differ.
 * <p>
 * {@code capability} is the request protocol version. After the legacy-LOD
 * removal only the voxel-section protocol exists; a request carrying
 * anything else comes from a mismatched client and is rejected with
 * per-entry forgets — capability 0 must never be guessed into section
 * payloads (plan §6.2). The field rides every request batch so a mid-session
 * protocol change self-heals.
 */
public record ServerboundAllvrLodRequestPacket(int capability, long sessionEpoch, long[] flat)
    implements CustomPacketPayload {

    public ServerboundAllvrLodRequestPacket {
        if (flat == null || flat.length % 3 != 0 || flat.length / 3 > MAX_ENTRIES) {
            throw new IllegalArgumentException("LOD request batch must contain 0.." + MAX_ENTRIES
                + " triples");
        }
        flat = flat.clone();
    }

    public static final int MAX_ENTRIES = 16;
    /** 32³ voxel section payloads with per-entry tickets ({@code ClientboundAllvrLodSectionPacket}) — the only protocol. */
    public static final int CAPABILITY_VOXEL_SECTION = 2;

    public static final CustomPacketPayload.Type<ServerboundAllvrLodRequestPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundAllvrLodRequestPacket> STREAM_CODEC =
        StreamCodec.of(ServerboundAllvrLodRequestPacket::encode, ServerboundAllvrLodRequestPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ServerboundAllvrLodRequestPacket p) {
        buf.writeVarInt(p.capability);
        buf.writeLong(p.sessionEpoch);
        buf.writeVarInt(p.flat.length / 3);
        for (long v : p.flat) {
            buf.writeLong(v);
        }
    }

    private static ServerboundAllvrLodRequestPacket decode(RegistryFriendlyByteBuf buf) {
        int capability = buf.readVarInt();
        long sessionEpoch = buf.readLong();
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) {
            throw new IllegalArgumentException("LOD request count out of range: " + n);
        }
        long[] flat = new long[n * 3];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = buf.readLong();
        }
        return new ServerboundAllvrLodRequestPacket(capability, sessionEpoch, flat);
    }

    /**
     * level/cell/requestId triples flattened as {level, cellLong, requestId}
     * — built by the client with {@link #CAPABILITY_VOXEL_SECTION}.
     */
    public static ServerboundAllvrLodRequestPacket of(int capability, long sessionEpoch,
                                                      List<long[]> entries) {
        long[] flat = new long[entries.size() * 3];
        int i = 0;
        for (long[] e : entries) {
            flat[i++] = e[0];
            flat[i++] = e[1];
            flat[i++] = e[2];
        }
        return new ServerboundAllvrLodRequestPacket(capability, sessionEpoch, flat);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundAllvrLodRequestPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer player)
            || player.level().dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        // protocol gate: anything but the voxel-section capability is a
        // mismatched client — drain its entries with forgets, never answer
        // with section payloads
        if (packet.capability() != CAPABILITY_VOXEL_SECTION) {
            CreateManaIndustry.LOGGER.warn(
                "[Allvr] rejected LOD request batch with unknown capability {} (client/server mismatch)",
                packet.capability());
            List<ClientboundAllvrLodForgetPacket> rejects = new ArrayList<>(packet.flat.length / 3);
            for (int i = 0; i + 2 < packet.flat.length; i += 3) {
                rejects.add(ClientboundAllvrLodForgetPacket.ticketed((int) packet.flat[i],
                    packet.flat[i + 1], packet.sessionEpoch(), packet.flat[i + 2], false));
            }
            ctx.enqueueWork(() -> {
                for (ClientboundAllvrLodForgetPacket forget : rejects) {
                    player.connection.send(forget);
                }
            });
            return;
        }
        List<long[]> entries = new ArrayList<>(packet.flat.length / 3);
        for (int i = 0; i + 2 < packet.flat.length; i += 3) {
            entries.add(new long[] {packet.flat[i], packet.flat[i + 1], packet.flat[i + 2]});
        }
        // the lazy duck access creates the maps — keep it on the server thread
        ctx.enqueueWork(() -> {
            AllvrLodMap lodMap = ((AllvrServerLevelDuck) player.level()).allvr$getLodMap();
            if (lodMap != null) {
                lodMap.onRequest(player, packet.sessionEpoch(), entries);
            }
        });
    }
}
