package com.iridium126.createmanaindustry.dimension.net;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrServerLevelDuck;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundAllvrResyncPacket(long cubePos, long generation) implements CustomPacketPayload {
    public static final Type<ServerboundAllvrResyncPacket> TYPE = new Type<>(CreateManaIndustry.modLoc("allvr_resync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundAllvrResyncPacket> STREAM_CODEC = StreamCodec.of(
        (buf, packet) -> { buf.writeLong(packet.cubePos); buf.writeLong(packet.generation); },
        buf -> new ServerboundAllvrResyncPacket(buf.readLong(), buf.readLong()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(ServerboundAllvrResyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && AllvrDimensions.isAllay(player.level())) {
                var map = ((AllvrServerLevelDuck) player.serverLevel()).allvr$peekCubeMap();
                if (map != null) map.broadcaster().requestResync(player, packet.cubePos, packet.generation);
            }
        });
    }
}
