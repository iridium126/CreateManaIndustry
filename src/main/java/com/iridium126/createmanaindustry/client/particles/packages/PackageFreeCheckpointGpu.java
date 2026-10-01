package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.function.Function;

/** Free authority bodies only. Observers have the same box flags, so a committed physical
 * domain limit is mandatory; a visual observer identity can never become a recovery identity. */
public final class PackageFreeCheckpointGpu extends PackagePoseCheckpointGpu {
    public PackageFreeCheckpointGpu(int capacity,long epoch,Function<String,String> sources){this(capacity,epoch,sources,Transfer.DEVICE_COPY);}
    public PackageFreeCheckpointGpu(int capacity,long epoch,Function<String,String> sources,Transfer transfer){super(capacity,epoch,sources,transfer,Domain.FREE);}
    public boolean captureAuthority(PackagePoseQueryGpu.Input input,int freeBodyCount,long submission){return capture(input,freeBodyCount,submission);}
}
