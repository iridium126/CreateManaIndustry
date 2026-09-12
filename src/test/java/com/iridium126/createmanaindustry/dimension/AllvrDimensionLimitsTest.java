package com.iridium126.createmanaindustry.dimension;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllvrDimensionLimitsTest {
    @Test
    void blockSectionAndCubeOwnershipAgreeAtBothSeams() {
        for (int y : new int[] {-25600000, -160, -129, -128, -1, 0, 383, 384, 415, 25600000}) {
            boolean central = y >= -128 && y < 384;
            assertEquals(central, AllvrDimensionLimits.isVanillaY(y));
            assertEquals(central, AllvrDimensionLimits.isVanillaSection(y >> 4));
            assertEquals(central, AllvrDimensionLimits.isVanillaCube(y >> 5));
        }
    }

    @Test
    void nativePlayerTicketsPrefetchSeamsButNotRemoteCubeAltitudes() {
        assertTrue(AllvrDimensionLimits.intersectsVanillaView(-129, 8));
        assertTrue(AllvrDimensionLimits.intersectsVanillaView(384, 8));
        assertFalse(AllvrDimensionLimits.intersectsVanillaView(-25600000, 32));
        assertFalse(AllvrDimensionLimits.intersectsVanillaView(25600000, 32));
        assertTrue(AllvrDimensionLimits.intersectsVanillaView(527, 8));
        assertFalse(AllvrDimensionLimits.intersectsVanillaView(528, 8));
        assertTrue(AllvrDimensionLimits.intersectsVanillaView(-272, 8));
        assertFalse(AllvrDimensionLimits.intersectsVanillaView(-273, 8));
    }
}
