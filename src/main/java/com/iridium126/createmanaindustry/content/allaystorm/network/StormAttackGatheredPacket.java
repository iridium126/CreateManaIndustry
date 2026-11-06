package com.iridium126.createmanaindustry.content.allaystorm.network;
import net.minecraft.resources.ResourceLocation;
import com.iridium126.createmanaindustry.content.allaystorm.DomeGatherState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
/** Authority reports an aggregate GPU readiness result, never member positions. */
public record StormAttackGatheredPacket(ResourceLocation dimension,long epoch,int id,long[] ready,long[] eligible,
        DomeGatherState flows,int referenceVersion,float sampleSec,long sampleTick,long[] completed) implements CustomPacketPayload {
 public StormAttackGatheredPacket{if(id<=0||id>=1<<24||referenceVersion<0||referenceVersion>=1<<24||!Float.isFinite(sampleSec)||sampleSec<0||sampleSec>600||sampleTick< -1||ready.length>192||eligible.length>192||completed.length>192)throw new io.netty.handler.codec.DecoderException("Invalid gather sample");}
 public StormAttackGatheredPacket(ResourceLocation dimension,long epoch,int id,long[] ready,long[] eligible,DomeGatherState flows,int referenceVersion,float sampleSec){this(dimension,epoch,id,ready,eligible,flows,referenceVersion,sampleSec,-1,new long[0]);}
 public StormAttackGatheredPacket(ResourceLocation dimension,long epoch,int id,long[] ready,long[] eligible,DomeGatherState flows){this(dimension,epoch,id,ready,eligible,flows,0,0);}
 public StormAttackGatheredPacket(ResourceLocation dimension,long epoch,int id,long[] ready,long[] eligible){this(dimension,epoch,id,ready,eligible,DomeGatherState.EMPTY);}
 public static final Type<StormAttackGatheredPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_attack_gathered"));
 public static final StreamCodec<RegistryFriendlyByteBuf,StormAttackGatheredPacket> STREAM_CODEC=StreamCodec.of(
     (b,p)->{b.writeResourceLocation(p.dimension);b.writeLong(p.epoch);b.writeVarInt(p.id);b.writeLongArray(p.ready);
       b.writeLongArray(p.eligible);p.flows.write(b);b.writeVarInt(p.referenceVersion);b.writeFloat(p.sampleSec);
       b.writeVarLong(p.sampleTick);b.writeLongArray(p.completed);},
     b->new StormAttackGatheredPacket(b.readResourceLocation(),b.readLong(),b.readVarInt(),b.readLongArray(new long[0],192),
       b.readLongArray(new long[0],192),DomeGatherState.read(b),b.readVarInt(),b.readFloat(),b.readVarLong(),b.readLongArray(new long[0],192)));
 public Type<? extends CustomPacketPayload> type(){return TYPE;}
 public static void handle(StormAttackGatheredPacket p,IPayloadContext ctx){ctx.enqueueWork(()->com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager.gatheredAttack(p,ctx));}
}
