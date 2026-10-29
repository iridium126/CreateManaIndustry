package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.content.allaystorm.StormOuterLoop;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.List;
import java.util.function.Consumer;

/** Bounded lifecycle batches; arrival packets contain identities, never positions. */
public record StormOuterPacket(ResourceLocation dimension,long epoch,int operation,int[] members,int[] cycles,float[] targets) implements CustomPacketPayload {
    public static final int START=0,RELEASE=1,ARRIVED=2,LIMIT=1024;
    public StormOuterPacket {
        if(operation<0||operation>2||members.length>LIMIT||members.length!=cycles.length||targets.length!=(operation==START?members.length*3:0))throw new IllegalArgumentException("Outer loop batch");
        for(int i=0;i<members.length;i++)if(members[i]<0||members[i]>=131072||cycles[i]<=0)throw new IllegalArgumentException("Outer loop identity");
        for(float value:targets)if(!Float.isFinite(value))throw new IllegalArgumentException("Outer loop target");
    }
    public static final Type<StormOuterPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_outer"));
    private static final ClientPayloadHandler<StormOuterPacket> CLIENT=new ClientPayloadHandler<>();
    public static final StreamCodec<RegistryFriendlyByteBuf,StormOuterPacket> STREAM_CODEC=StreamCodec.of((b,p)->{
        b.writeResourceLocation(p.dimension);b.writeLong(p.epoch);b.writeByte(p.operation);b.writeVarInt(p.members.length);
        for(int i=0;i<p.members.length;i++){b.writeVarInt(p.members[i]);b.writeVarInt(p.cycles[i]);if(p.operation==START)for(int k=0;k<3;k++)b.writeFloat(p.targets[i*3+k]);}
    },b->{var d=b.readResourceLocation();long epoch=b.readLong();int op=b.readUnsignedByte(),n=b.readVarInt();if(op>2||n<0||n>LIMIT)throw new io.netty.handler.codec.DecoderException("Outer loop batch");
        int[] members=new int[n],cycles=new int[n];float[] targets=new float[op==START?n*3:0];
        for(int i=0;i<n;i++){members[i]=b.readVarInt();cycles[i]=b.readVarInt();if(op==START)for(int k=0;k<3;k++)targets[i*3+k]=b.readFloat();}
        return new StormOuterPacket(d,epoch,op,members,cycles,targets);
    });
    public static StormOuterPacket batch(ResourceLocation dimension,long epoch,int operation,List<StormOuterLoop.Flight> flights){
        int n=flights.size();int[] members=new int[n],cycles=new int[n];float[] targets=new float[operation==START?n*3:0];
        for(int i=0;i<n;i++){var f=flights.get(i);members[i]=f.member();cycles[i]=f.cycle();if(operation==START){targets[i*3]=f.x();targets[i*3+1]=f.y();targets[i*3+2]=f.z();}}
        return new StormOuterPacket(dimension,epoch,operation,members,cycles,targets);
    }
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<? super StormOuterPacket> handler){CLIENT.install(handler);}
    public static void handle(StormOuterPacket packet,IPayloadContext context){context.enqueueWork(()->{
        if(context.player() instanceof net.minecraft.server.level.ServerPlayer)com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager.outerArrived(packet,context);
        else if(packet.operation!=ARRIVED)CLIENT.dispatch(packet);
    });}
}
