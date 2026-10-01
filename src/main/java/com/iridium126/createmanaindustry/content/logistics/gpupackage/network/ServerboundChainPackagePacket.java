package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ServerboundChainPackagePacket(int action,long epoch,int index,PackageLease.Identity identity,
        long leaseEpoch,long revision,int candidate,long sequence,byte[] events) implements CustomPacketPayload {
    public static final int HEARTBEAT=0,PREPARED=1,FINAL_READY=2,RELEASE=3,EVENTS=4;
    public static final Type<ServerboundChainPackagePacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"chain_package_up"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ServerboundChainPackagePacket> STREAM_CODEC=StreamCodec.of(ServerboundChainPackagePacket::encode,ServerboundChainPackagePacket::decode);
    public ServerboundChainPackagePacket {
        events=Objects.requireNonNull(events).clone();
        if(action<HEARTBEAT || action>EVENTS || epoch<=0 || events.length>PackageChainEventCodec.MAX_WIRE_BYTES
                || action==EVENTS && (sequence<0 || events.length==0) || action!=EVENTS && events.length!=0
                || action>=PREPARED && action<=RELEASE && (index<0 || identity==null || leaseEpoch<=0 || revision<=0)
                || action==PREPARED && (candidate<0 || candidate>=131072))throw new IllegalArgumentException("Chain control envelope");
    }
    @Override public byte[] events(){return events.clone();}
    public static ServerboundChainPackagePacket control(int action,long epoch,PackageChainAuthority.Baseline b,int candidate) {
        return new ServerboundChainPackagePacket(action,epoch,b.index(),b.identity(),b.leaseEpoch(),b.revision(),candidate,0,new byte[0]);
    }
    private static void encode(RegistryFriendlyByteBuf b,ServerboundChainPackagePacket p) {
        b.writeByte(p.action);b.writeVarLong(p.epoch);if(p.action==HEARTBEAT)return;
        if(p.action==EVENTS){b.writeVarLong(p.sequence);b.writeByteArray(p.events);return;}
        b.writeVarInt(p.index);b.writeVarLong(p.identity.id());b.writeVarLong(p.identity.generation());b.writeVarLong(p.leaseEpoch);b.writeVarLong(p.revision);
        if(p.action==PREPARED)b.writeVarInt(p.candidate);
    }
    private static ServerboundChainPackagePacket decode(RegistryFriendlyByteBuf b) {
        try {
            int action=b.readUnsignedByte();long epoch=b.readVarLong();
            if(action==HEARTBEAT)return new ServerboundChainPackagePacket(action,epoch,0,null,0,0,0,0,new byte[0]);
            if(action==EVENTS)return new ServerboundChainPackagePacket(action,epoch,0,null,0,0,0,b.readVarLong(),b.readByteArray(PackageChainEventCodec.MAX_WIRE_BYTES));
            if(action<PREPARED || action>RELEASE)throw new IllegalArgumentException("Chain control action");
            int index=b.readVarInt();var identity=new PackageLease.Identity(b.readVarLong(),b.readVarLong());long lease=b.readVarLong(),revision=b.readVarLong();int candidate=action==PREPARED?b.readVarInt():0;
            return new ServerboundChainPackagePacket(action,epoch,index,identity,lease,revision,candidate,0,new byte[0]);
        }catch(IllegalArgumentException invalid){throw new DecoderException("Invalid chain package control",invalid);}
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void handle(ServerboundChainPackagePacket packet,IPayloadContext context){context.enqueueWork(()->PackageChainAuthorityManager.receive(packet,context));}
}
