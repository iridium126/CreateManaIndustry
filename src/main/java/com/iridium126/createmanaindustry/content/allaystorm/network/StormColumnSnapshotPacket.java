package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StormColumnSnapshotPacket(ResourceLocation dimension, long epoch, int id, int token, UUID source,
        long gameTime, float sampleSec, StarfallColumnSnapshot snapshot) implements CustomPacketPayload {
    public static final Type<StormColumnSnapshotPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry", "storm_column_snapshot"));
    private static final ClientPayloadHandler<StormColumnSnapshotPacket> CLIENT = new ClientPayloadHandler<>();
    public static final StreamCodec<RegistryFriendlyByteBuf, StormColumnSnapshotPacket> STREAM_CODEC = StreamCodec.of((buffer, packet) -> {
        buffer.writeResourceLocation(packet.dimension); buffer.writeLong(packet.epoch); buffer.writeVarInt(packet.id); buffer.writeVarInt(packet.token); buffer.writeUUID(packet.source);
        buffer.writeVarLong(packet.gameTime); buffer.writeFloat(packet.sampleSec); packet.snapshot.write(buffer);
    }, buffer -> new StormColumnSnapshotPacket(buffer.readResourceLocation(), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt(), buffer.readUUID(),
            buffer.readVarLong(), buffer.readFloat(), StarfallColumnSnapshot.read(buffer)));

    public StormColumnSnapshotPacket {
        if (dimension.toString().length() > 256 || id <= 0 || id >= 1 << 24 || token <= 0 || !Float.isFinite(sampleSec) || sampleSec < 0 || sampleSec > 104857.61f)
            throw new IllegalArgumentException("Starfall snapshot identity or clock");
    }
    public StormIdentity identity() { return new StormIdentity(dimension, epoch); }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void installClientHandler(Consumer<? super StormColumnSnapshotPacket> handler) { CLIENT.install(handler); }
    public static void handle(StormColumnSnapshotPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer) AllayStormManager.columnSnapshot(packet, context);
            else CLIENT.dispatch(packet);
        });
    }
}
