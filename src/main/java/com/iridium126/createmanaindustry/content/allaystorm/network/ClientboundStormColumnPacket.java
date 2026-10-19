package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.content.allaystorm.*;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import io.netty.handler.codec.DecoderException;
import java.util.BitSet;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** One frozen regional column. No target entity or client-authored damage crosses this interface. */
public record ClientboundStormColumnPacket(ResourceLocation dimension,long epoch,int id,boolean abort,int seed,
        float start,float assemble,float touch,float bloomEnd,float end,float x,float y,float z,float gatherY,
        float centerX,float centerZ,float radius,int[] members) implements CustomPacketPayload {
    private static final ClientPayloadHandler<ClientboundStormColumnPacket> CLIENT=new ClientPayloadHandler<>();
    public static final Type<ClientboundStormColumnPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_column"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundStormColumnPacket> STREAM_CODEC=StreamCodec.of(ClientboundStormColumnPacket::encode,ClientboundStormColumnPacket::decode);
    public ClientboundStormColumnPacket aborted(){return new ClientboundStormColumnPacket(dimension,epoch,id,true,seed,start,assemble,touch,bloomEnd,end,x,y,z,gatherY,centerX,centerZ,radius,members);}
    public ClientboundStormWavePacket snapshot(){
        return new ClientboundStormWavePacket(id,epoch,abort?3:0,seed,-1,5,1,64,start,assemble,touch,end,x,gatherY,z,radius,members,
            new float[]{x,gatherY,z,x,y,z},new float[]{x,y,z,bloomEnd},StarfallColumnRules.COLUMN_KIND,abort?6:3,new int[]{64},centerX,y,centerZ,0,1,start,0);
    }
    private static void encode(RegistryFriendlyByteBuf b,ClientboundStormColumnPacket p){
        b.writeResourceLocation(p.dimension);b.writeLong(p.epoch);b.writeVarInt(p.id);b.writeBoolean(p.abort);if(p.abort)return;
        b.writeVarInt(p.seed);for(float f:new float[]{p.start,p.assemble,p.touch,p.bloomEnd,p.end,p.x,p.y,p.z,p.gatherY,p.centerX,p.centerZ,p.radius})b.writeFloat(f);
        for(int member:p.members)b.writeVarInt(member);
    }
    private static ClientboundStormColumnPacket decode(RegistryFriendlyByteBuf b){
        var dim=b.readResourceLocation();long epoch=b.readLong();int id=b.readVarInt();boolean abort=b.readBoolean();
        if(id<=0||id>=1<<24)throw new DecoderException("Invalid column id");
        if(abort)return new ClientboundStormColumnPacket(dim,epoch,id,true,0,0,0,0,0,0,0,0,0,0,0,0,2,new int[0]);
        int seed=b.readVarInt();float[] f=new float[12];for(int i=0;i<f.length;i++){f[i]=b.readFloat();if(!Float.isFinite(f[i]))throw new DecoderException("Nonfinite column");}
        if(f[1]<=f[0]||f[2]<=f[1]||f[3]<f[2]+StarfallColumnRules.BLOOM||f[4]<=f[3]||f[4]-f[0]>600||f[8]<=f[6]+3||f[11]<2||f[11]>64)
            throw new DecoderException("Invalid column geometry or schedule");
        if(Math.hypot(f[5]-f[9],f[7]-f[10])>f[11]+.01)throw new DecoderException("Column outside storm");
        int[] roster=new int[64];BitSet seen=new BitSet();for(int i=0;i<64;i++){int m=b.readVarInt();if(m<0||m>=AllayStarfall.MAX_MEMBERS||seen.get(m))throw new DecoderException("Invalid column roster");seen.set(m);roster[i]=m;}
        return new ClientboundStormColumnPacket(dim,epoch,id,false,seed,f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],f[8],f[9],f[10],f[11],roster);
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<? super ClientboundStormColumnPacket> handler){CLIENT.install(handler);}
    public static void handle(ClientboundStormColumnPacket p,IPayloadContext ctx){ctx.enqueueWork(()->CLIENT.dispatch(p));}
}
