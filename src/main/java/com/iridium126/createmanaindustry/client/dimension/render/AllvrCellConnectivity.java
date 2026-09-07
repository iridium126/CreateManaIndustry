package com.iridium126.createmanaindustry.client.dimension.render;

/** Conservative six-face connectivity for the CPU visibility graph. */
public final class AllvrCellConnectivity {

    private AllvrCellConnectivity() {}

    /**
     * Returns one bit per face in AllvrMesher order (+X,-X,+Y,-Y,+Z,-Z).
     * A face is connected when its boundary contains at least one non-full
     * voxel.  False negatives are avoided so the graph can only over-submit;
     * GPU frustum/Hi-Z remains the fine culler.
     */
    public static int mask(byte[] occludes) {
        if (occludes.length != AllvrCellMesher.PADDED * AllvrCellMesher.PADDED
                * AllvrCellMesher.PADDED) {
            throw new IllegalArgumentException("connectivity requires an 18³ snapshot");
        }
        int result = 0;
        for (int face = 0; face < 6; face++) {
            int axis = face >> 1;
            // The border samples live at -1 and 16.  Testing the outside
            // sample makes a fully solid cell connected to an air neighbour,
            // while an enclosed solid face remains closed.
            int coordinate = (face & 1) == 0 ? 16 : -1;
            boolean open = false;
            for (int v = 0; v < 16 && !open; v++) {
                for (int u = 0; u < 16; u++) {
                    int x = axis == 0 ? coordinate : axis == 1 ? u : u;
                    int y = axis == 1 ? coordinate : axis == 0 ? u : v;
                    int z = axis == 2 ? coordinate : axis == 0 ? v : v;
                    if (occludes[AllvrCellMesher.paddedIndex(x, y, z)] == 0) {
                        open = true;
                        break;
                    }
                }
            }
            if (open) {
                result |= 1 << face;
            }
        }
        return result;
    }
}
