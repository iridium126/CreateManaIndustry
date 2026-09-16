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

    @Test void distanceGradientAndIndependentHashesGiveSizeAndVerticalVariation() {
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
        assertTrue(island.bottom(island.cx(), island.cz()) < island.cy() - 50);
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

    @Test void sizeIncreasesSmoothlyWithDistanceAndKeepsWholeOutlineOutsideOrigin() {
        double previous = 0;
        for (int distance = 501; distance <= 30_000_000; distance += 137) {
            double radius = AllvrIslandLayout.radiusAtDistance(distance);
            assertTrue(radius > previous);
            assertTrue(radius * 1.06 <= distance - 500);
            assertTrue(radius * 1.06 < AllvrIslandLayout.MAX_RADIUS);
            previous = radius;
        }
        assertEquals(0, AllvrIslandLayout.radiusAtDistance(0));
        assertEquals(0, AllvrIslandLayout.radiusAtDistance(500));
    }

    @Test void originIsEmptyAcrossSeedsAndVerticalLayers() {
        for (long seed : new long[]{0, 42, 126, -999}) {
            var layout = new AllvrIslandLayout(seed, -64, 384, 63);
            for (int layer : new int[]{-46874, -1, 0, 1, 2, 46874}) {
                for (int ix = -1; ix <= 0; ix++) for (int iz = -1; iz <= 0; iz++) {
                    var island = layout.islandAt(ix, layer, iz);
                    for (int x = -499; x <= 499; x += 31) for (int z = -499; z <= 499; z += 31) {
                        if (Math.hypot(x, z) >= 500) continue;
                        assertEquals(Double.POSITIVE_INFINITY, island.bottom(x, z));
                        assertFalse(island.contains(x, island.cy(), z));
                    }
                    if (island.radius() == 0) {
                        assertFalse(island.intersects(-500, island.minY(), -500, 500, island.maxY(), 500));
                    }
                }
            }
        }
    }

    @Test void pointedKeelRisesTowardsRimAndShellSpansCubeBoundaries() {
        var island = new AllvrIslandLayout(99, -64, 384, 63).islandAt(10, 3, 10);
        double tip = island.bottom(island.cx(), island.cz());
        for (int direction = 0; direction < 16; direction++) {
            double angle = direction * Math.PI / 8;
            double previous = tip;
            for (int step = 1; step <= 90; step++) {
                double x = island.cx() + island.radius() * step / 100 * Math.cos(angle);
                double z = island.cz() + island.radius() * step / 100 * Math.sin(angle);
                double bottom = island.bottom(x, z);
                assertTrue(bottom > previous, "Keel must narrow towards its tip");
                previous = bottom;
                if (bottom < island.cy() - 12) {
                    int shellTop = island.shellTop(x, z, bottom);
                    assertTrue(shellTop >= Math.ceil(bottom) + 3);
                    for (int y = (int) Math.ceil(bottom); y < shellTop; y++) {
                        assertTrue(island.contains((int) Math.round(x), y + 1, (int) Math.round(z)));
                    }
                }
            }
        }
        assertTrue(island.bottom(island.cx() + island.radius() * .01, island.cz()) - tip > .5);
    }
}
