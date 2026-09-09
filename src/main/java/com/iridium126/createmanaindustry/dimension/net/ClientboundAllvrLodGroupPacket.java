package com.iridium126.createmanaindustry.dimension.net;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrLodClientState;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionCodec;

/**
 * VoxyMP-style grouped LOD response.  A response contains up to sixteen
 * two-by-two-by-two cell groups; each group carries the requested/included
 * masks and each included cell carries the client's ticket and immutable
 * section payload.  Grouping keeps the transport overhead close to the
 * original chunk protocol while retaining Allay's absolute-Y ticket checks.
 */
public record ClientboundAllvrLodGroupPacket(int protocolVersion, long sessionEpoch,
                                              List<Group> groups)
    implements CustomPacketPayload {

    public static final int PROTOCOL_VERSION = 1;
    public static final int MAX_GROUPS = 16;
    public static final int GROUP_SIZE = 2;
    public static final int MAX_ENTRIES_PER_GROUP = 8;
    public static final int MAX_PAYLOAD_BYTES = AllvrLodSectionCodec.MAX_PAYLOAD_BYTES;

    public ClientboundAllvrLodGroupPacket {
        if (protocolVersion != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unsupported LOD group protocol " + protocolVersion);
        }
        if (groups == null || groups.size() > MAX_GROUPS) {
            throw new IllegalArgumentException("too many LOD groups");
        }
        groups = List.copyOf(groups);
    }

    public static final CustomPacketPayload.Type<ClientboundAllvrLodGroupPacket> TYPE =
        new CustomPacketPayload.Type<>(CreateManaIndustry.modLoc("allvr_lod_group"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundAllvrLodGroupPacket> STREAM_CODEC =
        StreamCodec.of(ClientboundAllvrLodGroupPacket::encode, ClientboundAllvrLodGroupPacket::decode);

    public record Group(int level, int originX, int originY, int originZ,
                        int requestedMask, int includedMask, List<Entry> entries) {
        public Group {
            if (level < 0 || level > 3 || requestedMask < 0 || requestedMask > 0xFF
                || includedMask < 0 || includedMask > 0xFF
                || (includedMask & ~requestedMask) != 0
                || entries == null || entries.size() > MAX_ENTRIES_PER_GROUP) {
                throw new IllegalArgumentException("malformed LOD group");
            }
            entries = List.copyOf(entries);
            if (Integer.bitCount(includedMask) != entries.size()) {
                throw new IllegalArgumentException("LOD group mask/entry count mismatch");
            }
            int seen = 0;
            for (Entry entry : entries) {
                int bit = 1 << entry.localIndex();
                if ((includedMask & bit) == 0 || (seen & bit) != 0) {
                    throw new IllegalArgumentException("LOD group contains an invalid or duplicate local index");
                }
                seen |= bit;
            }
        }
    }

    public record Entry(int localIndex, long requestId, int generation, byte[] payload) {
        public Entry {
            if (localIndex < 0 || localIndex >= MAX_ENTRIES_PER_GROUP
                || payload == null || payload.length > MAX_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("malformed LOD group entry");
            }
            payload = payload.clone();
        }
    }

    private static void encode(RegistryFriendlyByteBuf buf, ClientboundAllvrLodGroupPacket p) {
        buf.writeVarInt(p.protocolVersion());
        buf.writeLong(p.sessionEpoch());
        buf.writeVarInt(p.groups().size());
        for (Group group : p.groups()) {
            buf.writeVarInt(group.level());
            buf.writeVarInt(group.originX());
            buf.writeVarInt(group.originY());
            buf.writeVarInt(group.originZ());
            buf.writeByte(group.requestedMask());
            buf.writeByte(group.includedMask());
            buf.writeVarInt(group.entries().size());
            for (Entry entry : group.entries()) {
                buf.writeByte(entry.localIndex());
                buf.writeLong(entry.requestId());
                buf.writeVarInt(entry.generation());
                buf.writeByteArray(entry.payload());
            }
        }
    }

    private static ClientboundAllvrLodGroupPacket decode(RegistryFriendlyByteBuf buf) {
        int protocol = buf.readVarInt();
        if (protocol != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("unknown LOD group protocol " + protocol);
        }
        long epoch = buf.readLong();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_GROUPS) {
            throw new IllegalArgumentException("LOD group count out of range: " + count);
        }
        List<Group> groups = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int level = buf.readVarInt();
            int ox = buf.readVarInt();
            int oy = buf.readVarInt();
            int oz = buf.readVarInt();
            int requested = buf.readUnsignedByte();
            int included = buf.readUnsignedByte();
            int entriesCount = buf.readVarInt();
            if (entriesCount < 0 || entriesCount > MAX_ENTRIES_PER_GROUP) {
                throw new IllegalArgumentException("LOD group entry count out of range");
            }
            List<Entry> entries = new ArrayList<>(entriesCount);
            for (int j = 0; j < entriesCount; j++) {
                entries.add(new Entry(buf.readUnsignedByte(), buf.readLong(), buf.readVarInt(),
                    buf.readByteArray(MAX_PAYLOAD_BYTES)));
            }
            groups.add(new Group(level, ox, oy, oz, requested, included, entries));
        }
        return new ClientboundAllvrLodGroupPacket(protocol, epoch, groups);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClientboundAllvrLodGroupPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> AllvrLodClientState.applyGroup(packet));
    }
}
