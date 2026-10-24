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
    void coreAndHaloRemainInsidePackedBlockYRange() {
        for (int y : new int[]{-30_000_000, -4097, -2048, -1, 0, 2047, 2048, 4096, 30_000_000}) {
            int index = AllvrLightEngine.windowIndex(y);
            int origin = AllvrLightEngine.windowOriginY(index);
            int min = index * AllvrLightEngine.CORE_SIZE - AllvrLightEngine.HALO - origin;
            int max = (index + 1) * AllvrLightEngine.CORE_SIZE + AllvrLightEngine.HALO - 1 - origin;
            assertTrue(min > -2048 && max < 2047);
            assertEquals(AllvrLightEngine.VANILLA_MIN_Y, min);
            assertEquals(AllvrLightEngine.VANILLA_MAX_Y, max);
        }
        assertEquals(0, AllvrLightEngine.windowIndex(2047));
        assertEquals(1, AllvrLightEngine.windowIndex(2048));
        assertEquals(-1, AllvrLightEngine.windowIndex(-1));
    }
}
