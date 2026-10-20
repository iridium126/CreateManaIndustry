package com.iridium126.createmanaindustry.content.allaystorm.network;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
/** Authority reports an aggregate GPU readiness result, never member positions. */
public record StormAttackGatheredPacket(ResourceLocation dimension,long epoch,int id,long[] ready,long[] eligible) implements CustomPacketPayload {
 public static final Type<StormAttackGatheredPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_attack_gathered"));
 public static final StreamCodec<RegistryFriendlyByteBuf,StormAttackGatheredPacket> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeResourceLocation(p.dimension);b.writeLong(p.epoch);b.writeVarInt(p.id);b.writeLongArray(p.ready);b.writeLongArray(p.eligible);},b->{var d=b.readResourceLocation();long e=b.readLong();int id=b.readVarInt();if(id<=0||id>=1<<24)throw new io.netty.handler.codec.DecoderException("Invalid gathered attack");return new StormAttackGatheredPacket(d,e,id,b.readLongArray(new long[0],192),b.readLongArray(new long[0],192));});
 public Type<? extends CustomPacketPayload> type(){return TYPE;}
 public static void handle(StormAttackGatheredPacket p,IPayloadContext ctx){ctx.enqueueWork(()->com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager.gatheredAttack(p,ctx));}
}
