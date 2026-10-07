package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.function.LongSupplier;

/** Give collision atlases an upload opportunity before sharing the spare frame budget. */
final class PackageCollisionUploadSchedule {
    enum Atlas { WORLD,MOVING,LIGHT }
    interface Work {
        long uploaded(Atlas atlas);
        void upload(Atlas atlas,int bytes,long nanos);
    }
    private PackageCollisionUploadSchedule(){}
    static void pump(Work work,boolean movingFirst,boolean lightFirst,boolean hasLight,int bytes,long nanos,LongSupplier clock) {
        if(bytes<0||nanos<0)throw new IllegalArgumentException("Shared upload budget");
        long started=clock.getAsLong();int remaining=bytes;
        Atlas first=movingFirst?Atlas.MOVING:Atlas.WORLD,second=movingFirst?Atlas.WORLD:Atlas.MOVING;
        Atlas[] order=lightFirst?new Atlas[]{Atlas.LIGHT,first,second}:new Atlas[]{first,second,Atlas.LIGHT};
        for(Atlas atlas:order) {
            if(atlas==Atlas.LIGHT&&!hasLight)continue;
            int share=atlas==Atlas.LIGHT?Math.min(bytes/3,PackageCollisionGpu.SLICE_BYTES):Math.min(bytes/3,5*PackageCollisionGpu.SLICE_BYTES);
            long time=Math.max(0,nanos-(clock.getAsLong()-started));
            remaining-=copy(work,atlas,Math.min(remaining,share),Math.min(time,nanos/3));
        }
        for(Atlas atlas:order) {
            if(atlas==Atlas.LIGHT&&!hasLight)continue;
            long time=Math.max(0,nanos-(clock.getAsLong()-started));if(remaining==0||time==0)break;
            remaining-=copy(work,atlas,remaining,time);
        }
    }
    private static int copy(Work work,Atlas atlas,int bytes,long nanos) {
        long before=work.uploaded(atlas);work.upload(atlas,bytes,nanos);long used=work.uploaded(atlas)-before;
        if(used<0||used>bytes)throw new IllegalStateException("Atlas exceeded shared upload allowance");
        return Math.toIntExact(used);
    }
}
