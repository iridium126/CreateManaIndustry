package com.iridium126.createmanaindustry.client.particles.packages;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PackageCpuDiagnosticsTest {
    @Test void disabledPathHasNoClockTokenAndEnabledFramesExposePhaseAndWorkload(){
        PackageCpuDiagnostics.enabled(false);assertEquals(0,PackageCpuDiagnostics.begin(PackageCpuDiagnostics.Phase.SOLVER));
        try {
            PackageCpuDiagnostics.enabled(true);long start=PackageCpuDiagnostics.begin(PackageCpuDiagnostics.Phase.SOLVER);
            PackageCpuDiagnostics.end(PackageCpuDiagnostics.Phase.SOLVER,start);PackageCpuDiagnostics.step(2);PackageCpuDiagnostics.dispatch();PackageCpuDiagnostics.downloaded(64);
            PackageCpuDiagnostics.context(3,1024,"force history");PackageCpuDiagnostics.frame();var report=PackageCpuDiagnostics.report();
            assertTrue(report.contains("SOLVER"));assertTrue(report.contains("live/indices=3/1024"));assertTrue(report.contains("downloaded=64"));assertTrue(report.contains("waiting=force history"));assertFalse(report.contains("NaN"));
        }finally{PackageCpuDiagnostics.enabled(false);}
    }
}
