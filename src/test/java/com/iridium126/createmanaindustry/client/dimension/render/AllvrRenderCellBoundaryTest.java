package com.iridium126.createmanaindustry.client.dimension.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AllvrRenderCellBoundaryTest {

    @Test
    void negativeBlockCoordinatesUseArithmeticCellDivision() {
        long key = AllvrRenderCellKey.ofBlock(-1, -16, -17);
        assertEquals(-1, AllvrRenderCellKey.cellX(key));
        assertEquals(-1, AllvrRenderCellKey.cellY(key));
        assertEquals(-2, AllvrRenderCellKey.cellZ(key));
        assertEquals(-16, AllvrRenderCellKey.minBlockX(key));
        assertEquals(-32, AllvrRenderCellKey.minBlockZ(key));
    }

    @Test
    void cubeExpandsToExactlyEightCellsAndRoundTrips() {
        long cube = com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(-7, 1000000, 9);
        int count = 0;
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    long cell = AllvrRenderCellKey.ofCube(cube, x, y, z);
                    assertEquals(-14 + x, AllvrRenderCellKey.cellX(cell));
                    assertEquals(2000000 + y, AllvrRenderCellKey.cellY(cell));
                    assertEquals(18 + z, AllvrRenderCellKey.cellZ(cell));
                    assertEquals(cube, AllvrRenderCellKey.cubeOf(cell));
                    count++;
                }
            }
        }
        assertEquals(8, count);
    }
}
