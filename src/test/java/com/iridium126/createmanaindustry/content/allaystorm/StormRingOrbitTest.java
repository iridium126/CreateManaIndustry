package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StormRingOrbitTest {
    private static final double CHORD = 2 * StormRingGeometry.RADIUS + StormRingOrbit.CLEARANCE;
    private static final Vec3 STORM = new Vec3(100, 80, -40);

    @Test void orbitRadiusTracksTheDanceBand() {
        for (int count : new int[]{1, 1024, 4096, 65536}) {
            assertEquals(AllayStormData.vortexRadius(count) * .2 + 2, StormRingOrbit.radius(count), 1e-6);
        }
    }

    @Test void adjacentCentersClearEachOther() {
        for (int count : new int[]{1024, 4096, 16384, 65536}) {
            double orbit = StormRingOrbit.radius(count);
            double step = StormRingOrbit.step(orbit);
            Vec3 first = StormRingOrbit.center(STORM, orbit, 0);
            Vec3 second = StormRingOrbit.center(STORM, orbit, step);
            assertEquals(CHORD, first.distanceTo(second), 1e-3);
            assertFalse(StormRingOrbit.overlaps(first, second));
        }
    }

    @Test void centersSitOnTheDroppedHorizontalOrbit() {
        double orbit = StormRingOrbit.radius(4096);
        Vec3 center = StormRingOrbit.center(STORM, orbit, Math.PI / 2);
        assertEquals(STORM.y - StormRingOrbit.CENTER_DROP, center.y, 1e-9);
        assertEquals(orbit, Math.hypot(center.x - STORM.x, center.z - STORM.z), 1e-6);
    }

    @Test void directionFollowsStormHandedness() {
        assertEquals(1, StormRingOrbit.direction(18));
        assertEquals(-1, StormRingOrbit.direction(17));
        assertTrue(StormRingOrbit.wrap(-.1) > Math.PI * 2 - .2);
        assertTrue(StormRingOrbit.wrap(Math.PI * 2 + .1) < .2);
    }

    @Test void overlapUsesTheFullChordClearance() {
        Vec3 origin = new Vec3(0, 64, 0);
        assertTrue(StormRingOrbit.overlaps(origin, origin.add(CHORD - 1e-3, 0, 0)));
        assertFalse(StormRingOrbit.overlaps(origin, origin.add(CHORD, 0, 0)));
    }
}