package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.world.phys.Vec3;

/** Horizontal assembly orbit around the storm's inner dance band. */
final class StormRingOrbit {
    /** Shader band whose members play the vanilla dance cycle (0.2 of the vortex radius). */
    static final float DANCE_FRACTION = .2f;
    static final double MARGIN = 2, CLEARANCE = .5, CENTER_DROP = 4;

    private StormRingOrbit() {}

    static double radius(double generated) {
        return AllayStormData.vortexRadius(generated) * DANCE_FRACTION + MARGIN;
    }

    static double step(double orbit) {
        double chord = 2 * StormRingGeometry.RADIUS + CLEARANCE;
        return 2 * Math.asin(Math.min(1, chord / (2 * orbit)));
    }

    static int direction(int seed) {
        return (seed & 1) == 0 ? 1 : -1;
    }

    static Vec3 center(Vec3 stormCenter, double orbit, double angle) {
        return stormCenter.add(Math.cos(angle) * orbit, -CENTER_DROP, Math.sin(angle) * orbit);
    }

    static boolean overlaps(Vec3 a, Vec3 b) {
        // nextCenter steps by exactly this chord; the tolerance keeps a
        // mathematically adjacent center from rounding into a false overlap
        double chord = 2 * StormRingGeometry.RADIUS + CLEARANCE - 1e-6;
        return a.distanceToSqr(b) < chord * chord;
    }

    static double wrap(double angle) {
        double tau = Math.PI * 2;
        angle %= tau;
        return angle < 0 ? angle + tau : angle;
    }
}