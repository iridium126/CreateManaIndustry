package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageDeltaCodec;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;

/** Mutation-only fixed GPU command writer. It copies exact fields; all pose merging, prediction
 * and correction happens on GPU. The reusable caller buffer must have native order. Positions
 * are unchanged so the caller can address/batch records without allocating duplicate views. */
public final class PackageObserverPatch {
    private PackageObserverPatch() {}
    public static void baseline(ByteBuffer out,int local,int serverIndex,PackageLease.Identity identity,
                                long epoch,long stream,long sequence,PackageDeltaCodec.Quantized state,
                                float originX,float originY,float originZ,float width,float height,float receipt) {
        validate(out,local,serverIndex,identity,epoch,stream,sequence,receipt);
        if(state==null || !Float.isFinite(originX) || !Float.isFinite(originY) || !Float.isFinite(originZ)
                || Math.abs(originX)>4096 || Math.abs(originY)>4096 || Math.abs(originZ)>4096
                || !Float.isFinite(width) || !Float.isFinite(height) || width<=0 || height<=0 || width>2 || height>2)
            throw new IllegalArgumentException("Observer baseline geometry");
        header(out,local,serverIndex,identity,epoch,stream,sequence,PackageObserverGpu.BASELINE,receipt);
        state(out,state);int p=out.position();
        out.putFloat(p+80,originX).putFloat(p+84,originY).putFloat(p+88,originZ);
        out.putFloat(p+96,width*.5f).putFloat(p+100,height*.5f).putFloat(p+104,width*.5f);
    }
    public static void delta(ByteBuffer out,int local,PackageLease.Identity identity,long epoch,long stream,
                             long sequence,PackageDeltaCodec.Entry change,float receipt) {
        if(change==null)throw new IllegalArgumentException("Observer delta");
        validate(out,local,change.id(),identity,epoch,stream,sequence,receipt);
        header(out,local,change.id(),identity,epoch,stream,sequence,change.mask(),receipt);state(out,change.value());
    }
    /** Mutation-only tuple. The GPU resolves full identity from the non-reused namespace and
     * rejects a mismatched stream, server index, empty or retired destination atomically. */
    public static void compactDelta(ByteBuffer out,int local,long epoch,long stream,long sequence,
                                    PackageDeltaCodec.Entry change,float receipt) {
        if(out==null || !out.isDirect() || out.isReadOnly() || out.order()!=ByteOrder.nativeOrder()
                || out.remaining()<PackageObserverGpu.COMPACT_BYTES || local<0 || epoch<=0 || stream<=0
                || sequence<0 || change==null || !Float.isFinite(receipt) || receipt<0)
            throw new IllegalArgumentException("Observer compact patch layout/namespace/time");
        var q=change.value();int p=out.position();
        out.putLong(p,epoch).putLong(p+8,stream).putInt(p+16,local).putInt(p+20,change.id())
                .putInt(p+24,change.mask()).putFloat(p+28,receipt);
        out.putInt(p+32,q.x()).putInt(p+36,q.y()).putInt(p+40,q.z()).putInt(p+44,q.flags());
        out.putInt(p+48,q.vx()).putInt(p+52,q.vy()).putInt(p+56,q.vz()).putInt(p+60,q.yaw())
                .putLong(p+64,sequence).putLong(p+72,0);
    }
    private static void validate(ByteBuffer out,int local,int serverIndex,PackageLease.Identity identity,
                                 long epoch,long stream,long sequence,float receipt) {
        if(out==null || !out.isDirect() || out.isReadOnly() || out.order()!=ByteOrder.nativeOrder()
                || out.remaining()<PackageObserverGpu.PATCH_BYTES || local<0 || serverIndex<0 || identity==null
                || epoch<=0 || stream<=0 || sequence<0 || !Float.isFinite(receipt) || receipt<0)
            throw new IllegalArgumentException("Observer patch identity/layout/time");
    }
    private static void header(ByteBuffer out,int local,int index,PackageLease.Identity identity,long epoch,long stream,
                                long sequence,int mask,float receipt) {
        int p=out.position();for(int i=0;i<PackageObserverGpu.PATCH_BYTES;i+=4)out.putInt(p+i,0);
        out.putLong(p,identity.id()).putLong(p+8,identity.generation()).putLong(p+16,epoch).putLong(p+24,stream);
        out.putInt(p+32,local).putInt(p+36,index).putInt(p+40,mask).putFloat(p+92,receipt).putLong(p+112,sequence);
    }
    private static void state(ByteBuffer out,PackageDeltaCodec.Quantized q) {
        int p=out.position();out.putInt(p+48,q.x()).putInt(p+52,q.y()).putInt(p+56,q.z()).putInt(p+60,q.flags());
        out.putInt(p+64,q.vx()).putInt(p+68,q.vy()).putInt(p+72,q.vz()).putInt(p+76,q.yaw());
    }
}
