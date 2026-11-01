package com.iridium126.createmanaindustry.client.particles.engine;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ClientFrameCaptureTest {
    @Test void percentileUsesOnlyFiniteCompletedMeasurements(){
        var result=ClientFrameCapture.statistics(new double[]{1,2,Double.NaN,4,Double.POSITIVE_INFINITY,1000},5);
        assertEquals(3,result.get("samples"));assertEquals(2.0,result.get("p50"));assertEquals(4.0,result.get("p95"));assertEquals(4.0,result.get("p99"));
    }
    @Test void EmptyGpuCompletionSetDoesNotFabricateAZeroTime(){
        assertEquals(java.util.Map.of("samples",0),ClientFrameCapture.statistics(new double[]{Double.NaN},1));
    }
    @Test void captureNamesCannotEscapeLocalDirectory(){
        var capture=com.iridium126.createmanaindustry.client.particles.engine.NeighborInputCapture.INSTANCE;
        assertFalse(capture.request("../outside"));assertFalse(capture.request("a\\b"));assertFalse(capture.request(""));assertFalse(capture.request("x".repeat(65)));
    }
}
