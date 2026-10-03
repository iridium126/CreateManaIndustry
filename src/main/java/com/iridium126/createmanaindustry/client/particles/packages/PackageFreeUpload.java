package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageDeltaCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;

/** Mutation-time bridge from an immutable server checkpoint to reusable GPU upload records.
 * It performs no world queries, GL calls or per-frame entity scans. Buffer positions are preserved. */
public final class PackageFreeUpload {
    private PackageFreeUpload() {}

    /** OFFER supplies the immutable model/identity; checkpoint is that OFFER or its newer FINAL_BASELINE.
     * All inputs are checked before any output changes. The caller owns allocation and upload batching. */
    public static void prepared(ClientboundPackagePacket offer,ClientboundPackagePacket checkpoint,
                                PackageModelCache.Style style,int bodyIndex,int packedLight,
                                ByteBuffer body,ByteBuffer poolMeta,ByteBuffer deltaMeta,ByteBuffer baseline) {
        if(offer==null)throw new IllegalArgumentException("Missing package offer");
        var region=offer.region();
        prepared(offer,checkpoint,style,bodyIndex,packedLight,region.originX(),region.originY(),region.originZ(),
                body,poolMeta,deltaMeta,baseline);
    }
    /** Shared physics domains can use a different origin; wire baselines remain region-local. */
    public static void prepared(ClientboundPackagePacket offer,ClientboundPackagePacket checkpoint,
                                PackageModelCache.Style style,int bodyIndex,int packedLight,double ox,double oy,double oz,
                                ByteBuffer body,ByteBuffer poolMeta,ByteBuffer deltaMeta,ByteBuffer baseline) {
        if(offer==null || checkpoint==null || style==null || offer.action()!=ClientboundPackagePacket.OFFER
                || (checkpoint.action()!=ClientboundPackagePacket.OFFER && checkpoint.action()!=ClientboundPackagePacket.FINAL_BASELINE)
                || !offer.dimension().equals(checkpoint.dimension()) || !offer.region().equals(checkpoint.region())
                || offer.epoch()!=checkpoint.epoch() || offer.regionRevision()!=checkpoint.regionRevision()
                || !offer.baseline().identity().equals(checkpoint.baseline().identity())
                || offer.baseline().index()!=checkpoint.baseline().index()
                || offer.baseline().leaseEpoch()!=checkpoint.baseline().leaseEpoch()
                || (checkpoint.action()==ClientboundPackagePacket.OFFER && !offer.equals(checkpoint))
                || (checkpoint.action()==ClientboundPackagePacket.FINAL_BASELINE
                    && checkpoint.baseline().revision()<=offer.baseline().revision())
                || bodyIndex<0 || style.box()<0 || offer.width()>2 || offer.height()>2
                || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz))
            throw new IllegalArgumentException("Package free upload identity/model/collider");
        output(body,PackagePhysicsGpu.BODY_BYTES);output(poolMeta,PackagePoolGpu.META_BYTES);
        output(deltaMeta,PackageDeltaGpu.META_BYTES);output(baseline,PackageDeltaGpu.BASELINE_BYTES);
        var region=offer.region();var snapshot=checkpoint.baseline().snapshot();var pose=snapshot.pose();
        if(!region.contains(pose) || Math.abs(pose.yaw())>1_000_000)
            throw new IllegalArgumentException("Package free upload outside region");
        var q=PackageDeltaCodec.quantize(pose,region.originX(),region.originY(),region.originZ(),snapshot.flags());
        double speed=(double)q.vx()*q.vx()+(double)q.vy()*q.vy()+(double)q.vz()*q.vz();
        long maxQuantizedSpeed=Math.round(PackageAuthorityRegion.MAX_PACKAGE_SPEED*PackageDeltaCodec.VELOCITY_SCALE);
        if(q.x()<0 || q.y()<0 || q.z()<0 || q.x()>=262144 || q.y()>=262144 || q.z()>=262144
                || speed>(double)maxQuantizedSpeed*maxQuantizedSpeed)
            throw new IllegalArgumentException("Package free upload requires full-state escape");
        float x=(float)(pose.x()-ox),z=(float)(pose.z()-oz);
        float halfWidth=offer.width()*.5f,halfHeight=offer.height()*.5f;
        float y=(float)(pose.y()-oy)+halfHeight;
        if(!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || Math.abs(x)>200_000_000 || Math.abs(y)>200_000_000 || Math.abs(z)>200_000_000)
            throw new IllegalArgumentException("Package free upload needs a closer physics origin");
        var b=clear(body,PackagePhysicsGpu.BODY_BYTES);int p=b.position();
        b.putFloat(p,x).putFloat(p+4,y).putFloat(p+8,z).putFloat(p+12,1);
        b.putFloat(p+16,pose.vx()).putFloat(p+20,pose.vy()).putFloat(p+24,pose.vz())
                .putFloat(p+28,(snapshot.flags()&PackageAuthorityRegion.GROUNDED)!=0?1:0);
        b.putFloat(p+32,halfWidth).putFloat(p+36,halfHeight).putFloat(p+40,halfWidth).putFloat(p+44,pose.yaw());
        b.putFloat(p+48,x).putFloat(p+52,y).putFloat(p+56,z).putFloat(p+60,PackagePhysicsGpu.PREPARED);
        var m=clear(poolMeta,PackagePoolGpu.META_BYTES);p=m.position();
        var identity=checkpoint.baseline().identity();
        m.putLong(p,identity.id()).putLong(p+8,identity.generation()).putInt(p+16,bodyIndex)
                .putInt(p+20,style.box()).putInt(p+24,PackagePoolGpu.NO_MESH).putInt(p+28,PackagePoolGpu.HIDDEN)
                .putInt(p+60,packedLight&0x00ffffff);
        // EntityDimensions defaults the eye probe to 85% of height; pool ground poses use feet.
        m.putFloat(p+76,offer.height()*.85f);
        var d=clear(deltaMeta,PackageDeltaGpu.META_BYTES);p=d.position();
        d.putLong(p,identity.id()).putLong(p+8,identity.generation()).putInt(p+16,bodyIndex)
                .putInt(p+20,checkpoint.baseline().index()); // ACTIVE stays zero until final handshake completes.
        writeBaseline(baseline,q);
    }
    private static void output(ByteBuffer buffer,int bytes) {
        if(buffer==null || !buffer.isDirect() || buffer.isReadOnly() || buffer.remaining()!=bytes)
            throw new IllegalArgumentException("Package upload record layout");
    }
    private static ByteBuffer clear(ByteBuffer buffer,int bytes) {
        var view=buffer.duplicate().order(ByteOrder.nativeOrder());
        for(int i=0;i<bytes;i+=4)view.putInt(view.position()+i,0);
        return view;
    }
    private static void writeBaseline(ByteBuffer buffer,PackageDeltaCodec.Quantized q) {
        var b=buffer.duplicate().order(ByteOrder.nativeOrder());int p=b.position();
        b.putInt(p,q.x()).putInt(p+4,q.y()).putInt(p+8,q.z()).putInt(p+12,q.flags());
        b.putInt(p+16,q.vx()).putInt(p+20,q.vy()).putInt(p+24,q.vz()).putInt(p+28,q.yaw());
    }
}
