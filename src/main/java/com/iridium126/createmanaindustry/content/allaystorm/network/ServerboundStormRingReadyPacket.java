package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import com.iridium126.createmanaindustry.content.allaystorm.StormRingGeometry;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundStormRingReadyPacket(StormIdentity identity, long tick, int[] ids) implements CustomPacketPayload {
    public static final Type<ServerboundStormRingReadyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry", "storm_ring_ready"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundStormRingReadyPacket> STREAM_CODEC =
            StreamCodec.of(ServerboundStormRingReadyPacket::encode, ServerboundStormRingReadyPacket::decode);

    private static void encode(RegistryFriendlyByteBuf out, ServerboundStormRingReadyPacket packet) {
        out.writeResourceLocation(packet.identity.dimension());
        out.writeLong(packet.identity.epoch());
        out.writeVarLong(packet.tick);
        out.writeVarInt(packet.ids.length);
        for (int id : packet.ids) out.writeVarInt(id);
    }
    private static ServerboundStormRingReadyPacket decode(RegistryFriendlyByteBuf in) {
        var identity = new StormIdentity(in.readResourceLocation(), in.readLong());
        long tick = in.readVarLong();
        int count = in.readVarInt();
        if (tick < 0 || count < 1 || count > StormRingGeometry.CAPACITY) throw new DecoderException("Invalid ring readiness");
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = in.readVarInt();
            if (ids[i] <= 0 || ids[i] >= 1 << 24) throw new DecoderException("Invalid ready ring ID");
        }
        return new ServerboundStormRingReadyPacket(identity, tick, ids);
    }
    public static void handle(ServerboundStormRingReadyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> AllayStormManager.ringReady(packet, context));
    }
    @Override public Type<ServerboundStormRingReadyPacket> type() { return TYPE; }
}
