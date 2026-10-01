package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import java.util.Objects;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundChainInteractionPacket(PackageChainInteraction request) implements CustomPacketPayload {
    public static final Type<ServerboundChainInteractionPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"chain_package_pick_up"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ServerboundChainInteractionPacket> STREAM_CODEC=StreamCodec.of(ServerboundChainInteractionPacket::encode,ServerboundChainInteractionPacket::decode);
    public ServerboundChainInteractionPacket {Objects.requireNonNull(request);}
    private static void encode(RegistryFriendlyByteBuf b,ServerboundChainInteractionPacket packet) {
        var r=packet.request;b.writeVarLong(r.epoch());b.writeVarLong(r.identity().id());b.writeVarLong(r.identity().generation());
        b.writeVarLong(r.leaseEpoch());b.writeVarLong(r.revision());b.writeVarInt(r.track());b.writeVarLong(r.trackRevision());
        b.writeVarLong(r.transaction());b.writeFloat(r.progress());
    }
    private static ServerboundChainInteractionPacket decode(RegistryFriendlyByteBuf b) {
        try{return new ServerboundChainInteractionPacket(new PackageChainInteraction(b.readVarLong(),new PackageLease.Identity(b.readVarLong(),b.readVarLong()),
                b.readVarLong(),b.readVarLong(),b.readVarInt(),b.readVarLong(),b.readVarLong(),b.readFloat()));}
        catch(IllegalArgumentException invalid){throw new DecoderException("Invalid chain pickup",invalid);}
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void handle(ServerboundChainInteractionPacket packet,IPayloadContext context) {
        context.enqueueWork(()->PackageChainAuthorityManager.receiveInteraction(packet,context));
    }
}
