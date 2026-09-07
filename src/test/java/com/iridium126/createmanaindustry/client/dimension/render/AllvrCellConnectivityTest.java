package com.iridium126.createmanaindustry.client.dimension.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class AllvrCellConnectivityTest {

    @Test
    void airCellConnectsOnAllSixFaces() {
        byte[] occludes = new byte[AllvrCellMesher.PADDED * AllvrCellMesher.PADDED
            * AllvrCellMesher.PADDED];
        assertEquals(0x3F, AllvrCellConnectivity.mask(occludes));
    }

    @Test
    void solidCellHasNoOpenFaces() {
        byte[] occludes = new byte[AllvrCellMesher.PADDED * AllvrCellMesher.PADDED
            * AllvrCellMesher.PADDED];
        Arrays.fill(occludes, (byte) 1);
        assertEquals(0, AllvrCellConnectivity.mask(occludes));
    }
}
