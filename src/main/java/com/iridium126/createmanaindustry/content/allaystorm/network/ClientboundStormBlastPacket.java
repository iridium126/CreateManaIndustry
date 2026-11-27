package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.content.allaystorm.StormIdentity;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundStormBlastPacket(StormIdentity identity, long revision, List<CellGroup> groups)
        implements CustomPacketPayload {
    public static final int MAX_ENTRIES = 4096, MAX_SECTIONS = 1024;
    public record CellGroup(int x, int y, int z, short[] cells) {}
    private static final ClientPayloadHandler<ClientboundStormBlastPacket> CLIENT = new ClientPayloadHandler<>();
    public static final Type<ClientboundStormBlastPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry", "storm_blast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundStormBlastPacket> STREAM_CODEC =
            StreamCodec.of(ClientboundStormBlastPacket::encode, ClientboundStormBlastPacket::decode);

    public static int pack(BlockPos pos) {
        return (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 15) << 8);
    }
    public static List<CellGroup> group(Iterable<BlockPos> removed) {
        Map<SectionPos, BitSet> sections = new LinkedHashMap<>();
        for (var pos : removed) sections.computeIfAbsent(SectionPos.of(pos), ignored -> new BitSet()).set(pack(pos));
        var groups = new ArrayList<CellGroup>();
        sections.forEach((pos, bits) -> {
            var cells = new short[bits.cardinality()];
            int offset = 0;
            for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1)) cells[offset++] = (short) bit;
            groups.add(new CellGroup(pos.x(), pos.y(), pos.z(), cells));
        });
        return groups;
    }
    private static void encode(RegistryFriendlyByteBuf out, ClientboundStormBlastPacket packet) {
        out.writeResourceLocation(packet.identity.dimension());
        out.writeLong(packet.identity.epoch());
        out.writeVarLong(packet.revision);
        out.writeVarInt(packet.groups.size());
        for (var group : packet.groups) {
            out.writeVarInt(group.x).writeVarInt(group.y).writeVarInt(group.z).writeVarInt(group.cells.length);
            for (short cell : group.cells) out.writeShort(cell);
        }
    }
    private static ClientboundStormBlastPacket decode(RegistryFriendlyByteBuf in) {
        var identity = new StormIdentity(in.readResourceLocation(), in.readLong());
        long revision = in.readVarLong();
        int count = in.readVarInt();
        if (revision <= 0 || count < 1 || count > MAX_SECTIONS) throw new DecoderException("Invalid storm blast batch");
        var groups = new ArrayList<CellGroup>(count);
        int total = 0;
        for (int i = 0; i < count; i++) {
            int x = in.readVarInt(), y = in.readVarInt(), z = in.readVarInt(), size = in.readVarInt();
            if (Math.abs((long) x) > 2_000_000 || Math.abs((long) y) > 2_000_000
                    || Math.abs((long) z) > 2_000_000 || size < 1 || size > MAX_ENTRIES - total)
                throw new DecoderException("Invalid storm blast section");
            total += size;
            short[] cells = new short[size];
            int previous = -1;
            for (int j = 0; j < size; j++) {
                int cell = in.readUnsignedShort();
                if (cell >= 4096 || cell <= previous) throw new DecoderException("Invalid storm blast cell");
                previous = cell;
                cells[j] = (short) cell;
            }
            groups.add(new CellGroup(x, y, z, cells));
        }
        return new ClientboundStormBlastPacket(identity, revision, List.copyOf(groups));
    }
    public static void installClientHandler(Consumer<? super ClientboundStormBlastPacket> handler) { CLIENT.install(handler); }
    public static void handle(ClientboundStormBlastPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> CLIENT.dispatch(packet));
    }
    @Override public Type<ClientboundStormBlastPacket> type() { return TYPE; }
}
