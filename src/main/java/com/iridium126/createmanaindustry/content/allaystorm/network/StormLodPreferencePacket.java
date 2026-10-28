package com.iridium126.createmanaindustry.content.allaystorm.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StormLodPreferencePacket(int distance) implements CustomPacketPayload {
    public static final Type<StormLodPreferencePacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("createmanaindustry","storm_lod_preference"));
    public static final StreamCodec<RegistryFriendlyByteBuf,StormLodPreferencePacket> STREAM_CODEC=StreamCodec.of((b,p)->b.writeVarInt(p.distance),b->new StormLodPreferencePacket(b.readVarInt()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void handle(StormLodPreferencePacket p,IPayloadContext context){context.enqueueWork(()->{
        if(context.player() instanceof net.minecraft.server.level.ServerPlayer player)
            com.iridium126.createmanaindustry.content.allaystorm.lod.StormLodServer.preference(player,p.distance);
    });}
}
