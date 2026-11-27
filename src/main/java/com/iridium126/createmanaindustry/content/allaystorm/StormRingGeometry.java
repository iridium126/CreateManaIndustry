package com.iridium126.createmanaindustry.content.allaystorm;

import net.minecraft.world.phys.Vec3;

public final class StormRingGeometry {
    public static final int MEMBERS = 16, CAPACITY = 64;
    public static final float RADIUS = 1.75f, FIRE_DISTANCE = 24, BEAM_RANGE = 48, BEAM_SPEED = 60;
    public static final float HOLD = .30f, FADE = .55f;
    public static final double SPIN = 2 * Math.PI / 12;
    public static final int GATHER = 1, APPROACH = 2, HOLDING = 3, FIRING = 4, END = 5;

    private StormRingGeometry() {}

    public static Vec3 right(Vec3 axis, Vec3 previous) {
        Vec3 projected = previous.subtract(axis.scale(previous.dot(axis)));
        if (projected.lengthSqr() > 1e-6) return projected.normalize();
        return (Math.abs(axis.y) < .95 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1)).cross(axis).normalize();
    }

    public static Vec3 offset(Vec3 axis, Vec3 right, int rank, double age) {
        double angle = rank * (Math.PI * 2 / MEMBERS) - SPIN * age;
        return right.scale(RADIUS * Math.cos(angle)).add(axis.cross(right).scale(RADIUS * Math.sin(angle)));
    }
}
