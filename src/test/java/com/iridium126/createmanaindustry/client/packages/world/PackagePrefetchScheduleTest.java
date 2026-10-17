package com.iridium126.createmanaindustry.client.packages.world;


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PackagePrefetchScheduleTest {
    @Test void stableFramesDeduplicateButPendingCoverageAndUsageStillRetry(){
        var s=new PackagePrefetchSchedule();assertTrue(s.due(0,1,2,3));s.submitted(0,1,2,3);
        for(long frame=1;frame<50_000_000;frame+=1_000_000)assertFalse(s.due(frame,1,2,3));
        assertTrue(s.due(50_000_000,1,2,3));
    }
    @Test void everyDependencyChangesImmediatelyAndRefusedRingCannotConsumeRetry(){
        var s=new PackagePrefetchSchedule();s.submitted(0,1,2,3);
        assertTrue(s.due(1,4,2,3));assertTrue(s.due(1,1,4,3));assertTrue(s.due(1,1,2,4));
        // A refused submission does not call submitted; identical repeated request remains due.
        assertTrue(s.due(2,4,2,3));s.reset();assertTrue(s.due(2,1,2,3));
    }
}
