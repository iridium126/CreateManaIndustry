package com.iridium126.createmanaindustry.dimension.lod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AllvrLodBandsTest {

    @Test
    void everyLevelSharesTheSameWorldSpaceCoverage() {
        assertEquals(129, AllvrLodBands.bitmapBoxCells(0, 2048));
        assertEquals(65, AllvrLodBands.bitmapBoxCells(1, 2048));
        assertEquals(33, AllvrLodBands.bitmapBoxCells(2, 2048));
        assertEquals(17, AllvrLodBands.bitmapBoxCells(3, 2048));
    }

    @Test
    void coverageDoesNotSelectAnLodLevelByDistance() {
        assertTrue(AllvrLodBands.inCoverage(0, 2048, 2048));
        assertTrue(AllvrLodBands.inCoverage(3, 2048, 2048));
        assertFalse(AllvrLodBands.inCoverage(0, 2049, 2048));
        assertEquals(192,
            AllvrLodBands.nearestDistanceBlocks(0, 6, 0, 0, 0, 0, 0));
    }
}
