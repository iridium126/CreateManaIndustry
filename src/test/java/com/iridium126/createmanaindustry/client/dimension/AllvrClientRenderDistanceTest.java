package com.iridium126.createmanaindustry.client.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
