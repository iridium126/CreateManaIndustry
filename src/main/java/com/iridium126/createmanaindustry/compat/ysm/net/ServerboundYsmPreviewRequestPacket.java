package com.iridium126.createmanaindustry.compat.ysm.net;

import io.netty.handler.codec.DecoderException;
import java.util.HexFormat;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** A client asks the current world to resolve one persistent preview handle. */
public record ServerboundYsmPreviewRequestPacket(String key, boolean cube) implements CustomPacketPayload {
    public static final Type<ServerboundYsmPreviewRequestPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("createmanaindustry", "ysm_preview_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerboundYsmPreviewRequestPacket> STREAM_CODEC =
            StreamCodec.of(ServerboundYsmPreviewRequestPacket::encode, ServerboundYsmPreviewRequestPacket::decode);

    public ServerboundYsmPreviewRequestPacket {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid YSM preview key");
    }
    private static void encode(RegistryFriendlyByteBuf out, ServerboundYsmPreviewRequestPacket packet) {
        out.writeBoolean(packet.cube()); out.writeByteArray(HexFormat.of().parseHex(packet.key()));
    }
    private static ServerboundYsmPreviewRequestPacket decode(RegistryFriendlyByteBuf in) {
        boolean cube = in.readBoolean();
        byte[] bytes = in.readByteArray(32);
        if (bytes.length != 32) throw new DecoderException("Invalid YSM preview key length");
        return new ServerboundYsmPreviewRequestPacket(HexFormat.of().formatHex(bytes), cube);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(ServerboundYsmPreviewRequestPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            try {
                var runtime = com.iridium126.createmanaindustry.compat.ysm.YsmServerRuntime.get(player.server);
                runtime.requestPreview(player, packet.key(), packet.cube());
            } catch (RuntimeException failure) {
                String reason = failure.getMessage() == null ? "YSM preview is unavailable" : failure.getMessage();
                String normalized = reason.toLowerCase(java.util.Locale.ROOT);
                PacketDistributor.sendToPlayer(player, ClientboundYsmPreviewPacket.error(packet.key(),
                        packet.cube(), normalized.contains("loading") || normalized.contains("busy"), reason));
            }
        });
    }
}
