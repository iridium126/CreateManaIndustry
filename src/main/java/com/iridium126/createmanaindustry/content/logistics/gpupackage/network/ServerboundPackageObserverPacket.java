package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityManager;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Explicit observer opt-in. It grants no authority and cannot mutate a package or inventory. */
public record ServerboundPackageObserverPacket(int action,PackageRegion region,long stream) implements CustomPacketPayload {
    public static final int SUBSCRIBE=0,UNSUBSCRIBE=1;
    public static final Type<ServerboundPackageObserverPacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(
            CreateManaIndustry.MODID,"package_observer_up"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ServerboundPackageObserverPacket> STREAM_CODEC=StreamCodec.of(
            ServerboundPackageObserverPacket::encode,ServerboundPackageObserverPacket::decode);
    public ServerboundPackageObserverPacket {
        if(action<SUBSCRIBE || action>UNSUBSCRIBE || region==null || stream<0
                || action!=UNSUBSCRIBE && stream!=0 || action==UNSUBSCRIBE && stream==0)
            throw new IllegalArgumentException("Observer control");
    }
    private static void encode(RegistryFriendlyByteBuf b,ServerboundPackageObserverPacket p) {
        b.writeByte(p.action);ServerboundPackagePacket.writeRegion(b,p.region);if(p.action==UNSUBSCRIBE)b.writeVarLong(p.stream);
    }
    private static ServerboundPackageObserverPacket decode(RegistryFriendlyByteBuf b) {
        try {
            int action=b.readUnsignedByte();var region=ServerboundPackagePacket.readRegion(b);
            return new ServerboundPackageObserverPacket(action,region,action==UNSUBSCRIBE?b.readVarLong():0);
        }catch(IllegalArgumentException invalid){throw new DecoderException("Invalid observer control",invalid);}
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void handle(ServerboundPackageObserverPacket packet,IPayloadContext ctx){ctx.enqueueWork(()->PackageAuthorityManager.observe(packet,ctx));}
}
