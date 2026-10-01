package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Shared track upload, exact lease baseline and transaction ACK. No contents or address data. */
public record ClientboundChainPackagePacket(int action,ResourceLocation dimension,long epoch,Track track,
        PackageChainAuthority.Baseline baseline,ResourceLocation model,float width,float height,long sequence) implements CustomPacketPayload {
    public static final int TRACK=0,OFFER=1,FINAL=2,ACTIVE=3,RELEASED=4,ACK=5,CLOSE=6;
    public record Node(float threshold,int flags) {
        public Node {if(!Float.isFinite(threshold) || threshold<0 || flags!=1 && flags!=2 && flags!=4)throw new IllegalArgumentException("Chain node");}
    }
    public record Track(int index,BlockPos conveyor,BlockPos connection,PackageChainTrack geometry,List<Node> nodes,java.util.UUID parent) {
        public Track(int index,BlockPos conveyor,BlockPos connection,PackageChainTrack geometry,List<Node> nodes){this(index,conveyor,connection,geometry,nodes,null);}
        public Track {
            nodes=List.copyOf(nodes);
            if(index<0 || index>=131072 || conveyor==null || geometry==null || nodes.size()!=geometry.nodes() || geometry.firstNode()!=0
                    || (connection==null)!=geometry.looping())throw new IllegalArgumentException("Chain track packet");
            for(var node:nodes)if(geometry.looping()?node.threshold>=360:node.threshold>geometry.length())throw new IllegalArgumentException("Node outside track");
        }
    }
    public static final Type<ClientboundChainPackagePacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"chain_package_down"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundChainPackagePacket> STREAM_CODEC=StreamCodec.of(ClientboundChainPackagePacket::encode,ClientboundChainPackagePacket::decode);
    private static final ClientPayloadHandler<ClientboundChainPackagePacket> CLIENT=new ClientPayloadHandler<>();
    public ClientboundChainPackagePacket {
        if(action<TRACK || action>CLOSE || dimension==null || epoch<=0 || action==TRACK && track==null
                || action==ACK && sequence<0 || action>=OFFER && action<=RELEASED && (baseline==null || baseline.index()<0 || baseline.identity()==null
                || baseline.leaseEpoch()<=0 || baseline.revision()<=0 || baseline.track()<0 || baseline.track()>=131072 || baseline.trackRevision()<=0 || baseline.state()==null))
            throw new IllegalArgumentException("Chain baseline envelope");
        if(action==OFFER && (model==null || !Float.isFinite(width) || !Float.isFinite(height) || width<=0 || height<=0 || width>16 || height>16))throw new IllegalArgumentException("Chain model dimensions");
    }
    private static void vector(RegistryFriendlyByteBuf b,Vec3 v){b.writeDouble(v.x);b.writeDouble(v.y);b.writeDouble(v.z);}
    private static Vec3 vector(RegistryFriendlyByteBuf b){return new Vec3(b.readDouble(),b.readDouble(),b.readDouble());}
    // BlockPos's vanilla packed Y is only 12 bits. Allay/Sable locations retain signed axes.
    private static void block(RegistryFriendlyByteBuf b,BlockPos p){b.writeVarInt(p.getX());b.writeVarInt(p.getY());b.writeVarInt(p.getZ());}
    private static BlockPos block(RegistryFriendlyByteBuf b){return new BlockPos(b.readVarInt(),b.readVarInt(),b.readVarInt());}
    private static void encode(RegistryFriendlyByteBuf b,ClientboundChainPackagePacket p) {
        b.writeByte(p.action);b.writeResourceLocation(p.dimension);b.writeVarLong(p.epoch);
        if(p.action==CLOSE)return;
        if(p.action==ACK){b.writeVarLong(p.sequence);return;}
        if(p.action==TRACK) {
            var t=p.track;var g=t.geometry;b.writeVarInt(t.index);block(b,t.conveyor);b.writeBoolean(t.connection!=null);if(t.connection!=null)block(b,t.connection);
            vector(b,g.start());vector(b,g.end());b.writeFloat(g.radius());b.writeFloat(g.length());b.writeFloat(g.rate());b.writeBoolean(g.reversed());b.writeFloat(g.yaw());b.writeVarLong(g.revision());
            b.writeByte(t.nodes.size());for(var n:t.nodes){b.writeFloat(n.threshold);b.writeByte(n.flags);}
            b.writeBoolean(t.parent!=null);if(t.parent!=null)b.writeUUID(t.parent);return;
        }
        var v=p.baseline;b.writeVarInt(v.index());b.writeVarLong(v.identity().id());b.writeVarLong(v.identity().generation());
        b.writeVarLong(v.leaseEpoch());b.writeVarLong(v.revision());b.writeVarInt(v.track());b.writeVarLong(v.trackRevision());
        var s=v.state();b.writeFloat(s.progress());b.writeVarLong(s.tick());b.writeInt(v.eligibility());var q=s.pose();
        b.writeDouble(q.x());b.writeDouble(q.y());b.writeDouble(q.z());b.writeFloat(q.vx());b.writeFloat(q.vy());b.writeFloat(q.vz());b.writeFloat(q.yaw());
        if(p.action==OFFER){b.writeResourceLocation(p.model);b.writeFloat(p.width);b.writeFloat(p.height);}
    }
    private static ClientboundChainPackagePacket decode(RegistryFriendlyByteBuf b) {
        try {
            int action=b.readUnsignedByte();var dimension=b.readResourceLocation();long epoch=b.readVarLong();
            if(action==CLOSE)return new ClientboundChainPackagePacket(action,dimension,epoch,null,null,null,0,0,0);
            if(action==ACK)return new ClientboundChainPackagePacket(action,dimension,epoch,null,null,null,0,0,b.readVarLong());
            if(action==TRACK) {
                int index=b.readVarInt();var conveyor=block(b);BlockPos connection=b.readBoolean()?block(b):null;
                Vec3 start=vector(b),end=vector(b);float radius=b.readFloat(),length=b.readFloat(),rate=b.readFloat();boolean reverse=b.readBoolean();float yaw=b.readFloat();long revision=b.readVarLong();
                int count=b.readUnsignedByte();if(count>32)throw new IllegalArgumentException("Chain node count");
                var nodes=new java.util.ArrayList<Node>(count);for(int i=0;i<count;i++)nodes.add(new Node(b.readFloat(),b.readUnsignedByte()));
                var geometry=new PackageChainTrack(start,end,radius,length,rate,connection==null,reverse,yaw,0,count,revision);
                var parent=b.readBoolean()?b.readUUID():null;
                return new ClientboundChainPackagePacket(action,dimension,epoch,new Track(index,conveyor,connection,geometry,nodes,parent),null,null,0,0,0);
            }
            if(action<OFFER || action>RELEASED)throw new IllegalArgumentException("Chain action");
            int index=b.readVarInt();var identity=new PackageLease.Identity(b.readVarLong(),b.readVarLong());long lease=b.readVarLong(),revision=b.readVarLong();
            int track=b.readVarInt();long trackRevision=b.readVarLong();float progress=b.readFloat();long tick=b.readVarLong();int mask=b.readInt();
            var pose=new PackageLease.Pose(b.readDouble(),b.readDouble(),b.readDouble(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat());
            var baseline=new PackageChainAuthority.Baseline(index,identity,lease,revision,track,trackRevision,new PackageChainAuthority.State(progress,tick,pose),mask);
            var model=action==OFFER?b.readResourceLocation():null;float width=action==OFFER?b.readFloat():0,height=action==OFFER?b.readFloat():0;
            return new ClientboundChainPackagePacket(action,dimension,epoch,null,baseline,model,width,height,0);
        }catch(IllegalArgumentException invalid){throw new DecoderException("Invalid chain package baseline",invalid);}
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<? super ClientboundChainPackagePacket> handler){CLIENT.install(handler);}
    public static void handle(ClientboundChainPackagePacket packet,IPayloadContext context){context.enqueueWork(()->CLIENT.dispatch(packet));}
}
