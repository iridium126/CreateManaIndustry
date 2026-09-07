package com.iridium126.createmanaindustry.dimension.storage;

import org.junit.jupiter.api.Test;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCoords;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 (plan §10.1): cube → region/local/slot mapping across negative
 * coordinates and the ±30 M / ±33.5 M software boundaries. All floorDiv /
 * floorMod arithmetic — a shift+mask regression on negatives fails here.
 */
class AllvrRegionIndexTest {

    @Test
    void negativeRegionBoundaries() {
        assertEquals(-1, AllvrRegionIndex.region(-1));
        assertEquals(15, AllvrRegionIndex.local(-1));
        assertEquals(-1, AllvrRegionIndex.region(-16));
        assertEquals(0, AllvrRegionIndex.local(-16));
        assertEquals(-2, AllvrRegionIndex.region(-17));
        assertEquals(15, AllvrRegionIndex.local(-17));
        assertEquals(0, AllvrRegionIndex.region(0));
        assertEquals(1, AllvrRegionIndex.region(16));
        assertEquals(0, AllvrRegionIndex.local(16));
        assertEquals(1, AllvrRegionIndex.region(17));
        assertEquals(1, AllvrRegionIndex.local(17));
    }

    @Test
    void regionLocalRoundTrip() {
        for (int cube = -1_100_000; cube <= 1_100_000; cube += 1) {
            int region = AllvrRegionIndex.region(cube);
            int local = AllvrRegionIndex.local(cube);
            assertTrue(local >= 0 && local < AllvrRegionIndex.DIAMETER, "local out of range at " + cube);
            assertEquals(cube, region * AllvrRegionIndex.DIAMETER + local, "not reversible at " + cube);
        }
    }

    @Test
    void slotLayoutIsYMajor() {
        assertEquals(0, AllvrRegionIndex.slot(0, 0, 0));
        assertEquals(1, AllvrRegionIndex.slot(1, 0, 0));
        assertEquals(16, AllvrRegionIndex.slot(0, 0, 1));
        assertEquals(256, AllvrRegionIndex.slot(0, 1, 0));
        assertEquals(AllvrStorageFormat.SLOTS - 1, AllvrRegionIndex.slot(15, 15, 15));
    }

    @Test
    void slotFromCubePosUsesInRegionLocals() {
        // cube (-1, -1, -1) lives in region (-1,-1,-1), local (15,15,15)
        AllvrCubePos pos = AllvrCubePos.of(-1, -1, -1);
        assertEquals(15, AllvrRegionIndex.local(pos.getX()));
        assertEquals(AllvrRegionIndex.slot(15, 15, 15), AllvrRegionIndex.slot(pos));
    }

    @Test
    void gameplayBoundariesMapReversibly() {
        // ±30 M gameplay Y bound and the vanilla world border, block → cube → region+local → block
        long[] blocks = {0, 31, 32, -1, -32, -33, 30_000_000, -30_000_000, 29_999_984, -29_999_984,
            33_554_431, -33_554_432, 33_554_431 - 31, -33_554_432 + 31};
        for (long block : blocks) {
            int b = (int) block;
            int cube = AllvrCoords.blockToCube(b);
            int local = AllvrCoords.blockToLocal(b);
            assertTrue(local >= 0 && local < AllvrCoords.DIAMETER_IN_BLOCKS, "blockLocal out of range at " + b);
            assertEquals(b, AllvrCoords.cubeToMinBlock(cube) + local, "block mapping not reversible at " + b);
            int region = AllvrRegionIndex.region(cube);
            int inRegion = AllvrRegionIndex.local(cube);
            assertEquals(cube, region * AllvrRegionIndex.DIAMETER + inRegion, "region mapping not reversible at " + b);
        }
    }

    @Test
    void regionKeyPacksAndUnpacks() {
        int[] extremes = {0, 1, -1, 16, -16, 65_536, -65_536, 65_535, -65_535};
        for (int rx : extremes) {
            for (int ry : extremes) {
                for (int rz : extremes) {
                    long key = AllvrRegionIndex.regionKey(rx, ry, rz);
                    assertEquals(rx, AllvrRegionIndex.regionKeyX(key));
                    assertEquals(ry, AllvrRegionIndex.regionKeyY(key));
                    assertEquals(rz, AllvrRegionIndex.regionKeyZ(key));
                }
            }
        }
    }

    @Test
    void fileNameRoundTrip() {
        int[][] coords = {{0, 0, 0}, {1, -2, 3}, {-65_536, 65_536, 0}};
        for (int[] c : coords) {
            String name = AllvrRegionIndex.fileName(c[0], c[1], c[2]);
            int[] parsed = AllvrRegionIndex.parseFileName(name);
            assertNotNull(parsed, "parse failed for " + name);
            assertEquals(c[0], parsed[0]);
            assertEquals(c[1], parsed[1]);
            assertEquals(c[2], parsed[2]);
        }
        assertNull(AllvrRegionIndex.parseFileName("r.1.2.txt"));
        assertNull(AllvrRegionIndex.parseFileName("x.1.2.3.3dr"));
        assertNull(AllvrRegionIndex.parseFileName("r.1.2.3.3dr.bak"));
        assertFalse(AllvrRegionIndex.parseFileName("level.dat") != null);
    }
}
