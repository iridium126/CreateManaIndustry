package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainTrack;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;

/** Mutation-time wire/layout bridge. No world access, GL calls or per-frame object traversal. */
public final class PackageChainUpload {
    /** Create's pendulum velocity is displacement per tick, not blocks per second. */
    public record Pendulum(double x,double y,double z,float vx,float vy,float vz,float yaw) {
        public Pendulum {
            if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(vx)
                    || !Float.isFinite(vy) || !Float.isFinite(vz) || !Float.isFinite(yaw))
                throw new IllegalArgumentException("Chain pendulum pose");
        }
    }
    public record Previous(double x,double y,double z,float yaw,double targetX,double targetY,double targetZ) {
        public Previous {
            if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(yaw)
                    || !Double.isFinite(targetX) || !Double.isFinite(targetY) || !Double.isFinite(targetZ))
                throw new IllegalArgumentException("Chain checkpoint history");
        }
    }
    public record Checkpoint(float progress,double hookX,double hookY,double hookZ,float targetYaw,Pendulum pendulum,Previous previous) {}
    /** Validate the entire delayed result before changing any native gameplay/render fields. */
    public static Checkpoint checkpoint(PackagePoseQueryGpu.Result pose,PackageLease.Identity identity,
                                       ClientboundChainPackagePacket.Track track,double ox,double oy,double oz) {
        return checkpoint(pose,identity,track,ox,oy,oz,true);
    }
    /** Emergency/materialization snapshot: a completed visible pose is valid only for the
     * exact retained native claim. This does not acknowledge a retirement or a gameplay event. */
    public static Checkpoint retainedCheckpoint(PackagePoseQueryGpu.Result pose,PackageLease.Identity identity,
                                       ClientboundChainPackagePacket.Track track,double ox,double oy,double oz) {
        return checkpoint(pose,identity,track,ox,oy,oz,false);
    }
    private static Checkpoint checkpoint(PackagePoseQueryGpu.Result pose,PackageLease.Identity identity,
                                       ClientboundChainPackagePacket.Track track,double ox,double oy,double oz,boolean retiredOnly) {
        if(pose==null || identity==null || track==null || !pose.present() || !pose.chain()
                || retiredOnly && !pose.retired() || !pose.retired() && !(pose.state()>=0 && Float.isFinite(pose.state()))
                || pose.id()!=identity.id() || pose.generation()!=identity.generation() || pose.track()!=track.index()
                || !Float.isFinite(pose.progress()) || pose.progress()<0 || (track.geometry().looping()?pose.progress()>=360:pose.progress()>track.geometry().length())
                || pose.reversed()!=(track.geometry().reversed()?1:0)
                || pose.flags()!=(PackagePoolGpu.CHAIN|(pose.retired()?PackagePoolGpu.HIDDEN:0)|(track.geometry().reversed()?PackagePoolGpu.FLIPPED:0)|(pose.flags()&PackagePoolGpu.FRAMED))
                || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz))
            throw new IllegalArgumentException("Chain checkpoint identity/track/state");
        if((pose.flags()&PackagePoolGpu.FRAMED)!=0) {
            // Framed queries/checkpoints are native-conveyor local, including emergency
            // restoration after the moving parent disappeared. Never add the free origin.
            ox=track.conveyor().getX()+.5;oy=track.conveyor().getY()+.5;oz=track.conveyor().getZ()+.5;
        }
        var pendulum=new Pendulum(pose.x()+ox,pose.y()+oy,pose.z()+oz,pose.vx(),pose.vy(),pose.vz(),pose.yaw());
        double x=pose.tx()+ox,y=pose.ty()+oy+9.0/16,z=pose.tz()+oz;
        if(!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Float.isFinite(pose.targetYaw()))
            throw new IllegalArgumentException("Chain checkpoint hook");
        var previous=new Previous(pose.px()+ox,pose.py()+oy,pose.pz()+oz,pose.previousYaw(),pose.ptx()+ox,pose.pty()+oy,pose.ptz()+oz);
        return new Checkpoint(pose.progress(),x,y,z,pose.targetYaw(),pendulum,previous);
    }
    private PackageChainUpload() {}
    /** Append-only geometry namespace. Server headers use firstNode=0; the GPU table uses a
     * global node offset. Validate all outputs before writing reusable caller-owned storage. */
    public static void track(ClientboundChainPackagePacket.Track track,int firstNode,double ox,double oy,double oz,
                             ByteBuffer header,ByteBuffer nodes) {
        if(track==null || firstNode<0 || (long)firstNode+track.nodes().size()>1_048_576)
            throw new IllegalArgumentException("Chain node namespace");
        output(header,64);output(nodes,track.nodes().size()*16);
        var g=track.geometry();
        new PackageChainTrack(g.start(),g.end(),g.radius(),g.length(),g.rate(),g.looping(),g.reversed(),g.yaw(),
                firstNode,g.nodes(),g.revision()).write(header,ox,oy,oz);
        var v=clear(nodes,nodes.remaining());int p=v.position();
        for(int i=0;i<track.nodes().size();i++) {
            var n=track.nodes().get(i);v.putFloat(p+i*16,n.threshold()).putInt(p+i*16+4,n.flags());
        }
    }
    public static void prepared(ClientboundChainPackagePacket offer,ClientboundChainPackagePacket checkpoint,
            ClientboundChainPackagePacket.Track track,PackageModelCache.Style style,int bodyIndex,int packedLight,float hookDistance,
            double ox,double oy,double oz,Pendulum pendulum,
            ByteBuffer body,ByteBuffer chain,ByteBuffer poolMeta,ByteBuffer eventMeta) {
        prepared(offer,checkpoint,track,style,bodyIndex,packedLight,hookDistance,ox,oy,oz,pendulum,body,chain,poolMeta,eventMeta,false);
    }
    public static void prepared(ClientboundChainPackagePacket offer,ClientboundChainPackagePacket checkpoint,
            ClientboundChainPackagePacket.Track track,PackageModelCache.Style style,int bodyIndex,int packedLight,float hookDistance,
            double ox,double oy,double oz,Pendulum pendulum,
            ByteBuffer body,ByteBuffer chain,ByteBuffer poolMeta,ByteBuffer eventMeta,boolean framed) {
        if(offer==null || checkpoint==null || track==null || style==null || offer.action()!=ClientboundChainPackagePacket.OFFER
                || checkpoint.action()!=ClientboundChainPackagePacket.OFFER && checkpoint.action()!=ClientboundChainPackagePacket.FINAL
                || !offer.dimension().equals(checkpoint.dimension()) || offer.epoch()!=checkpoint.epoch()
                || !offer.baseline().identity().equals(checkpoint.baseline().identity())
                || offer.baseline().index()!=checkpoint.baseline().index() || offer.baseline().leaseEpoch()!=checkpoint.baseline().leaseEpoch()
                || offer.baseline().track()!=checkpoint.baseline().track() || offer.baseline().trackRevision()!=checkpoint.baseline().trackRevision()
                || checkpoint.baseline().track()!=track.index() || checkpoint.baseline().trackRevision()!=track.geometry().revision()
                || checkpoint.action()==ClientboundChainPackagePacket.OFFER && !offer.equals(checkpoint)
                || checkpoint.action()==ClientboundChainPackagePacket.FINAL && checkpoint.baseline().revision()<=offer.baseline().revision()
                || bodyIndex<0 || style.box()<0 || style.rig()<0 || !Float.isFinite(hookDistance) || hookDistance<0 || hookDistance>16
                || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz))
            throw new IllegalArgumentException("Chain upload identity/model/track");
        output(body,64);output(chain,64);output(poolMeta,80);output(eventMeta,32);
        var b=checkpoint.baseline();var g=track.geometry();float progress=b.state().progress();
        if((g.looping()?progress>=360:progress>g.length()) || g.nodes()<32 && b.eligibility()>>>g.nodes()!=0)
            throw new IllegalArgumentException("Chain progress/eligibility");
        // The authoritative baseline is the hook. Preserve the local Create pendulum when available.
        var hook=b.state().pose();
        float tx=local(hook.x(),ox),ty=local(hook.y()-9.0/16,oy),tz=local(hook.z(),oz);
        float x=pendulum==null?tx:local(pendulum.x(),ox),y=pendulum==null?ty:local(pendulum.y(),oy),z=pendulum==null?tz:local(pendulum.z(),oz);
        float yaw=pendulum==null?hook.yaw():pendulum.yaw();
        var v=clear(body,64);int p=v.position();
        v.putFloat(p,x).putFloat(p+4,y).putFloat(p+8,z).putFloat(p+12,1);
        if(pendulum!=null)v.putFloat(p+16,pendulum.vx()).putFloat(p+20,pendulum.vy()).putFloat(p+24,pendulum.vz());
        v.putFloat(p+32,offer.width()*.5f).putFloat(p+36,offer.height()*.5f).putFloat(p+40,offer.width()*.5f).putFloat(p+44,yaw);
        v.putFloat(p+48,x).putFloat(p+52,y).putFloat(p+56,z).putFloat(p+60,PackagePhysicsGpu.PREPARED);
        v=clear(chain,64);p=v.position();
        v.putFloat(p,progress).putFloat(p+12,track.index()).putFloat(p+32,progress).putFloat(p+36,g.rate())
                .putFloat(p+40,g.looping()?3:2).putFloat(p+44,g.reversed()?1:0)
                .putFloat(p+48,tx).putFloat(p+52,ty).putFloat(p+56,tz).putFloat(p+60,hook.yaw());
        v=clear(poolMeta,80);p=v.position();
        v.putLong(p,b.identity().id()).putLong(p+8,b.identity().generation()).putInt(p+16,bodyIndex)
                .putInt(p+20,style.box()).putInt(p+24,style.rig())
                .putInt(p+28,PackagePoolGpu.CHAIN|PackagePoolGpu.HIDDEN|(g.reversed()?PackagePoolGpu.FLIPPED:0)|(framed?PackagePoolGpu.FRAMED:0))
                .putFloat(p+56,hookDistance).putInt(p+60,packedLight&0x00ffffff);
        v=clear(eventMeta,32);p=v.position();
        v.putLong(p,b.identity().id()).putLong(p+8,b.identity().generation()).putInt(p+16,track.index()).putInt(p+20,b.eligibility());
    }
    private static float local(double coordinate,double origin) {
        float value=(float)(coordinate-origin);
        if(!Float.isFinite(value) || Math.abs(value)>200_000_000)throw new IllegalArgumentException("Chain requires a closer physics origin");
        return value;
    }
    private static void output(ByteBuffer v,int bytes) {
        if(v==null || !v.isDirect() || v.isReadOnly() || v.remaining()!=bytes)throw new IllegalArgumentException("Chain output layout");
    }
    private static ByteBuffer clear(ByteBuffer buffer,int bytes) {
        var v=buffer.duplicate().order(ByteOrder.nativeOrder());for(int i=0;i<bytes;i+=4)v.putInt(v.position()+i,0);return v;
    }
}
