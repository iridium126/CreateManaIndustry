package com.iridium126.createmanaindustry.content.allaystorm;

import java.util.Arrays;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side estimate of where recently released members still are. The
 * server never sees positions, but every release site knows its approximate
 * point; selection adds the estimated remaining return distance to the
 * analytic home distance so the assemble budget stays honest.
 */
final class StormMemberReturns {
    @FunctionalInterface interface Release { void accept(int member, Vec3 point, double margin); }

    /** Conservative ordinary return speed; below the GPU's 6 b/s cap. */
    static final double SPEED = 5;
    /** Gather-equivalent distance per block of return distance (gather ~7.5 b/s). */
    static final double BOOST = 1.5;
    /** Upper bound on the raw return estimate, blocks. */
    static final double CAP = 64;

    private long[] released;
    private float[] x, y, z, margin;

    void released(int member, long tick, Vec3 point, double margin) {
        if (member < 0 || member >= StormAttackMembers.MAX_MEMBERS
                || !Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) return;
        if (released == null) {
            released = new long[StormAttackMembers.MAX_MEMBERS];
            Arrays.fill(released, Long.MIN_VALUE);
            x = new float[StormAttackMembers.MAX_MEMBERS];
            y = new float[StormAttackMembers.MAX_MEMBERS];
            z = new float[StormAttackMembers.MAX_MEMBERS];
            this.margin = new float[StormAttackMembers.MAX_MEMBERS];
        }
        released[member] = tick;
        x[member] = (float) point.x;
        y[member] = (float) point.y;
        z[member] = (float) point.z;
        this.margin[member] = (float) margin;
    }

    void dead(int member) {
        if (released != null && member >= 0 && member < released.length) released[member] = Long.MIN_VALUE;
    }

    void clear() {
        if (released != null) Arrays.fill(released, Long.MIN_VALUE);
    }

    /** Gather-equivalent extra distance; 0 when the member is not returning. */
    double penalty(int member, long now, Vec3 home) {
        if (released == null || member < 0 || member >= released.length || released[member] == Long.MIN_VALUE) return 0;
        double age = Math.max(0, now - released[member]) / 20.0;
        double dx = home.x - x[member], dy = home.y - y[member], dz = home.z - z[member];
        double remaining = Math.sqrt(dx * dx + dy * dy + dz * dz) + margin[member] - SPEED * age;
        return Math.clamp(remaining, 0, CAP) * BOOST;
    }
}