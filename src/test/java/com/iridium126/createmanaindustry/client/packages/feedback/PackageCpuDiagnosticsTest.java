package com.iridium126.createmanaindustry.client.packages.feedback;


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PackageCpuDiagnosticsTest {
    @Test void stoppingPreservesTheLastReportAndStartingClearsIt(){
        try {
            PackageCpuDiagnostics.enabled(false);PackageCpuDiagnostics.enabled(true);
            PackageCpuDiagnostics.downloaded(32);PackageCpuDiagnostics.context(2,16,"old session");PackageCpuDiagnostics.frame();
            var captured=PackageCpuDiagnostics.report();PackageCpuDiagnostics.enabled(false);
            PackageCpuDiagnostics.downloaded(100);PackageCpuDiagnostics.context(9,99,"disabled");PackageCpuDiagnostics.frame();
            assertEquals(captured,PackageCpuDiagnostics.report());
            PackageCpuDiagnostics.enabled(true);assertEquals("",PackageCpuDiagnostics.report());
            PackageCpuDiagnostics.frame();var fresh=PackageCpuDiagnostics.report();
            assertEquals("",fresh,"Empty phases and workloads must be hidden");assertEquals("",PackageCpuDiagnostics.summary());
            PackageCpuDiagnostics.reset();assertEquals("",PackageCpuDiagnostics.report());
        }finally{PackageCpuDiagnostics.enabled(false);}
    }
    @Test void disabledPathHasNoClockTokenAndEnabledFramesExposePhaseAndWorkload(){
        PackageCpuDiagnostics.enabled(false);assertEquals(0,PackageCpuDiagnostics.begin(PackageCpuDiagnostics.Phase.SOLVER));
        try {
            PackageCpuDiagnostics.enabled(true);long start=PackageCpuDiagnostics.begin(PackageCpuDiagnostics.Phase.SOLVER);
            PackageCpuDiagnostics.end(PackageCpuDiagnostics.Phase.SOLVER,start);PackageCpuDiagnostics.step(2);PackageCpuDiagnostics.dispatch();PackageCpuDiagnostics.downloaded(64);
            PackageCpuDiagnostics.context(3,1024,"force history");PackageCpuDiagnostics.frame();var report=PackageCpuDiagnostics.report();
            assertTrue(report.contains("SOLVER"));assertTrue(report.contains("live/indices=3/1024"));assertTrue(report.contains("readback=64 B"));assertTrue(report.contains("waiting=force history"));assertFalse(report.contains("NaN"));
            assertFalse(report.contains("Package CPU CAPTURE"));assertFalse(report.contains("p99"));assertTrue(PackageCpuDiagnostics.summary().contains("SOLVER"));
        }finally{PackageCpuDiagnostics.enabled(false);}
    }
}
