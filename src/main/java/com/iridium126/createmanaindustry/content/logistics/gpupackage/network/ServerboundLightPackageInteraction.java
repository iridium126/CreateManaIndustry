package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundLightPackageInteraction(PackageLease.Identity identity,boolean attack,InteractionHand hand) implements CustomPacketPayload {
    public static final Type<ServerboundLightPackageInteraction> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"light_package_interaction"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ServerboundLightPackageInteraction> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeVarLong(p.identity.id());b.writeVarLong(p.identity.generation());b.writeBoolean(p.attack);b.writeEnum(p.hand);},b->new ServerboundLightPackageInteraction(new PackageLease.Identity(b.readVarLong(),b.readVarLong()),b.readBoolean(),b.readEnum(InteractionHand.class)));
    public ServerboundLightPackageInteraction{java.util.Objects.requireNonNull(identity);java.util.Objects.requireNonNull(hand);}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void handle(ServerboundLightPackageInteraction p,IPayloadContext c){c.enqueueWork(()->PackageLightGameplay.interact(p,c));}
}
