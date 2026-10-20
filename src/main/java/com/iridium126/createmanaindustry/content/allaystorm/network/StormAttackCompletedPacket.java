package com.iridium126.createmanaindustry.content.allaystorm.network;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.function.Consumer;
/** Rank bitmap only: no member positions. Same bounded payload in each direction. */
public record StormAttackCompletedPacket(ResourceLocation dimension,long epoch,int id,long[] ranks) implements CustomPacketPayload {
 public static final Type<StormAttackCompletedPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_attack_completed"));
 private static final ClientPayloadHandler<StormAttackCompletedPacket> CLIENT=new ClientPayloadHandler<>();
 public static final StreamCodec<RegistryFriendlyByteBuf,StormAttackCompletedPacket> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeResourceLocation(p.dimension);b.writeLong(p.epoch);b.writeVarInt(p.id);b.writeLongArray(p.ranks);},b->{var d=b.readResourceLocation();long e=b.readLong();int id=b.readVarInt();long[] ranks=b.readLongArray(new long[0],192);if(id<=0||id>=1<<24)throw new io.netty.handler.codec.DecoderException("Invalid attack completion");return new StormAttackCompletedPacket(d,e,id,ranks);});
 public Type<? extends CustomPacketPayload> type(){return TYPE;}
 public static void installClientHandler(Consumer<? super StormAttackCompletedPacket> handler){CLIENT.install(handler);}
 public static void handle(StormAttackCompletedPacket p,IPayloadContext ctx){ctx.enqueueWork(()->{if(ctx.player() instanceof net.minecraft.server.level.ServerPlayer)com.iridium126.createmanaindustry.content.allaystorm.AllayStormManager.completeAttack(p,ctx);else CLIENT.dispatch(p);});}
}
