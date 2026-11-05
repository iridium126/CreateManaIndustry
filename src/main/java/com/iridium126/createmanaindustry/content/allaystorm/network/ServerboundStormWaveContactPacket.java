package com.iridium126.createmanaindustry.content.allaystorm.network;


import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** One reduced attack/target contact. The reporting player is the target;
 * membership, launch eligibility, lifecycle and damage remain server-owned. */
public record ServerboundStormWaveContactPacket(
        net.minecraft.resources.ResourceLocation dimension, int waveId, long epoch, float sampleSec, float relX, float relY, float relZ) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerboundStormWaveContactPacket> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_wave_contact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundStormWaveContactPacket> STREAM_CODEC =
            StreamCodec.of(ServerboundStormWaveContactPacket::encode, ServerboundStormWaveContactPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ServerboundStormWaveContactPacket p) {
        buffer.writeResourceLocation(p.dimension);
        ByteBufCodecs.VAR_INT.encode(buffer, p.waveId);
        buffer.writeLong(p.epoch);buffer.writeFloat(p.sampleSec);
        buffer.writeFloat(p.relX);
        buffer.writeFloat(p.relY);
        buffer.writeFloat(p.relZ);
    }

    private static ServerboundStormWaveContactPacket decode(RegistryFriendlyByteBuf b){return new ServerboundStormWaveContactPacket(b.readResourceLocation(),b.readVarInt(),b.readLong(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat());}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ServerboundStormWaveContactPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof net.minecraft.server.level.ServerPlayer)
                com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager
                        .handleWaveContact(packet, ctx);
        });
    }
}
