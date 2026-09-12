package com.iridium126.createmanaindustry.client.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.cube.AllvrVanillaRenderDistance;

import org.junit.jupiter.api.Test;

class AllvrClientRenderDistanceTest {

    @Test
    void vanillaDistanceConvertsToBlocksAndCubes() {
        assertEquals(192, AllvrClientRenderDistance.blocksForChunks(12));
        assertEquals(6, AllvrClientRenderDistance.cubeRadiusForChunks(12));
    }

    @Test
    void distanceHasTheSameMinimumAsMinecraftOptions() {
        assertEquals(32, AllvrClientRenderDistance.blocksForChunks(1));
        assertEquals(1, AllvrClientRenderDistance.cubeRadiusForChunks(1));
    }

    @Test
    void usesVanillaCircularChunkGeometry() {
        assertTrue(AllvrVanillaRenderDistance.isWithinVanillaChunkDistance(0, 0, 12, 12, 0));
        assertTrue(AllvrVanillaRenderDistance.isWithinVanillaChunkDistance(0, 0, 12, 8, 8));
        assertFalse(AllvrVanillaRenderDistance.isWithinVanillaChunkDistance(0, 0, 12, 10, 10));
        assertFalse(AllvrVanillaRenderDistance.isWithinVanillaChunkDistance(0, 0, 12, 14, 0));
    }

    @Test
    void keepsVerticalDistanceIndependent() {
        AllvrCubePos sameHeight = AllvrCubePos.of(6, 8, 0);
        AllvrCubePos justOutsideHeight = AllvrCubePos.of(6, 17, 0);
        assertTrue(AllvrVanillaRenderDistance.isCubeWithinCylinder(
            sameHeight, 0, 0, 8, 12, 8));
        assertFalse(AllvrVanillaRenderDistance.isCubeWithinCylinder(
            justOutsideHeight, 0, 0, 8, 12, 8));
    }
}
