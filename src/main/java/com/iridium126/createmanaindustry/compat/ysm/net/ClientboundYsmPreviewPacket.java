package com.iridium126.createmanaindustry.compat.ysm.net;

import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import java.util.function.Consumer;

import io.netty.handler.codec.DecoderException;
import java.util.HexFormat;
import com.iridium126.createmanaindustry.compat.ysm.render.YsmGeometryThumbnail;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** One bounded thumbnail response or a retryable/permanent preview failure. */
public record ClientboundYsmPreviewPacket(byte kind, String key, boolean cube, byte[] png,
        boolean retryable, String reason) implements CustomPacketPayload {
    public static final byte IMAGE = 0, ERROR = 1;
    public static final int MAX_IMAGE_BYTES = YsmGeometryThumbnail.MAX_PNG_BYTES;
    private static final ClientPayloadHandler<ClientboundYsmPreviewPacket> CLIENT_HANDLER =
            new ClientPayloadHandler<>();

    public static final Type<ClientboundYsmPreviewPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("createmanaindustry", "ysm_preview"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundYsmPreviewPacket> STREAM_CODEC =
            StreamCodec.of(ClientboundYsmPreviewPacket::encode, ClientboundYsmPreviewPacket::decode);

    public static ClientboundYsmPreviewPacket image(String key, boolean cube, byte[] png) {
        return new ClientboundYsmPreviewPacket(IMAGE, key, cube, png, false, "");
    }
    public static ClientboundYsmPreviewPacket error(String key, boolean cube, boolean retryable, String reason) {
        String bounded = reason == null ? "YSM preview is unavailable" : reason;
        if (bounded.length() > 512) bounded = bounded.substring(0, 512);
        return new ClientboundYsmPreviewPacket(ERROR, key, cube, new byte[0], retryable, bounded);
    }
    public ClientboundYsmPreviewPacket {
        if (key == null || !key.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid YSM preview key");
        if (kind != IMAGE && kind != ERROR) throw new IllegalArgumentException("Invalid YSM preview response kind");
        png = png == null ? new byte[0] : png.clone();
        reason = reason == null ? "" : reason;
        if (reason.length() > 512) reason = reason.substring(0, 512);
        if (kind == IMAGE && (png.length == 0 || png.length > MAX_IMAGE_BYTES))
            throw new IllegalArgumentException("YSM preview PNG must be 1 to 70 KiB");
        if (kind == ERROR && png.length != 0) throw new IllegalArgumentException("Error response cannot contain a preview PNG");
    }
    @Override public byte[] png() { return png.clone(); }

    private static void encode(RegistryFriendlyByteBuf out, ClientboundYsmPreviewPacket packet) {
        out.writeByte(packet.kind());
        out.writeByteArray(HexFormat.of().parseHex(packet.key()));
        out.writeBoolean(packet.cube());
        if (packet.kind() == IMAGE) out.writeByteArray(packet.png());
        else { out.writeBoolean(packet.retryable()); out.writeUtf(packet.reason(), 512); }
    }
    private static ClientboundYsmPreviewPacket decode(RegistryFriendlyByteBuf in) {
        byte kind = in.readByte();
        if (kind != IMAGE && kind != ERROR) throw new DecoderException("Invalid YSM preview response kind");
        byte[] keyBytes = in.readByteArray(32);
        if (keyBytes.length != 32) throw new DecoderException("Invalid YSM preview key length");
        String key = HexFormat.of().formatHex(keyBytes);
        boolean cube = in.readBoolean();
        if (kind == IMAGE) {
            byte[] png = in.readByteArray(MAX_IMAGE_BYTES);
            if (png.length == 0) throw new DecoderException("Empty YSM preview PNG");
            return image(key, cube, png);
        }
        return error(key, cube, in.readBoolean(), in.readUtf(512));
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    /** Installs the client receiver during client startup. */
    public static void installClientHandler(Consumer<? super ClientboundYsmPreviewPacket> handler) {
        CLIENT_HANDLER.install(handler);
    }

    public static void handle(ClientboundYsmPreviewPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> CLIENT_HANDLER.dispatch(packet));
    }
}
