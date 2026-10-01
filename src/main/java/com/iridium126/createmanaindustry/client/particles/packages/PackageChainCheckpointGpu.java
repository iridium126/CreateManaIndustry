package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.function.Function;

/** Chain-only facade; shares fenced checkpoint storage with the free domain. */
public final class PackageChainCheckpointGpu extends PackagePoseCheckpointGpu {
    public PackageChainCheckpointGpu(int capacity,long epoch,Function<String,String> sources){this(capacity,epoch,sources,Transfer.DEVICE_COPY);}
    public PackageChainCheckpointGpu(int capacity,long epoch,Function<String,String> sources,Transfer transfer){super(capacity,epoch,sources,transfer,Domain.CHAIN);}
}
