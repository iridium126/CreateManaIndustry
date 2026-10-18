package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.resources.ResourceLocation;
import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import io.netty.handler.codec.DecoderException;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.function.Consumer;

/**
 * Protocol 6 authoritative combat result, including dimension/epoch and absolute hit position.
 * All observers, including the attacker, apply these visuals only after server acceptance.
 * Damage and knockback use finite floats so legitimate mace/modded hits are not byte-clamped.
 * The death bit remains authoritative even when a late observer has a full-HP local mirror.
 */
public record ClientboundStormDamagePacket(
        int memberIdx, float damage, float kbX, float kbZ, float light,
        boolean died, int attackerId, boolean crit, boolean magic, int hearts,
        double x, double y, double z, ResourceLocation dimension, long epoch) implements CustomPacketPayload {

    public StormIdentity identity() { return new StormIdentity(dimension, epoch); }

    private static final ClientPayloadHandler<ClientboundStormDamagePacket> CLIENT_HANDLER =
            new ClientPayloadHandler<>();

    public static final CustomPacketPayload.Type<ClientboundStormDamagePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID, "storm_damage"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormDamagePacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormDamagePacket::encode, ClientboundStormDamagePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ClientboundStormDamagePacket p) {
        ResourceLocation.STREAM_CODEC.encode(buffer, p.dimension);
        buffer.writeLong(p.epoch);
        ByteBufCodecs.VAR_INT.encode(buffer, p.memberIdx);
        buffer.writeFloat(p.damage);
        buffer.writeFloat(p.kbX);
        buffer.writeFloat(p.kbZ);
        buffer.writeByte(clamp(Math.round(p.light), 0, 255));
        buffer.writeByte(p.died ? 1 : 0);
        ByteBufCodecs.VAR_INT.encode(buffer, p.attackerId);
        buffer.writeByte((p.crit ? 1 : 0) | (p.magic ? 2 : 0) | (clamp(p.hearts, 0, 63) << 2));
        buffer.writeDouble(p.x);
        buffer.writeDouble(p.y);
        buffer.writeDouble(p.z);
    }

    private static ClientboundStormDamagePacket decode(RegistryFriendlyByteBuf buffer) {
        ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buffer);
        long epoch = buffer.readLong();
        int memberIdx = ByteBufCodecs.VAR_INT.decode(buffer);
        float damage = buffer.readFloat();
        float kbX = buffer.readFloat();
        float kbZ = buffer.readFloat();
        float light = (buffer.readByte() & 0xFF) / 1.0f;
        boolean died = buffer.readByte() != 0;
        int attackerId = ByteBufCodecs.VAR_INT.decode(buffer);
        int visuals = buffer.readByte() & 0xFF;
        boolean crit = (visuals & 1) != 0;
        boolean magic = (visuals & 2) != 0;
        int hearts = (visuals >>> 2) & 0x3F;
        double x = buffer.readDouble();
        double y = buffer.readDouble();
        double z = buffer.readDouble();
        if (memberIdx < 0 || memberIdx >= 131072 || !Float.isFinite(damage) || damage <= 0
                || !Float.isFinite(kbX) || !Float.isFinite(kbZ)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
            throw new DecoderException("Invalid storm combat result");
        return new ClientboundStormDamagePacket(memberIdx, damage, kbX, kbZ, light,
                died, attackerId, crit, magic, hearts, x, y, z, dimension, epoch);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Installs the client receiver during client startup. */
    public static void installClientHandler(Consumer<? super ClientboundStormDamagePacket> handler) {
        CLIENT_HANDLER.install(handler);
    }

    /** Called on the client. */
    public static void handle(ClientboundStormDamagePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CLIENT_HANDLER.dispatch(packet));
    }
}
