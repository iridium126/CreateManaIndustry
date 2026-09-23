package com.iridium126.createmanaindustry.compat.ysm.net;

import io.netty.handler.codec.DecoderException;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Bounded archive transfer. A start packet is followed by ordered 16 KiB chunks. */
public record ClientboundYsmArchivePacket(byte kind, UUID transfer, UUID target, long revision,
        String digest, String modelId, int size, int index, byte[] bytes) implements CustomPacketPayload {
    public static final int APPLY = 0, EXPORT = 1, CLEAR = 2, CHUNK = 3;
    public static final int CHUNK_BYTES = 16 * 1024;
    public static final Type<ClientboundYsmArchivePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry", "ysm_archive"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundYsmArchivePacket> STREAM_CODEC =
            StreamCodec.of(ClientboundYsmArchivePacket::encode, ClientboundYsmArchivePacket::decode);

    public static ClientboundYsmArchivePacket begin(int kind, UUID transfer, UUID target, long revision,
            String digest, String modelId, int size) {
        return new ClientboundYsmArchivePacket((byte) kind, transfer, target, revision, digest, modelId, size, -1, new byte[0]);
    }
    public static ClientboundYsmArchivePacket clear(UUID target, long revision) {
        return begin(CLEAR, new UUID(0, 0), target, revision, "", "", 0);
    }
    public static ClientboundYsmArchivePacket chunk(UUID transfer, UUID target, long revision, int index, byte[] bytes) {
        return new ClientboundYsmArchivePacket((byte) CHUNK, transfer, target, revision, "", "", 0, index, bytes);
    }
    private static void encode(RegistryFriendlyByteBuf out, ClientboundYsmArchivePacket packet) {
        out.writeByte(packet.kind); out.writeUUID(packet.transfer); out.writeUUID(packet.target);
        out.writeLong(packet.revision);
        if (packet.kind == CHUNK) { out.writeVarInt(packet.index); out.writeByteArray(packet.bytes); }
        else { out.writeUtf(packet.digest, 64); out.writeUtf(packet.modelId, 128); out.writeVarInt(packet.size); }
    }
    private static ClientboundYsmArchivePacket decode(RegistryFriendlyByteBuf in) {
        byte kind = in.readByte();
        if (kind < APPLY || kind > CHUNK) throw new DecoderException("Invalid YSM archive packet type");
        UUID transfer = in.readUUID(), target = in.readUUID(); long revision = in.readLong();
        if (kind == CHUNK) {
            int index = in.readVarInt();
            byte[] bytes = in.readByteArray(CHUNK_BYTES);
            if (index < 0 || bytes.length == 0) throw new DecoderException("Invalid YSM archive chunk");
            return chunk(transfer, target, revision, index, bytes);
        }
        String digest = in.readUtf(64), modelId = in.readUtf(128); int size = in.readVarInt();
        if (kind != CLEAR && (digest.length() != 64 || !digest.matches("[0-9a-f]{64}")
                || size < 1 || size > 64 * 1024 * 1024)) throw new DecoderException("Invalid YSM archive header");
        return begin(kind, transfer, target, revision, digest, modelId, size);
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public static void handle(ClientboundYsmArchivePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> com.iridium126.createmanaindustry.compat.ysm.net.YsmClientArchives.accept(packet));
    }
}
