package com.iridium126.createmanaindustry.dimension.gen;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllvrIslandLayoutTest {
    @Test void candidateBoundsIncludeNegativeAndOddLayersAtExtremeHeights() {
        var layout = new AllvrIslandLayout(126L, -64, 384, 63);
        for (int layer : new int[]{-46874, -3, -2, -1, 0, 1, 2, 3, 46874}) {
            for (int cx : new int[]{-10650, -1, 0, 1, 10650}) {
                var island = layout.islandAt(cx, layer, -cx);
                assertTrue(Math.abs(island.cx() + island.sourceOffsetX()) < 9_000_000);
                assertTrue(Math.abs(island.cz() + island.sourceOffsetZ()) < 9_000_000);
                assertEquals(0, Math.floorMod(island.offsetY(), 4));
                for (double dx : new double[]{-.9, 0, .9}) for (double dz : new double[]{-.3, 0, .3}) {
                    int x = (int) (island.cx() + dx * island.radius());
                    int z = (int) (island.cz() + dz * island.radius());
                    double bottom = island.bottom(x, z);
                    if (!Double.isFinite(bottom)) continue;
                    int y = (int) Math.ceil(bottom);
                    var found = layout.islandsForBox(x, y, z, x + 1, y + 1, z + 1);
                    assertTrue(Arrays.asList(found).contains(island), () -> "Missing island " + island);
                }
            }
        }
    }

    @Test void independentHashesGiveRealSizeAndVerticalVariation() {
        var layout = new AllvrIslandLayout(42, -64, 384, 63);
        double min = Double.POSITIVE_INFINITY, max = 0;
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int x = -100; x <= 100; x++) {
            var island = layout.islandAt(x, 0, 0);
            min = Math.min(min, island.radius()); max = Math.max(max, island.radius());
            low = Math.min(low, island.cy()); high = Math.max(high, island.cy());
            assertEquals(island, layout.islandAt(x, 0, 0));
        }
        assertTrue(max - min > 280);
        assertTrue(high - low > 130);
    }

    @Test void keelAndCoordinateMappingHaveNoCubeBoundaryDiscontinuity() {
        var layout = new AllvrIslandLayout(99, -64, 384, 63);
        var island = layout.islandAt(-1, 40000, 0);
        assertTrue(island.bottom(island.cx(), island.cz()) < island.cy() - 70);
        assertEquals(Double.POSITIVE_INFINITY, island.bottom(island.cx() + 1500, island.cz()));
        assertEquals(63, island.cy() - island.offsetY());
        assertEquals(0, island.sourceOffsetX() & 15);
        assertEquals(0, island.sourceOffsetZ() & 15);
        int x = (int) island.cx(), z = (int) island.cz();
        for (int y = island.minY(); y < island.maxY(); y++) {
            assertEquals(y >= island.bottom(x, z), island.contains(x, y, z));
        }
    }

    @Test void tallDatapackSettingsKeepVerticalLayersSeparated() {
        var layout = new AllvrIslandLayout(17, -512, 2048, 64);
        for (int layer = -10; layer < 10; layer++) {
            var a = layout.islandAt(0, layer, 0);
            var b = layout.islandAt(0, layer + 1, 0);
            assertTrue(a.maxY() < b.minY());
        }
    }
}
