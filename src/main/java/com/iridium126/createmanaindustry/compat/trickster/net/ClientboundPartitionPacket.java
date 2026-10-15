package com.iridium126.createmanaindustry.compat.trickster.net;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionBounds;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.*;
import java.util.function.Consumer;

public record ClientboundPartitionPacket(ResourceLocation dimension,UUID id,PartitionBounds bounds,boolean remove,boolean animate,List<UUID> escaping,boolean append) implements CustomPacketPayload {
    public ClientboundPartitionPacket(ResourceLocation dimension,UUID id,PartitionBounds bounds,boolean remove,boolean animate,List<UUID> escaping){this(dimension,id,bounds,remove,animate,List.copyOf(escaping),false);}
    public static final Type<ClientboundPartitionPacket> TYPE=new Type<>(CreateManaIndustry.modLoc("partition"));
    private static final ClientPayloadHandler<ClientboundPartitionPacket> CLIENT=new ClientPayloadHandler<>();
    public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundPartitionPacket> STREAM_CODEC=StreamCodec.of(ClientboundPartitionPacket::encode,ClientboundPartitionPacket::decode);
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<ClientboundPartitionPacket> handler){CLIENT.install(handler);}
    public static void handle(ClientboundPartitionPacket packet,IPayloadContext context){context.enqueueWork(()->CLIENT.dispatch(packet));}
    private static void encode(RegistryFriendlyByteBuf b,ClientboundPartitionPacket p) {
        if(p.escaping.size()>1024)throw new IllegalArgumentException("Unbatched partition exemptions");
        b.writeResourceLocation(p.dimension);b.writeUUID(p.id);var r=p.bounds;
        b.writeInt(r.minX());b.writeInt(r.minY());b.writeInt(r.minZ());b.writeInt(r.maxX());b.writeInt(r.maxY());b.writeInt(r.maxZ());
        b.writeBoolean(p.remove);b.writeBoolean(p.animate);b.writeBoolean(p.append);b.writeVarInt(p.escaping.size());p.escaping.forEach(b::writeUUID);
    }
    private static ClientboundPartitionPacket decode(RegistryFriendlyByteBuf b) {
        var dimension=b.readResourceLocation();var id=b.readUUID();var bounds=new PartitionBounds(b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt(),b.readInt());
        boolean remove=b.readBoolean(),animate=b.readBoolean(),append=b.readBoolean();int count=b.readVarInt();
        if(count<0||count>1024)throw new IllegalArgumentException("Invalid partition exemption batch");
        var ids=new ArrayList<UUID>(count);for(int i=0;i<count;i++)ids.add(b.readUUID());
        return new ClientboundPartitionPacket(dimension,id,bounds,remove,animate,ids,append);
    }
}
