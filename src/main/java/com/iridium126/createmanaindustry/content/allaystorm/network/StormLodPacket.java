package com.iridium126.createmanaindustry.content.allaystorm.network;

import java.util.BitSet;
import java.util.function.Consumer;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.content.allaystorm.lod.StormLodMask;
import com.iridium126.createmanaindustry.content.allaystorm.rift.StormVisualDefinition;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Visual-only subscription. Optional sample and death fields avoid idle/per-member traffic. */
public record StormLodPacket(StormIdentity identity, long subscription, int action, int limit, StormVisualDefinition definition,
        StormCenterSample previous, StormCenterSample current, BitSet deaths) implements CustomPacketPayload {
    public static final int INIT=0, UPDATE=1, REMOVE=2;
    public StormLodPacket { deaths=(BitSet)deaths.clone(); }
    public static final Type<StormLodPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_lod"));
    public static final StreamCodec<RegistryFriendlyByteBuf,StormLodPacket> STREAM_CODEC=StreamCodec.of(StormLodPacket::write,StormLodPacket::read);
    private static final ClientPayloadHandler<StormLodPacket> CLIENT=new ClientPayloadHandler<>();
    private static void write(RegistryFriendlyByteBuf b,StormLodPacket p){
        b.writeResourceLocation(p.identity.dimension());b.writeLong(p.identity.epoch());b.writeVarLong(p.subscription);b.writeByte(p.action);
        if(p.action==REMOVE)return;
        if(p.action==INIT){b.writeVarInt(p.limit);p.definition.write(b);p.previous.write(b);}
        b.writeBoolean(p.current!=null);if(p.current!=null)p.current.write(b);
        StormLodMask.write(b,p.deaths);
    }
    private static StormLodPacket read(RegistryFriendlyByteBuf b){
        var id=new StormIdentity(b.readResourceLocation(),b.readLong());long subscription=b.readVarLong();
        if(subscription<=0)throw new IllegalArgumentException("Invalid LOD subscription");
        int action=b.readUnsignedByte();
        if(action==REMOVE)return new StormLodPacket(id,subscription,action,0,null,null,null,new BitSet());
        if(action!=INIT&&action!=UPDATE)throw new IllegalArgumentException("Invalid LOD action");
        int limit=0;StormVisualDefinition definition=null;StormCenterSample previous=null;
        if(action==INIT){limit=b.readVarInt();if(limit<0||limit>8192)throw new IllegalArgumentException("Invalid LOD limit");definition=StormVisualDefinition.read(b);previous=StormCenterSample.read(b);}
        var current=b.readBoolean()?StormCenterSample.read(b):null;
        if(previous!=null&&!previous.valid()||current!=null&&!current.valid())throw new IllegalArgumentException("Invalid LOD sample");
        if(action==INIT&&(current==null||previous.tick()>current.tick()))throw new IllegalArgumentException("Invalid LOD initialization");
        return new StormLodPacket(id,subscription,action,limit,definition,previous,current,StormLodMask.read(b));
    }
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<StormLodPacket> handler){CLIENT.install(handler);}
    public static void handle(StormLodPacket p,IPayloadContext context){context.enqueueWork(()->CLIENT.dispatch(p));}
}
