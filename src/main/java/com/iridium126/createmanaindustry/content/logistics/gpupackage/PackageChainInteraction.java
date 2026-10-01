package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.Objects;

/** Exact gameplay identity. Contains neither a pool index nor client inventory/world position. */
public record PackageChainInteraction(long epoch,PackageLease.Identity identity,long leaseEpoch,long revision,
                                      int track,long trackRevision,long transaction,float progress) {
    public PackageChainInteraction {
        Objects.requireNonNull(identity);
        if(epoch<=0 || leaseEpoch<=0 || revision<=0 || track<0 || track>=131072 || trackRevision<=0
                || transaction<=0 || !Float.isFinite(progress) || progress<0)
            throw new IllegalArgumentException("Chain interaction envelope");
    }
}
