package com.iridium126.createmanaindustry.client.particles.packages;

import static org.junit.jupiter.api.Assertions.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import org.junit.jupiter.api.Test;

class PackageFreeNativeRecoveryTest {
    private static final PackageRegion REGION=new PackageRegion(-10,40,5);
    private static final PackageAuthorityRegion.Baseline EXPECTED=baseline(7,11,13,19,2);
    private static final PackageFreeNativeRecovery.Pose NATIVE=new PackageFreeNativeRecovery.Pose(-640.125,2560.5,321.75,-34,0,true);
    private static PackageAuthorityRegion.Baseline baseline(int index,long id,long generation,long lease,long revision){
        return new PackageAuthorityRegion.Baseline(index,new PackageLease.Identity(id,generation),lease,revision,
                new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(0,0,0,0,0,0,0),0));
    }
    @Test void inFlightNativePoseIsIgnoredUntilExactTerminalIdentityAndFinalRevision() {
        var recovery=new PackageFreeNativeRecovery(REGION,23,EXPECTED);
        recovery.teleported(NATIVE);assertNull(recovery.pose());
        assertFalse(recovery.released(new PackageRegion(-9,40,5),23,EXPECTED));
        assertFalse(recovery.released(REGION,24,EXPECTED));
        for(var foreign:new PackageAuthorityRegion.Baseline[]{baseline(8,11,13,19,2),baseline(7,12,13,19,2),
                baseline(7,11,14,19,2),baseline(7,11,13,20,2),baseline(7,11,13,19,1)}) {
            assertFalse(recovery.released(REGION,23,foreign));recovery.teleported(NATIVE);assertNull(recovery.pose());
        }
        assertTrue(recovery.released(REGION,23,EXPECTED));assertNull(recovery.pose());
        recovery.teleported(NATIVE);assertEquals(NATIVE,recovery.pose());
        assertTrue(recovery.released(REGION,23,EXPECTED));assertEquals(NATIVE,recovery.pose());
    }
    @Test void nativeRecoverySupersedesTheGpuCacheAndLaterRebaseCanUpdateThePendingHandback() {
        var recovery=new PackageFreeNativeRecovery(REGION,23,EXPECTED);
        assertNull(recovery.pose()); // Handback falls back to the last completed GPU pose.
        assertTrue(recovery.released(REGION,23,EXPECTED));recovery.teleported(NATIVE);
        var rebase=new PackageFreeNativeRecovery.Pose(-639.5,2560.5,321.75,-35,0,false);
        recovery.teleported(rebase);assertEquals(rebase,recovery.pose());
        assertFalse(recovery.released(REGION,23,baseline(7,11,14,19,2)));assertEquals(rebase,recovery.pose());
        assertThrows(IllegalArgumentException.class,()->new PackageFreeNativeRecovery.Pose(Double.NaN,0,0,0,0,false));
    }
}
