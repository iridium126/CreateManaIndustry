package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

/** Only acknowledges an already committed transaction; cannot request travel. */
public record RiftReadyPacket(UUID transaction) implements CustomPacketPayload {
    public static final Type<RiftReadyPacket> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"rift_ready"));
    public static final StreamCodec<RegistryFriendlyByteBuf,RiftReadyPacket> STREAM_CODEC=StreamCodec.of((b,p)->b.writeUUID(p.transaction),b->new RiftReadyPacket(b.readUUID()));
    public static void handle(RiftReadyPacket packet,IPayloadContext context){context.enqueueWork(()->{if(context.player() instanceof ServerPlayer player)StormRiftTransfers.ready(player,packet.transaction);});}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
