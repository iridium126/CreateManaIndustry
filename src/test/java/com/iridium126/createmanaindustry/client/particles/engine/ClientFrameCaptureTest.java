package com.iridium126.createmanaindustry.client.particles.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ClientFrameCaptureTest {
    @Test void queuedCaptureWaitsForACompletedGameplayFrameAfterChatCloses() {
        var gate = new ClientFrameCapture.StartGate();
        assertFalse(gate.canStart(false));
        gate.beforeDisplay(true); // Chat closed during a frame that began with a menu.
        assertFalse(gate.canStart(true));
        gate.beforeDisplay(true);
        assertTrue(gate.canStart(true));

        assertFalse(gate.canStart(false)); // A menu reopened before capture could start.
        assertFalse(gate.canStart(true));
        gate.beforeDisplay(false);
        assertFalse(gate.canStart(true));
        gate.beforeDisplay(true);
        assertTrue(gate.canStart(true));
    }

    @Test void captureReportsTheChangedSubsystem() {
        var before = new ParticleDiagnostics.MeasurementContext("Sundial; session=2", "dxr", "shader",
                "FULL", false, "Iris composite9 1707x1067 -> 2560x1600", 0.6666667);
        assertEquals("", before.changeFrom(before));
        assertEquals("DLSS configuration or route changed", new ParticleDiagnostics.MeasurementContext(
                before.iris(), before.backend(), before.engine(), before.shadowQuality(), before.autoThrottle(),
                "original renderer", before.dlssScale()).changeFrom(before));
        assertEquals("DLSS configuration or route changed", new ParticleDiagnostics.MeasurementContext(
                before.iris(), before.backend(), before.engine(), before.shadowQuality(), before.autoThrottle(),
                before.dlss(), 0.6666668).changeFrom(before));
        assertEquals("shader session/options changed", new ParticleDiagnostics.MeasurementContext(
                "Sundial; session=3", before.backend(), before.engine(), before.shadowQuality(), before.autoThrottle(),
                before.dlss(), before.dlssScale()).changeFrom(before));
    }

    @Test void percentileUsesOnlyFiniteCompletedMeasurements(){
        var result=ClientFrameCapture.statistics(new double[]{1,2,Double.NaN,4,Double.POSITIVE_INFINITY,1000},5);
        assertEquals(3,result.get("samples"));assertEquals(2.0,result.get("p50"));assertEquals(4.0,result.get("p95"));assertEquals(4.0,result.get("p99"));
    }
    @Test void EmptyGpuCompletionSetDoesNotFabricateAZeroTime(){
        assertEquals(java.util.Map.of("samples",0),ClientFrameCapture.statistics(new double[]{Double.NaN},1));
    }
    @Test void captureNamesCannotEscapeLocalDirectory(){
        var capture=ParticleChainCapture.INSTANCE;
        assertFalse(capture.request("../outside"));assertFalse(capture.request("a\\b"));assertFalse(capture.request(""));assertFalse(capture.request("x".repeat(65)));
    }
}
