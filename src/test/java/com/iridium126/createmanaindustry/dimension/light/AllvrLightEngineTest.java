package com.iridium126.createmanaindustry.dimension.light;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllvrLightEngineTest {

    @Test
    void absoluteYIsTranslatedIntoTheVanillaPackedRange() {
        int[] worldY = {-30_000_000, -4097, -4096, -2048, -1, 0, 2047, 2048,
            4095, 4096, 9613, 30_000_000};

        for (int y : worldY) {
            int localY = y - AllvrLightEngine.windowOriginY(AllvrLightEngine.windowIndex(y));
            assertTrue(localY >= AllvrLightEngine.VANILLA_MIN_Y &&
                localY <= AllvrLightEngine.VANILLA_MAX_Y,
                "Y " + y + " translated outside vanilla range: " + localY);
        }
    }

    @Test
    void windowBoundariesDoNotAliasAcrossThePackedYSignBit() {
        assertEquals(0, AllvrLightEngine.windowIndex(2047));
        assertEquals(0, AllvrLightEngine.windowIndex(4095));
        assertEquals(1, AllvrLightEngine.windowIndex(4096));
        assertEquals(-1, AllvrLightEngine.windowIndex(-4096));
        assertEquals(-2, AllvrLightEngine.windowIndex(-4097));

        assertEquals(AllvrLightEngine.VANILLA_MAX_Y,
            4095 - AllvrLightEngine.windowOriginY(0));
        assertEquals(AllvrLightEngine.VANILLA_MIN_Y,
            4096 - AllvrLightEngine.windowOriginY(1));
        assertEquals(AllvrLightEngine.VANILLA_MAX_Y,
            -1 - AllvrLightEngine.windowOriginY(-1));
        assertEquals(AllvrLightEngine.VANILLA_MIN_Y,
            -4096 - AllvrLightEngine.windowOriginY(-1));
    }
}
