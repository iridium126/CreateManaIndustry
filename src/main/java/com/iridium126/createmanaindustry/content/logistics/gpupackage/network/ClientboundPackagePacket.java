package com.iridium126.createmanaindustry.content.logistics.gpupackage.network;

import java.util.UUID;
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

/** Pose-only protocol: package contents/address/inventory remain in Create's server objects. */
public record ClientboundPackagePacket(int action,ResourceLocation dimension,PackageRegion region,long epoch,
        long regionRevision,long sequence,PackageAuthorityRegion.Baseline baseline,int entityId,UUID entityUuid,
        ResourceLocation model,float width,float height) implements CustomPacketPayload {
    public static final int OFFER=0,FINAL_BASELINE=1,ACTIVE=2,RELEASED=3,ACK=4,DETACHED=5;
    public static final Type<ClientboundPackagePacket> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(CreateManaIndustry.MODID,"package_down"));
    public static final StreamCodec<RegistryFriendlyByteBuf,ClientboundPackagePacket> STREAM_CODEC=StreamCodec.of(
            ClientboundPackagePacket::encode,ClientboundPackagePacket::decode);
    private static final ClientPayloadHandler<ClientboundPackagePacket> CLIENT=new ClientPayloadHandler<>();
    public ClientboundPackagePacket {
        if(action<0 || action>DETACHED || dimension==null || region==null || epoch<=0 || regionRevision<=0)
            throw new IllegalArgumentException("Package server envelope");
        if(action==ACK) {if(sequence<0)throw new IllegalArgumentException("Package ACK sequence");}
        else if(baseline==null || baseline.index()<0 || baseline.leaseEpoch()<=0 || baseline.revision()<=0)
            throw new IllegalArgumentException("Package identity baseline");
        if(action==OFFER && (entityId< -1 || entityUuid==null || model==null || !Float.isFinite(width) || !Float.isFinite(height)
                || width<=0 || height<=0 || width>16 || height>16))throw new IllegalArgumentException("Package model/dimensions");
    }
    private static void encode(RegistryFriendlyByteBuf b,ClientboundPackagePacket p) {
        b.writeByte(p.action);b.writeResourceLocation(p.dimension);ServerboundPackagePacket.writeRegion(b,p.region);
        b.writeVarLong(p.epoch);b.writeVarLong(p.regionRevision);
        if(p.action==ACK){b.writeVarLong(p.sequence);return;}
        var v=p.baseline;b.writeVarInt(v.index());b.writeVarLong(v.identity().id());b.writeVarLong(v.identity().generation());
        b.writeVarLong(v.leaseEpoch());b.writeVarLong(v.revision());
        if(p.action==OFFER || p.action==FINAL_BASELINE) {
            var s=v.snapshot();var q=s.pose();b.writeDouble(q.x());b.writeDouble(q.y());b.writeDouble(q.z());
            b.writeFloat(q.vx());b.writeFloat(q.vy());b.writeFloat(q.vz());b.writeFloat(q.yaw());b.writeByte(s.flags());
        }
        if(p.action==OFFER){b.writeVarInt(p.entityId);b.writeUUID(p.entityUuid);b.writeResourceLocation(p.model);b.writeFloat(p.width);b.writeFloat(p.height);}
    }
    private static ClientboundPackagePacket decode(RegistryFriendlyByteBuf b) {
        try {
            int action=b.readUnsignedByte();ResourceLocation dimension=b.readResourceLocation();PackageRegion region=ServerboundPackagePacket.readRegion(b);
            long epoch=b.readVarLong(),revision=b.readVarLong();
            if(action==ACK)return new ClientboundPackagePacket(action,dimension,region,epoch,revision,b.readVarLong(),null,0,null,null,0,0);
            if(action<OFFER || action>DETACHED || action==ACK)throw new IllegalArgumentException("Package server action");
            int index=b.readVarInt();var identity=new PackageLease.Identity(b.readVarLong(),b.readVarLong());
            long lease=b.readVarLong(),base=b.readVarLong();
            var pose=new PackageLease.Pose(0,0,0,0,0,0,0);int flags=0;
            if(action==OFFER || action==FINAL_BASELINE){pose=new PackageLease.Pose(b.readDouble(),b.readDouble(),b.readDouble(),b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat());flags=b.readUnsignedByte();}
            var baseline=new PackageAuthorityRegion.Baseline(index,identity,lease,base,new PackageAuthorityRegion.Snapshot(pose,flags));
            int entityId=0;UUID uuid=null;ResourceLocation model=null;float width=0,height=0;
            if(action==OFFER){entityId=b.readVarInt();uuid=b.readUUID();model=b.readResourceLocation();width=b.readFloat();height=b.readFloat();}
            return new ClientboundPackagePacket(action,dimension,region,epoch,revision,0,baseline,entityId,uuid,model,width,height);
        }catch(IllegalArgumentException invalid){throw new DecoderException("Invalid package baseline",invalid);}
    }
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    public static void installClientHandler(Consumer<? super ClientboundPackagePacket> handler){CLIENT.install(handler);}
    public static void handle(ClientboundPackagePacket packet,IPayloadContext ctx){ctx.enqueueWork(()->CLIENT.dispatch(packet));}
}
