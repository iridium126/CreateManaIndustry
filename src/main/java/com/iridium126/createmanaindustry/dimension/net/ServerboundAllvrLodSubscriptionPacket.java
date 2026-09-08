package com.iridium126.createmanaindustry.dimension.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap;

/**
 * C2S LOD subscription handshake (plan F30): the client announces whether it
 * can consume the far-terrain stream at all — voxy backend selected and the
 * master {@code allvrLod} switch on. The server only generates bitmaps,
 * serves sections and tracks in-flight state for SUBSCRIBED players, so a
 * near-only client (voxy missing, disabled, OFF, or failed) costs the server
 * zero LOD work: no bitmap compute, no build jobs, no cached traffic.
 * <p>
 * The packet also carries Minecraft's effective render distance in chunks.
 * This lets the cube stream use the same near-render radius as Sodium while
 * the subscription remains the far-terrain capability handshake. It is
 * re-sent whenever either value changes.
 */
public record ServerboundAllvrLodSubscriptionPacket(long sessionEpoch, boolean subscribed,
                                                    int renderDistanceChunks)
    implements CustomPacketPayload {

    public ServerboundAllvrLodSubscriptionPacket {
        renderDistanceChunks = Math.max(2, Math.min(64, renderDistanceChunks));
    }

    public static final CustomPacketPayload.Type<ServerboundAllvrLodSubscriptionPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_subscription"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundAllvrLodSubscriptionPacket> STREAM_CODEC =
        StreamCodec.of(ServerboundAllvrLodSubscriptionPacket::encode,
            ServerboundAllvrLodSubscriptionPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, ServerboundAllvrLodSubscriptionPacket p) {
        buf.writeLong(p.sessionEpoch);
        buf.writeBoolean(p.subscribed);
        buf.writeVarInt(p.renderDistanceChunks);
    }

    private static ServerboundAllvrLodSubscriptionPacket decode(RegistryFriendlyByteBuf buf) {
        return new ServerboundAllvrLodSubscriptionPacket(buf.readLong(), buf.readBoolean(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundAllvrLodSubscriptionPacket packet, IPayloadContext ctx) {
        if (!(ctx.player() instanceof net.minecraft.server.level.ServerPlayer player)
            || player.level().dimension() != com.iridium126.createmanaindustry.dimension.AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        ctx.enqueueWork(() -> {
            AllvrServerLevelDuck duck = (AllvrServerLevelDuck) player.level();
            AllvrCubeMap cubeMap = duck.allvr$getCubeMap();
            if (cubeMap != null) {
                cubeMap.setClientRenderDistance(player.getUUID(), packet.renderDistanceChunks());
            }
            AllvrLodMap lodMap = duck.allvr$getLodMap();
            if (lodMap != null) {
                lodMap.setSubscribed(player.getUUID(), packet.sessionEpoch(), packet.subscribed());
            }
        });
    }
}
