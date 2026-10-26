package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;
import java.util.List;
import java.util.function.Consumer;

public record RiftArrivalPacket(UUID transaction,ResourceLocation dimension,AllaySkyContext sky,List<UUID> trackedEntities,List<UUID> trackedStructures,List<StormTransferGroup.Mount> mounts) implements CustomPacketPayload {
    public RiftArrivalPacket(UUID transaction,ResourceLocation dimension,AllaySkyContext sky,List<UUID> entities,List<UUID> structures){this(transaction,dimension,sky,entities,structures,List.of());}
    public RiftArrivalPacket {
        trackedEntities=List.copyOf(trackedEntities); trackedStructures=List.copyOf(trackedStructures);mounts=List.copyOf(mounts);
        if(trackedEntities.size()>4096||trackedStructures.size()>4096||mounts.size()>4096)throw new IllegalArgumentException("Oversized arrival manifest");
    }
    public static final Type<RiftArrivalPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"rift_arrival"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RiftArrivalPacket> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeUUID(p.transaction);b.writeResourceLocation(p.dimension);p.sky.write(b);writeIds(b,p.trackedEntities);writeIds(b,p.trackedStructures);b.writeVarInt(p.mounts.size());for(var m:p.mounts){b.writeUUID(m.passenger());b.writeUUID(m.vehicle());b.writeVarInt(m.order());}},b->{UUID id=b.readUUID();var dim=b.readResourceLocation();var sky=AllaySkyContext.read(b);var entities=readIds(b);var structures=readIds(b);int count=b.readVarInt();if(count<0||count>4096)throw new io.netty.handler.codec.DecoderException("Oversized mount manifest");var edges=new java.util.ArrayList<StormTransferGroup.Mount>();for(int i=0;i<count;i++)edges.add(new StormTransferGroup.Mount(b.readUUID(),b.readUUID(),b.readVarInt()));return new RiftArrivalPacket(id,dim,sky,entities,structures,edges);});
    private static void writeIds(RegistryFriendlyByteBuf b,List<UUID> ids){b.writeVarInt(ids.size());for(UUID id:ids)b.writeUUID(id);}
    private static List<UUID> readIds(RegistryFriendlyByteBuf b){int count=b.readVarInt();if(count<0||count>4096)throw new io.netty.handler.codec.DecoderException("Oversized arrival manifest");var ids=new java.util.ArrayList<UUID>(count);for(int i=0;i<count;i++)ids.add(b.readUUID());return ids;}
    private static final ClientPayloadHandler<RiftArrivalPacket> CLIENT=new ClientPayloadHandler<>();
    public static void install(Consumer<RiftArrivalPacket> handler){CLIENT.install(handler);}
    public static void handle(RiftArrivalPacket packet,IPayloadContext context){context.enqueueWork(()->CLIENT.dispatch(packet));}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
