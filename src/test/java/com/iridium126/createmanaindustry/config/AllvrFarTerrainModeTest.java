package com.iridium126.createmanaindustry.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Far-terrain backend selection semantics that are unit-testable without a
 * running game (sodium-parity plan §11.4, M1 §6.1): the stored lodBackend
 * string parses into the reduced AUTO/VOXY/OFF enum, LEGACY (a value written
 * before the legacy-LOD removal) migrates to AUTO instead of failing the
 * config load, and unknown values degrade to AUTO as well.
 */
class AllvrFarTerrainModeTest {

    @Test
    void canonicalValues() {
        assertEquals(ClientConfig.AllvrLodBackendMode.AUTO, ClientConfig.parseBackendMode("AUTO"));
        assertEquals(ClientConfig.AllvrLodBackendMode.VOXY, ClientConfig.parseBackendMode("VOXY"));
        assertEquals(ClientConfig.AllvrLodBackendMode.OFF, ClientConfig.parseBackendMode("OFF"));
    }

    @Test
    void legacyMigratesToAuto() {
        assertEquals(ClientConfig.AllvrLodBackendMode.AUTO, ClientConfig.parseBackendMode("LEGACY"));
        assertEquals(ClientConfig.AllvrLodBackendMode.AUTO, ClientConfig.parseBackendMode("legacy"));
    }

    @Test
    void unknownDegradesToAuto() {
        assertEquals(ClientConfig.AllvrLodBackendMode.AUTO, ClientConfig.parseBackendMode("SODIUM_BRIDGE"));
        assertEquals(ClientConfig.AllvrLodBackendMode.AUTO, ClientConfig.parseBackendMode(""));
        assertEquals(ClientConfig.AllvrLodBackendMode.AUTO, ClientConfig.parseBackendMode(null));
    }

    @Test
    void caseAndWhitespaceTolerant() {
        assertEquals(ClientConfig.AllvrLodBackendMode.VOXY, ClientConfig.parseBackendMode("voxy"));
        assertEquals(ClientConfig.AllvrLodBackendMode.OFF, ClientConfig.parseBackendMode(" off "));
    }
}
