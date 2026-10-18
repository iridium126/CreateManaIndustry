package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** GPU hit candidate only. Combat values are exclusively calculated by the server. */
public record ServerboundStormHitPacket(ResourceLocation dimension, long epoch, int memberIdx,
        long sampleTick, double x, double y, double z) implements CustomPacketPayload {
    public StormIdentity identity() { return new StormIdentity(dimension, epoch); }
    public Vec3 contact() { return new Vec3(x, y, z); }
    public static final Type<ServerboundStormHitPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID, "storm_hit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundStormHitPacket> STREAM_CODEC =
            StreamCodec.of(ServerboundStormHitPacket::encode, ServerboundStormHitPacket::decode);

    private static void encode(RegistryFriendlyByteBuf b, ServerboundStormHitPacket p) {
        ResourceLocation.STREAM_CODEC.encode(b, p.dimension);
        b.writeLong(p.epoch); b.writeVarInt(p.memberIdx); b.writeLong(p.sampleTick);
        b.writeDouble(p.x); b.writeDouble(p.y); b.writeDouble(p.z);
    }
    private static ServerboundStormHitPacket decode(RegistryFriendlyByteBuf b) {
        var p = new ServerboundStormHitPacket(ResourceLocation.STREAM_CODEC.decode(b), b.readLong(),
                b.readVarInt(), b.readLong(), b.readDouble(), b.readDouble(), b.readDouble());
        if (p.memberIdx < 0 || p.memberIdx >= 131072 || !Double.isFinite(p.x)
                || !Double.isFinite(p.y) || !Double.isFinite(p.z))
            throw new DecoderException("Invalid storm hit candidate");
        return p;
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(ServerboundStormHitPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager.handleHit(packet, ctx));
    }
}
