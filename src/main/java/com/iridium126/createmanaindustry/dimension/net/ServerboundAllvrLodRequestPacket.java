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
 * Batched C2S LOD section requests (sodium-parity plan §6.2): pairs of
 * {@code (level byte, cellLong)} — batching 16 per packet keeps the
 * 64-requests/tick pacing at 4 packets/tick instead of 64. Invalid entries
 * (wrong dimension, out of range, over the in-flight cap) are answered with
 * a forget so the client's pending set cannot leak.
 * <p>
 * {@code capability} is the request protocol version. After the legacy-LOD
 * removal only the voxel-section protocol exists ({@code 1}); a request
 * carrying anything else comes from a mismatched client and is rejected with
 * per-entry forgets — capability 0 must never be guessed into section
 * payloads (plan §6.2). The field rides every request batch so a mid-session
 * protocol change self-heals.
 */
public record ServerboundAllvrLodRequestPacket(int capability, long[] flat) implements CustomPacketPayload {

    public static final int MAX_ENTRIES = 16;
    /** 32³ voxel section payloads ({@code ClientboundAllvrLodSectionPacket}) — the only protocol. */
    public static final int CAPABILITY_VOXEL_SECTION = 1;

    public static final CustomPacketPayload.Type<ServerboundAllvrLodRequestPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundAllvrLodRequestPacket> STREAM_CODEC =
        StreamCodec.of(ServerboundAllvrLodRequestPacket::encode, ServerboundAllvrLodRequestPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ServerboundAllvrLodRequestPacket p) {
        buf.writeVarInt(p.capability);
        buf.writeVarInt(p.flat.length / 2);
        for (long v : p.flat) {
            buf.writeLong(v);
        }
    }

    private static ServerboundAllvrLodRequestPacket decode(RegistryFriendlyByteBuf buf) {
        int capability = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
        long[] flat = new long[n * 2];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = buf.readLong();
        }
        return new ServerboundAllvrLodRequestPacket(capability, flat);
    }

    /**
     * level/cell pairs flattened as {level, cellLong} — built by the client
     * with {@link #CAPABILITY_VOXEL_SECTION}.
     */
    public static ServerboundAllvrLodRequestPacket of(int capability, List<long[]> entries) {
        long[] flat = new long[entries.size() * 2];
        int i = 0;
        for (long[] e : entries) {
            flat[i++] = e[0];
            flat[i++] = e[1];
        }
        return new ServerboundAllvrLodRequestPacket(capability, flat);
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
            List<ClientboundAllvrLodForgetPacket> rejects = new ArrayList<>(packet.flat.length / 2);
            for (int i = 0; i + 1 < packet.flat.length; i += 2) {
                rejects.add(new ClientboundAllvrLodForgetPacket((int) packet.flat[i], packet.flat[i + 1]));
            }
            ctx.enqueueWork(() -> {
                for (ClientboundAllvrLodForgetPacket forget : rejects) {
                    player.connection.send(forget);
                }
            });
            return;
        }
        List<long[]> entries = new ArrayList<>(packet.flat.length / 2);
        for (int i = 0; i + 1 < packet.flat.length; i += 2) {
            entries.add(new long[] {packet.flat[i], packet.flat[i + 1]});
        }
        // the lazy duck access creates the maps — keep it on the server thread
        ctx.enqueueWork(() -> {
            AllvrLodMap lodMap = ((AllvrServerLevelDuck) player.level()).allvr$getLodMap();
            if (lodMap != null) {
                lodMap.onRequest(player, entries);
            }
        });
    }
}
