package com.iridium126.createmanaindustry.client.particles.packages;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackageGpuIdentityReservationsTest {
    @Test void activeAndPreparedLifetimesCannotAlias() {
        var ids=new PackageGpuIdentityReservations();ids.reserve(5,1,0);
        assertThrows(IllegalArgumentException.class,()->ids.reserve(5,1,1));
        assertTrue(ids.contains(5,1));assertFalse(ids.retired(0));
    }
    @Test void reacquisitionUsesANewCandidateAndOldRetirementCannotReleaseIt() {
        var ids=new PackageGpuIdentityReservations();ids.reserve(5,1,0);ids.retire(5,1,0);
        assertFalse(ids.contains(5,1));assertTrue(ids.retired(0));
        ids.reserve(5,1,7);
        assertThrows(IllegalArgumentException.class,()->ids.retire(5,1,0));
        assertTrue(ids.contains(5,1));assertFalse(ids.retired(7));
        ids.retire(5,1,7);assertFalse(ids.contains(5,1));
        assertThrows(IllegalArgumentException.class,()->ids.reserve(6,1,0));
    }
    @Test void retirementRequiresTheCompleteIdentityAndCandidate() {
        var ids=new PackageGpuIdentityReservations();ids.reserve(5,2,3);
        assertThrows(IllegalArgumentException.class,()->ids.retire(5,1,3));
        assertThrows(IllegalArgumentException.class,()->ids.retire(5,2,4));
        assertTrue(ids.contains(5,2));
    }
    @Test void onlyAnEpochResetCanRecycleCandidateIndices() {
        var ids=new PackageGpuIdentityReservations();ids.reserve(5,1,0);ids.retire(5,1,0);
        ids.clear();ids.reserve(6,1,0);assertTrue(ids.contains(6,1));assertFalse(ids.retired(0));
    }
}
