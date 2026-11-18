package com.iridium126.createmanaindustry.client.particles.engine.spatial;

import com.iridium126.createmanaindustry.infrastructure.config.StormSimulationGpu;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormSimulationGpuTest {
    @Test void defaultFollowsTheOpenGlRenderer() {
        assertEquals(StormSimulationGpu.FOLLOW_MINECRAFT, StormSimulationGpu.parse("minecraft"));
        assertEquals("Arc A750", StormGpuDevices.shortModel("Intel(R) Arc(TM) A750 Graphics"));
        assertEquals("RTX 4070 Laptop", StormGpuDevices.shortModel("NVIDIA GeForce RTX 4070 Laptop GPU/PCIe/SSE2"));
        assertEquals("Radeon 610M", StormGpuDevices.shortModel("AMD Radeon(TM) 610M"));
    }

    @Test void explicitDeviceRetainsItsLabelWithoutTheDriver() {
        var choice = StormSimulationGpu.parse("gpu-12345678-90AB-1234-5678-90ABCDEF1234|RTX 4070 Laptop");
        assertFalse(choice.followsMinecraft());
        assertEquals("GPU-12345678-90ab-1234-5678-90abcdef1234", choice.uuid());
        assertEquals("RTX 4070 Laptop", choice.label());
        assertEquals(choice, StormSimulationGpu.parse(choice.value()));
        assertFalse(StormSimulationGpu.valid("RTX 4070 Laptop"));
        assertFalse(StormSimulationGpu.valid("GPU-1234|RTX 4070 Laptop"));
    }
}
