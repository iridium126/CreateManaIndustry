package com.iridium126.createmanaindustry.client.gpu.profile;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuPassTelemetryTest {
    @Test void disabledTelemetryNeedsNoOpenGlContext() {
        try(var telemetry=new GpuPassTelemetry()) {
            for(int i=0;i<10;i++){telemetry.configure(false,false);telemetry.begin(false,false);telemetry.end();}
            assertEquals(0,telemetry.pending());assertEquals(0,telemetry.cpuSamples());
            assertEquals(0,telemetry.gpuSamples());assertEquals(0,telemetry.skipped());
            assertEquals(0,telemetry.poll());assertEquals("unmeasured",telemetry.report());
        }
    }
}
