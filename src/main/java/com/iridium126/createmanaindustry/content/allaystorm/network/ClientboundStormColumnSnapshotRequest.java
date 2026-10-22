package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundStormColumnSnapshotRequest(ResourceLocation dimension, long epoch, int id, int token, UUID source, long requestedAt) implements CustomPacketPayload {
    public static final Type<ClientboundStormColumnSnapshotRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry", "storm_column_snapshot_request"));
    private static final ClientPayloadHandler<ClientboundStormColumnSnapshotRequest> CLIENT = new ClientPayloadHandler<>();
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormColumnSnapshotRequest> STREAM_CODEC = StreamCodec.of((buffer, packet) -> {
        buffer.writeResourceLocation(packet.dimension); buffer.writeLong(packet.epoch); buffer.writeVarInt(packet.id); buffer.writeVarInt(packet.token); buffer.writeUUID(packet.source); buffer.writeVarLong(packet.requestedAt);
    }, buffer -> new ClientboundStormColumnSnapshotRequest(buffer.readResourceLocation(), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt(), buffer.readUUID(), buffer.readVarLong()));

    public ClientboundStormColumnSnapshotRequest {
        if (id <= 0 || id >= 1 << 24 || token <= 0) throw new IllegalArgumentException("Starfall snapshot request identity");
    }
    public StormIdentity identity() { return new StormIdentity(dimension, epoch); }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void installClientHandler(Consumer<? super ClientboundStormColumnSnapshotRequest> handler) { CLIENT.install(handler); }
    public static void handle(ClientboundStormColumnSnapshotRequest packet, IPayloadContext context) { context.enqueueWork(() -> CLIENT.dispatch(packet)); }
}
