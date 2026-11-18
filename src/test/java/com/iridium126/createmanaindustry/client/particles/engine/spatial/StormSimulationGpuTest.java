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
        String hash = "AB".repeat(32);
        var choice = StormSimulationGpu.parse("pnp-" + hash + "|RTX 4070 Laptop");
        assertFalse(choice.followsMinecraft());
        assertEquals("PNP-" + "ab".repeat(32), choice.uuid());
        assertEquals("RTX 4070 Laptop", choice.label());
        assertEquals(choice, StormSimulationGpu.parse(choice.value()));
        assertFalse(StormSimulationGpu.valid("RTX 4070 Laptop"));
        assertFalse(StormSimulationGpu.valid("PNP-1234|RTX 4070 Laptop"));
    }

    @Test void hidingTheRendererPreservesOtherCardsOfTheSameModelAndSavedPins() {
        var first = StormSimulationGpu.parse("PNP-" + "a".repeat(64) + "|Arc A750");
        var second = StormSimulationGpu.parse("PNP-" + "b".repeat(64) + "|Arc A750");
        var renderer = new StormGpuDevices.Device(first, "01:00.0", 1, true, "");
        var sameModel = new StormGpuDevices.Device(second, "02:00.0", 2, true, "");
        assertFalse(renderer.visible(1, ""));
        assertTrue(renderer.visible(1, first.uuid()));
        assertTrue(renderer.visible(0, ""));
        assertTrue(sameModel.visible(1, ""));
    }
}
