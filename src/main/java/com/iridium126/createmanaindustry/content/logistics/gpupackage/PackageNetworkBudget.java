package com.iridium126.createmanaindustry.content.logistics.gpupackage;

/** Current release scope: further bandwidth optimization/comparison is deferred by the user.
 * This is diagnostic information, not an admission gate. The existing relative-position
 * encoding, batched ACK/controls and light-record observer replication remain in use. Experimental
 * opt-in, negotiated channels, resource coverage and exact ownership checks are independent.
 * Historical strict comparisons remain in PackageNetworkComparison for the later release;
 * deferral does not turn incomplete or over-budget evidence into a passing measurement. */
public final class PackageNetworkBudget {
    private PackageNetworkBudget() {}
    public static String status(){return "bandwidth optimization deferred; existing relative/batched synchronization";}
}
