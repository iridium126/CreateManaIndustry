package com.iridium126.createmanaindustry.content.allaystorm;

import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormAttackKindTest {
    @Test void onlyColumnsUseTheVortexEnvelope() {
        double range = ServerConfig.stormWaveRange;
        try {
            ServerConfig.stormWaveRange = 96;
            float vortex = AllayStormData.vortexRadius(1 << 20);
            assertEquals(vortex, StormAttackKind.COLUMNS.range(1 << 20));
            assertTrue(StormAttackKind.COLUMNS.withinRange(vortex, 0, 0, 0, 1 << 20));
            assertFalse(StormAttackKind.COLUMNS.withinRange(vortex + 1, 0, 0, 0, 1 << 20));
            for (var kind : new StormAttackKind[]{StormAttackKind.RING, StormAttackKind.HELIX, StormAttackKind.DOME}) {
                assertEquals(96, kind.range(1 << 20));
                assertFalse(kind.withinRange(97, 0, 0, 0, 1 << 20));
                assertTrue(kind.withinRange(96, 0, 0, 0, 1 << 20));
            }
        } finally {
            ServerConfig.stormWaveRange = range;
        }
    }

    @Test void wireKindsAndThresholdsStayStable() {
        assertEquals(StormAttack.HELIX, StormAttackKind.HELIX.wireKind());
        assertEquals(StormAttack.DOME, StormAttackKind.DOME.wireKind());
        assertEquals(StormAttack.NONE, StormAttackKind.COLUMNS.wireKind());
        assertEquals(StormAttack.NONE, StormAttackKind.RING.wireKind());
        assertEquals(64, StormAttackKind.COLUMNS.minMembers());
        assertEquals(16, StormAttackKind.RING.minMembers());
        assertEquals(16384, StormAttackKind.HELIX.minMembers());
        assertEquals(AllayDome.MIN_MEMBERS, StormAttackKind.DOME.minMembers());
        assertTrue(StormAttackKind.COLUMNS.immediateFirstDraw());
        assertTrue(StormAttackKind.RING.immediateFirstDraw());
        assertFalse(StormAttackKind.HELIX.immediateFirstDraw());
        assertFalse(StormAttackKind.DOME.immediateFirstDraw());
    }
}