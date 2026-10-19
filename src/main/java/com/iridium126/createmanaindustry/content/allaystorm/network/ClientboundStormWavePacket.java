package com.iridium126.createmanaindustry.content.allaystorm.network;

import com.iridium126.createmanaindustry.content.allaystorm.AllayStarfall;
import com.iridium126.createmanaindustry.content.allaystorm.AllayDome;
import com.iridium126.createmanaindustry.content.allaystorm.AllayHelix;
import com.iridium126.createmanaindustry.content.allaystorm.StormAttack;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import io.netty.handler.codec.DecoderException;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Version 4: explicit attack kind, phase and exclusive group boundaries. */
public record ClientboundStormWavePacket(int waveId,long epoch,int event,int waveSeed,int targetEntityId,int swordTier,
 int columns,int perColumn,float startSec,float assembleSec,float lockSec,float diveUntilSec,
 float crownX,float crownY,float crownZ,float crownRadius,int[] members,float[] path,float[] landings,
 int attackType,int phase,int[] groupEnds,float centerX,float centerY,float centerZ,float orientation,int direction,
 float centerSec,int fallbackReason,AllayHelix.Flight helix) implements CustomPacketPayload {
 private static final ClientPayloadHandler<ClientboundStormWavePacket> CLIENT_HANDLER=new ClientPayloadHandler<>();
 public static final Type<ClientboundStormWavePacket> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_wave"));
 public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundStormWavePacket> STREAM_CODEC=StreamCodec.of(ClientboundStormWavePacket::encode,ClientboundStormWavePacket::decode);
 /** Source compatibility for starfall fixtures; the version 3 wire is always explicit. */
 public ClientboundStormWavePacket(int id,long epoch,int event,int seed,int target,int tier,int columns,int per,
   float start,float assemble,float lock,float end,float x,float y,float z,float radius,int[] members,float[] path,float[] landings){
  this(id,epoch,event,seed,target,tier,columns,per,start,assemble,lock,end,x,y,z,radius,members,path,landings,
   AllayDome.STARFALL,event==AllayStarfall.LOCK?3:event==AllayStarfall.ABORT?6:1,
   AllayDome.columns(members.length>0?members.length:columns*per,Math.max(1,per)),0,0,0,0,1,start,0);
 }
 public ClientboundStormWavePacket(int id,long epoch,int event,int seed,int target,int tier,int columns,int per,
  float start,float assemble,float lock,float end,float x,float y,float z,float radius,int[] members,float[] path,float[] landings,
  int kind,int phase,int[] ends,float cx,float cy,float cz,float orientation,int direction,float centerSec,int fallback){
  this(id,epoch,event,seed,target,tier,columns,per,start,assemble,lock,end,x,y,z,radius,members,path,landings,kind,phase,ends,cx,cy,cz,orientation,direction,centerSec,fallback,null);
 }
 public boolean helixAttack(){return attackType==StormAttack.HELIX;}
 public boolean starfall(){return attackType==StormAttack.STARFALL||column();}
 public boolean column(){return attackType==com.iridium126.createmanaindustry.content.allaystorm.StarfallColumnRules.COLUMN_KIND;}
 public static ClientboundStormWavePacket abort(int id,long epoch){return new ClientboundStormWavePacket(id,epoch,3,0,0,0,0,0,0,0,0,0,0,0,0,0,new int[0],new float[0],new float[0]);}
 public static ClientboundStormWavePacket abort(int id,long epoch,int reason){return abort(id,epoch).reason(reason);}
 public ClientboundStormWavePacket reason(int value){return new ClientboundStormWavePacket(waveId,epoch,event,waveSeed,targetEntityId,swordTier,columns,perColumn,startSec,assembleSec,lockSec,diveUntilSec,crownX,crownY,crownZ,crownRadius,members,path,landings,attackType,phase,groupEnds,centerX,centerY,centerZ,orientation,direction,centerSec,value,helix);}
 public boolean abort(){return event==AllayStarfall.ABORT;}
 public boolean locked(){return phase>=3&&phase<=5;}
 public boolean dome(){return attackType==AllayDome.DOME;}
 public ClientboundStormWavePacket withMembers(int[] roster){return copy(event,phase,lockSec,diveUntilSec,roster);}
 public ClientboundStormWavePacket copy(int event,int phase,float lock,float end,int[] roster){return new ClientboundStormWavePacket(waveId,epoch,event,waveSeed,targetEntityId,swordTier,columns,perColumn,startSec,assembleSec,lock,end,crownX,crownY,crownZ,crownRadius,roster,path,landings,attackType,phase,groupEnds,centerX,centerY,centerZ,orientation,direction,centerSec,fallbackReason,helix);}
 private static void encode(RegistryFriendlyByteBuf b,ClientboundStormWavePacket p){
  b.writeVarInt(p.waveId);b.writeLong(p.epoch);b.writeByte(p.event);if(p.abort()){b.writeByte(p.fallbackReason);return;}
  b.writeByte(p.attackType);b.writeByte(p.phase);b.writeVarInt(p.waveSeed);b.writeVarInt(p.targetEntityId);b.writeByte(p.swordTier);b.writeByte(p.columns);b.writeVarInt(p.perColumn);
  for(float f:new float[]{p.startSec,p.assembleSec,p.lockSec,p.diveUntilSec,p.crownX,p.crownY,p.crownZ,p.crownRadius,p.centerX,p.centerY,p.centerZ,p.orientation,p.centerSec})b.writeFloat(f);
  b.writeByte(p.direction);b.writeByte(p.fallbackReason);for(int end:p.groupEnds)b.writeVarInt(end);
  b.writeVarInt(p.members.length);for(int m:p.members)b.writeVarInt(m);
  b.writeByte(p.path.length/3);for(float f:p.path)b.writeFloat(f);for(float f:p.landings)b.writeFloat(f);
  if(p.dome())b.writeVarInt(com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.VERSION);
  if(p.helixAttack()){b.writeVarInt(com.iridium126.createmanaindustry.content.allaystorm.HelixTemplate.VERSION);var h=p.helix;b.writeByte(h.armA());b.writeByte(h.armB());for(float f:new float[]{h.length(),h.radius(),h.maxSpeed(),h.speed(),h.charge(),h.thrust(),h.finish(),h.curvature(),h.targetArc(),h.axisX(),h.axisY(),h.axisZ()})b.writeFloat(f);b.writeByte(h.controls().length/3);for(float f:h.controls())b.writeFloat(f);for(float f:h.samples())b.writeFloat(f);}
 }
 private static ClientboundStormWavePacket decode(RegistryFriendlyByteBuf b){
  int id=b.readVarInt();long epoch=b.readLong();int event=b.readUnsignedByte();if(id<=0||event>3)throw new DecoderException("Invalid attack event");if(event==3){int reason=b.readUnsignedByte();if(reason>9)throw new DecoderException("Invalid abort reason");return abort(id,epoch,reason);}
  int kind=b.readUnsignedByte(),phase=b.readUnsignedByte(),seed=b.readVarInt(),target=b.readVarInt(),tier=b.readUnsignedByte(),columns=b.readUnsignedByte(),per=b.readVarInt();
  if(kind>2||phase<1||phase>5||columns<1||columns>32||per<1||per>8192||tier<1||tier>6||(kind==1&&(columns!=1||per!=8192))||(kind==2&&columns!=2))throw new DecoderException("Invalid attack dimensions");
  if(event==AllayStarfall.LOCK&&phase<3||event==AllayStarfall.UPDATE&&phase>=3)throw new DecoderException("Invalid attack phase");
  float[] f=new float[13];for(int i=0;i<f.length;i++)f[i]=finite(b);
  int direction=b.readByte(),fallback=b.readUnsignedByte();if(Math.abs(direction)!=1||fallback>5)throw new DecoderException("Invalid attack orientation");
  int[] ends=new int[columns];int previous=0;for(int g=0;g<columns;g++){ends[g]=b.readVarInt();if(ends[g]<=previous||ends[g]-previous>(kind==1?8192:4096)||ends[g]>AllayStarfall.MAX_MEMBERS)throw new DecoderException("Invalid attack groups");previous=ends[g];}
  if(kind==1&&previous!=8192)throw new DecoderException("Invalid dome roster size");
  if(kind==2&&(previous!=4096||ends[0]!=2048))throw new DecoderException("Invalid helix roster");
  int count=b.readVarInt();if(count<0||count>AllayStarfall.MAX_MEMBERS||(count!=0&&count!=previous)||(event==0&&count==0))throw new DecoderException("Invalid attack roster");
  if(kind==0&&!java.util.Arrays.equals(ends,AllayDome.columns(previous,per)))throw new DecoderException("Invalid starfall groups");
  if(kind==1&&!java.util.Arrays.equals(ends,AllayDome.groups(previous)))throw new DecoderException("Invalid dome groups");
  int[] members=new int[count];java.util.BitSet seen=new java.util.BitSet();for(int i=0;i<count;i++){int m=b.readVarInt();if(m<0||m>=AllayStarfall.MAX_MEMBERS||seen.get(m))throw new DecoderException("Invalid attack member");seen.set(m);members[i]=m;}
  int points=b.readUnsignedByte();if(points<2||points>6)throw new DecoderException("Invalid attack route");float[] path=new float[points*3];for(int i=0;i<path.length;i++)path[i]=finite(b);
  float[] land=new float[columns*4];for(int i=0;i<land.length;i++)land[i]=finite(b);
  if(f[7]<=0||f[7]>128||(kind==1&&f[7]!=32))throw new DecoderException("Invalid attack radius");
  if(f[1]<f[0]||f[2]<f[1]||f[3]<f[2]||f[3]-f[0]>600)throw new DecoderException("Invalid attack schedule");
  if(kind==1&&b.readVarInt()!=com.iridium126.createmanaindustry.content.allaystorm.DomeTemplate.VERSION)throw new DecoderException("Unknown dome template");
  AllayHelix.Flight helix=null;
  if(kind==2){
   if(b.readVarInt()!=com.iridium126.createmanaindustry.content.allaystorm.HelixTemplate.VERSION)throw new DecoderException("Unknown helix template");
   int a=b.readUnsignedByte(),other=b.readUnsignedByte();float[] h=new float[12];for(int i=0;i<h.length;i++)h[i]=finite(b);
   int controlCount=b.readUnsignedByte();if(a>2||other!=a+3||controlCount<2||controlCount>6||h[0]!=64||h[1]!=32||h[2]<6||h[2]>18||h[3]<=0||h[3]>h[2]||h[7]<0||h[7]>1/(4*h[1]))throw new DecoderException("Invalid helix geometry");
   if(Math.abs(h[9]*h[9]+h[10]*h[10]+h[11]*h[11]-1)>.002)throw new DecoderException("Invalid helix axis");
   float[] controls=new float[controlCount*3],samples=new float[AllayHelix.SAMPLES*4];
   for(int i=0;i<controls.length;i++)controls[i]=finite(b);float previousArc=-1;
   for(int i=0;i<samples.length;i++){samples[i]=finite(b);if(i%4==3){if(samples[i]<0||samples[i]<=previousArc)throw new DecoderException("Invalid helix arc");previousArc=samples[i];}}
   if(previousArc<=0||previousArc>1024||h[8]<-32||h[8]>=previousArc||h[4]<f[1]||h[5]<h[4]||h[6]<=h[5]||h[6]>f[3])throw new DecoderException("Invalid helix schedule");
   helix=new AllayHelix.Flight(a,other,h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7],h[8],controls,samples,h[9],h[10],h[11]);
  }
  return new ClientboundStormWavePacket(id,epoch,event,seed,target,tier,columns,per,f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],members,path,land,kind,phase,ends,f[8],f[9],f[10],f[11],direction,f[12],fallback,helix);
 }
 private static float finite(RegistryFriendlyByteBuf b){float f=b.readFloat();if(!Float.isFinite(f))throw new DecoderException("Nonfinite attack parameter");return f;}
 @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
 public static void installClientHandler(Consumer<? super ClientboundStormWavePacket> handler){CLIENT_HANDLER.install(handler);}
 public static void handle(ClientboundStormWavePacket packet,IPayloadContext ctx){ctx.enqueueWork(()->CLIENT_HANDLER.dispatch(packet));}
}
