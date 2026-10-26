package com.iridium126.createmanaindustry.content.allaystorm.rift;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable order: original XZ, squared horizontal distance, X, Z. Never origin fallback. */
public final class RiftLandingOffsets {
    public record Offset(int x, int z) { public int distanceSquared() { return x*x + z*z; } }
    public static final List<Offset> OFFSETS = generate(RiftRules.SEARCH_RADIUS);
    private RiftLandingOffsets() {}
    public static List<Offset> generate(int radius) {
        if (radius < 0 || radius > 128) throw new IllegalArgumentException("Invalid landing radius");
        var result = new ArrayList<Offset>();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++)
            if (x*x + z*z <= radius*radius) result.add(new Offset(x, z));
        result.sort(Comparator.comparingInt(Offset::distanceSquared).thenComparingInt(Offset::x).thenComparingInt(Offset::z));
        return List.copyOf(result);
    }
}
