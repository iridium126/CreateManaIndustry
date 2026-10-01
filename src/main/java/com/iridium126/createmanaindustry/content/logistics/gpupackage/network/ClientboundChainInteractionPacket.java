package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import java.util.Objects;
import java.util.function.Consumer;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.infrastructure.network.ClientPayloadHandler;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ClientboundChainInteractionPacket(long epoch,PackageLease.Identity identity,long transaction,PackageChainAuthority.Result result) implements CustomPacketPayload {
    public static final Type<ClientboundChainInteractionPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"chain_package_pick_down"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundChainInteractionPacket> STREAM_CODEC=StreamCodec.of(ClientboundChainInteractionPacket::encode,ClientboundChainInteractionPacket::decode);
    private static final ClientPayloadHandler<ClientboundChainInteractionPacket> CLIENT=new ClientPayloadHandler<>();
    public ClientboundChainInteractionPacket {
        Objects.requireNonNull(identity);Objects.requireNonNull(result);
        if(epoch<=0 || transaction<=0)throw new IllegalArgumentException("Chain pickup confirmation");
    }
    private static void encode(RegistryFriendlyByteBuf b,ClientboundChainInteractionPacket p) {
        b.writeVarLong(p.epoch);b.writeVarLong(p.identity.id());b.writeVarLong(p.identity.generation());b.writeVarLong(p.transaction);b.writeByte(p.result.ordinal());
    }
    private static ClientboundChainInteractionPacket decode(RegistryFriendlyByteBuf b) {
        try {
            long epoch=b.readVarLong();var identity=new PackageLease.Identity(b.readVarLong(),b.readVarLong());long transaction=b.readVarLong();
            int code=b.readUnsignedByte();if(code>=PackageChainAuthority.Result.values().length)throw new IllegalArgumentException("Pickup result code");
            return new ClientboundChainInteractionPacket(epoch,identity,transaction,PackageChainAuthority.Result.values()[code]);
        }catch(IllegalArgumentException invalid){throw new DecoderException("Invalid chain pickup confirmation",invalid);}
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<? super ClientboundChainInteractionPacket> handler){CLIENT.install(handler);}
    public static void handle(ClientboundChainInteractionPacket packet,IPayloadContext context){context.enqueueWork(()->CLIENT.dispatch(packet));}
}
