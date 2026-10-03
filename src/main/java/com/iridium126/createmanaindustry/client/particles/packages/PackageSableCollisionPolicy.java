package com.iridium126.createmanaindustry.client.particles.packages;

/** Readiness gate for optional Sable collision sources. */
final class PackageSableCollisionPolicy {
    static boolean trackable(boolean removed, boolean finalized, boolean hasPlot, boolean hasLoadedChunks) {
        return !removed && finalized && hasPlot && hasLoadedChunks;
    }

    private PackageSableCollisionPolicy() {}
}
