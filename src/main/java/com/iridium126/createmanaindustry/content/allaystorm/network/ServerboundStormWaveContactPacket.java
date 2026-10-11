package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Dive-wave contact self-report, target client → server (~13 B). The target
 * player's client detects the visual contact on the GPU (exact positions,
 * zero quantization delay) and reports {waveId, memberIdx, contact point};
 * the server re-derives the squad membership from the shared hash chains and
 * decides the damage ({@code AllayStormManager.handleWaveContact}).
 * <p>
 * Trust surface (recorded in docs/allay-storm-ai.md §7): a report can only
 * damage the REPORTER (ctx.player is the target — there is no target field),
 * so the only lie is self-harm; the residual abuse (faking contacts to yank
 * members around via the strong hit-correction broadcast) is bounded by the
 * server's per-member-per-wave dedupe plus the plausibility reach check.
 * The contact point rides rel-to-anchor 1/16-block shorts, the same
 * reconstruction frame as {@link ServerboundStormHitPacket}.
 */
public record ServerboundStormWaveContactPacket(
        int waveId, long epoch, float sampleSec, int memberIdx, float relX, float relY, float relZ) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ServerboundStormWaveContactPacket> TYPE =
            new CustomPacketPayload.Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_wave_contact"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundStormWaveContactPacket> STREAM_CODEC =
            StreamCodec.of(ServerboundStormWaveContactPacket::encode, ServerboundStormWaveContactPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, ServerboundStormWaveContactPacket p) {
        ByteBufCodecs.VAR_INT.encode(buffer, p.waveId);
        buffer.writeLong(p.epoch);buffer.writeFloat(p.sampleSec);
        ByteBufCodecs.VAR_INT.encode(buffer, p.memberIdx);
        buffer.writeFloat(p.relX);
        buffer.writeFloat(p.relY);
        buffer.writeFloat(p.relZ);
    }

    private static short quant(double rel) {
        double q = rel * 16.0;
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(q)));
    }

    private static ServerboundStormWaveContactPacket decode(RegistryFriendlyByteBuf b){return new ServerboundStormWaveContactPacket(b.readVarInt(),b.readLong(),b.readFloat(),b.readVarInt(),b.readFloat(),b.readFloat(),b.readFloat());}

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
