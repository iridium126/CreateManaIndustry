package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import java.util.Objects;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Bounded client control/delta stream. Region/index refer to a server-established identity baseline. */
public record ServerboundPackagePacket(int action,int capabilities,PackageRegion region,long epoch,int index,
        PackageLease.Identity identity,long leaseEpoch,long revision,long sequence,byte[] changes,long simulationStep) implements CustomPacketPayload {
    public static final int CAPABILITIES=0,PREPARED=1,FINAL_READY=2,HEARTBEAT=3,RELEASE=4,DELTA=5;
    public static final int BATCH_DELTA=6;
    public static final int RELATIVE_DELTA=7;
    public static final int PREDICTED_DELTA=8;
    /** Sent only after ACTIVE and a successful visible GPU admission for this exact baseline. */
    public static final int VISIBLE_READY=9;
    public static final int CONTROL_BATCH=10,ENVIRONMENT=11;
    public static final int FREE_READY=1,CHAIN_READY=2,MAX_BYTES=24576;
    public static final Type<ServerboundPackagePacket> TYPE=new Type<>(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"package_up"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ServerboundPackagePacket> STREAM_CODEC=StreamCodec.of(
            ServerboundPackagePacket::encode,ServerboundPackagePacket::decode);
    public ServerboundPackagePacket(int action,int capabilities,PackageRegion region,long epoch,int index,PackageLease.Identity identity,long leaseEpoch,long revision,long sequence,byte[] changes){
        this(action,capabilities,region,epoch,index,identity,leaseEpoch,revision,sequence,changes,0);
    }
    public ServerboundPackagePacket {
        if(action<0 || action>ENVIRONMENT || (capabilities&~3)!=0)throw new IllegalArgumentException("Package action/capabilities");
        if(simulationStep<0||!deltaAction(action)&&simulationStep!=0)throw new IllegalArgumentException("Package simulation step");
        changes=Objects.requireNonNull(changes).clone();
        if(changes.length>MAX_BYTES)throw new IllegalArgumentException("Package packet too large");
        if(action!=CAPABILITIES && (region==null || epoch<=0))throw new IllegalArgumentException("Package region/epoch");
        if((action==PREPARED || action==FINAL_READY || action==VISIBLE_READY || action==RELEASE)
                && (index<0 || identity==null || leaseEpoch<=0 || revision<=0))throw new IllegalArgumentException("Package baseline");
        if(deltaAction(action) && (sequence<0 || revision<=0 || changes.length==0))throw new IllegalArgumentException("Package delta");
        if(action==CONTROL_BATCH&&(revision<=0||sequence!=0||changes.length==0||changes.length>PackageControlBatchCodec.MAX_BYTES))throw new IllegalArgumentException("Package controls");
        if(action==ENVIRONMENT&&(revision<=0||changes.length==0))throw new IllegalArgumentException("Package environment event");
        if(!deltaAction(action)&&action!=CONTROL_BATCH&&action!=ENVIRONMENT&&changes.length!=0)throw new IllegalArgumentException("Unexpected package body");
    }
    @Override public byte[] changes(){return changes.clone();}
    public static ServerboundPackagePacket capabilities(int flags){return new ServerboundPackagePacket(CAPABILITIES,flags,null,0,0,null,0,0,0,new byte[0]);}
    public static ServerboundPackagePacket control(int action,PackageRegion region,long epoch,PackageAuthorityRegion.Baseline baseline) {
        return new ServerboundPackagePacket(action,0,region,epoch,baseline.index(),baseline.identity(),baseline.leaseEpoch(),baseline.revision(),0,new byte[0]);
    }
    public static ServerboundPackagePacket controls(PackageRegion region,long epoch,long revision,byte[] body) {
        return new ServerboundPackagePacket(CONTROL_BATCH,0,region,epoch,0,null,0,revision,0,body);
    }
    private static void encode(RegistryFriendlyByteBuf b,ServerboundPackagePacket p) {
        b.writeByte(p.action);
        if(p.action==CAPABILITIES){b.writeByte(p.capabilities);return;}
        writeRegion(b,p.region);b.writeVarLong(p.epoch);
        if(p.action==HEARTBEAT)return;
        if(p.action==ENVIRONMENT){b.writeVarLong(p.revision);b.writeByteArray(p.changes);return;}
        if(p.action==CONTROL_BATCH){b.writeVarLong(p.revision);b.writeByteArray(p.changes);return;}
        if(deltaAction(p.action)){b.writeVarLong(p.revision);b.writeVarLong(p.sequence);b.writeVarLong(p.simulationStep);b.writeByteArray(p.changes);return;}
        b.writeVarInt(p.index);b.writeVarLong(p.identity.id());b.writeVarLong(p.identity.generation());
        b.writeVarLong(p.leaseEpoch);b.writeVarLong(p.revision);
    }
    private static ServerboundPackagePacket decode(RegistryFriendlyByteBuf b) {
        try {
            int action=b.readUnsignedByte();
            if(action==CAPABILITIES)return capabilities(b.readUnsignedByte());
            if(action<PREPARED || action>ENVIRONMENT)throw new IllegalArgumentException("Package action");
            PackageRegion region=readRegion(b);long epoch=b.readVarLong();
            if(action==HEARTBEAT)return new ServerboundPackagePacket(action,0,region,epoch,0,null,0,0,0,new byte[0]);
            if(action==ENVIRONMENT)return new ServerboundPackagePacket(action,0,region,epoch,0,null,0,b.readVarLong(),0,b.readByteArray(MAX_BYTES));
            if(action==CONTROL_BATCH)return controls(region,epoch,b.readVarLong(),b.readByteArray(PackageControlBatchCodec.MAX_BYTES));
            if(deltaAction(action)){long revision=b.readVarLong(),sequence=b.readVarLong(),step=b.readVarLong();return new ServerboundPackagePacket(action,0,region,epoch,0,null,0,revision,sequence,b.readByteArray(MAX_BYTES),step);}
            return new ServerboundPackagePacket(action,0,region,epoch,b.readVarInt(),new PackageLease.Identity(b.readVarLong(),b.readVarLong()),
                    b.readVarLong(),b.readVarLong(),0,new byte[0]);
        }catch(IllegalArgumentException | IndexOutOfBoundsException invalid){throw new DecoderException("Invalid package control",invalid);}
    }
    public static boolean deltaAction(int action){return action==DELTA || action==BATCH_DELTA || action==RELATIVE_DELTA || action==PREDICTED_DELTA;}
    static void writeRegion(RegistryFriendlyByteBuf b,PackageRegion r){b.writeVarInt(r.x());b.writeVarInt(r.y());b.writeVarInt(r.z());}
    static PackageRegion readRegion(RegistryFriendlyByteBuf b){return new PackageRegion(b.readVarInt(),b.readVarInt(),b.readVarInt());}
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void handle(ServerboundPackagePacket packet,IPayloadContext ctx) {
        ctx.enqueueWork(()->PackageAuthorityManager.receive(packet,ctx));
    }
}
