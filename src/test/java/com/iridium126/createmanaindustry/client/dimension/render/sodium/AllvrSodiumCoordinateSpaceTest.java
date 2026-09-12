package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AllvrSodiumCoordinateSpaceTest {

    @Test
    void negativeCoordinatesUseFloorDivision() {
        assertEquals(-1, AllvrSodiumCoordinateSpace.sectionOfBlock(-1));
        assertEquals(-1, AllvrSodiumCoordinateSpace.cubeOfSection(-1));
        assertEquals(-1, AllvrSodiumCoordinateSpace.cubeOfBlock(-1));
        assertEquals(1, AllvrSodiumCoordinateSpace.localSectionInCube(-1));
        assertEquals(7, AllvrSodiumCoordinateSpace.sliceIndexOfSection(-1, -1, -1));
    }

    @Test
    void cubeMapsToEightIndependentSections() {
        int n = 0;
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    assertEquals((y << 2) | (z << 1) | x,
                        AllvrSodiumCoordinateSpace.sliceIndexOfSection(x, y, z));
                    n++;
                }
            }
        }
        assertEquals(AllvrSodiumCoordinateSpace.SECTIONS_PER_CUBE, n);
    }

    @Test
    void extremeAbsoluteCoordinatesKeepTheirSectionAndCubeRoundTrip() {
        int[] blocks = {-29_999_900, -1_000_000, -33, -32, -1, 0, 31, 32, 1_000_000, 29_999_900};
        for (int block : blocks) {
            int section = AllvrSodiumCoordinateSpace.sectionOfBlock(block);
            int cube = AllvrSodiumCoordinateSpace.cubeOfSection(section);
            int local = AllvrSodiumCoordinateSpace.localSectionInCube(section);
            assertEquals(section, AllvrSodiumCoordinateSpace.cubeSection(cube, local));
            assertEquals(cube, AllvrSodiumCoordinateSpace.cubeOfBlock(block));
        }
    }

    @Test
    void sodiumContextMatchesLevelSliceNeighbourRadius() {
        assertEquals(1, AllvrSodiumSectionSource.CONTEXT_RADIUS);
        assertEquals(3, AllvrSodiumSectionSource.CONTEXT_SIDE);
        assertEquals(27, AllvrSodiumSectionSource.CONTEXT_SIZE);
    }
}
