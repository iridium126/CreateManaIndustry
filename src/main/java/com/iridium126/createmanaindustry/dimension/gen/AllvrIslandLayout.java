package com.iridium126.createmanaindustry.dimension.gen;

import java.util.ArrayList;
import java.util.List;

/** Geometry only. All coordinates stay in int/double, including at ±30M Y. */
public final class AllvrIslandLayout {
    public static final int SPACING_XZ = 2816;
    public static final double MAX_RADIUS = 1180;
    private final long seed;
    private final int minY;
    private final int height;
    private final int seaLevel;
    private final int spacingY;

    public AllvrIslandLayout(long seed, int minY, int height, int seaLevel) {
        this.seed = seed;
        this.minY = minY;
        this.height = height;
        this.seaLevel = seaLevel;
        this.spacingY = ((Math.max(640, height + 256) + 31) / 32) * 32;
    }

    public Island islandAt(int x, int y, int z) {
        long h = mix(seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xBF58476D1CE4E5B9L ^ z * 0x94D049BB133111EBL);
        double offset = (y & 1) * SPACING_XZ * 0.5;
        double cx = x * (double) SPACING_XZ + 1408 + offset + (unit(mix(h + 1)) - .5) * 320;
        double cz = z * (double) SPACING_XZ + 1408 + offset + (unit(mix(h + 2)) - .5) * 320;
        int anchor = y * spacingY + 4 * (int) ((unit(mix(h + 3)) - .5) * 40) + Math.floorMod(seaLevel, 4);
        // Independent hashes: shifting a hash before converting to a 53-bit fraction
        // biases the result towards zero (the old generator's size/jitter bug).
        double radius = 780 + unit(mix(h + 4)) * 320;
        // Rebase each island into a bounded source window. Adding an offset to
        // ±30M world X/Z directly could exceed BlockPos's signed 26-bit range.
        int dx = ((int) mix(h + 5) & 0x7ffff) * 16 - (int) Math.floor(cx / 16) * 16;
        int dz = ((int) mix(h + 6) & 0x7ffff) * 16 - (int) Math.floor(cz / 16) * 16;
        return new Island(cx, anchor, cz, radius, h, dx, dz,
            anchor - seaLevel, anchor + minY - seaLevel + 8, anchor + minY + height - seaLevel);
    }

    public Island nearest(int x, int y, int z) {
        int layer = (int) Math.round(y / (double) spacingY);
        int offset = (layer & 1) * (SPACING_XZ / 2);
        return islandAt(Math.floorDiv(x - offset, SPACING_XZ), layer, Math.floorDiv(z - offset, SPACING_XZ));
    }

    public Island[] islandsForBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        List<Island> result = new ArrayList<>();
        int reachY = height + Math.abs(minY - seaLevel) + 160;
        for (int y = Math.floorDiv(y0 - reachY, spacingY); y <= Math.floorDiv(y1 + reachY, spacingY); y++) {
            int offset = (y & 1) * (SPACING_XZ / 2);
            // Subtract both the half-cell center and the odd-layer offset before
            // quantization. Missing this used to drop islands on odd-layer seams.
            int ax = Math.floorDiv(x0 - offset - 1408 - 1340, SPACING_XZ);
            int bx = Math.floorDiv(x1 - offset - 1408 + 1340, SPACING_XZ);
            int az = Math.floorDiv(z0 - offset - 1408 - 1340, SPACING_XZ);
            int bz = Math.floorDiv(z1 - offset - 1408 + 1340, SPACING_XZ);
            for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
                Island island = islandAt(x, y, z);
                if (island.intersects(x0, y0, z0, x1, y1, z1)) result.add(island);
            }
        }
        return result.toArray(Island[]::new);
    }

    public record Island(double cx, int cy, double cz, double radius, long hash,
                         int sourceOffsetX, int sourceOffsetZ, int offsetY, int minY, int maxY) {
        public boolean intersects(double x0, double y0, double z0, double x1, double y1, double z1) {
            return x1 > cx - MAX_RADIUS && x0 < cx + MAX_RADIUS
                && z1 > cz - MAX_RADIUS && z0 < cz + MAX_RADIUS && y1 > minY && y0 < maxY;
        }

        /** Rounded, lobed outline and a tapered rocky keel, never grass on the underside. */
        public double bottom(double x, double z) {
            double nx = (x - cx) / radius;
            double nz = (z - cz) / radius;
            double phase = unit(hash) * Math.PI * 2;
            double warp = 1 + .035 * Math.sin(nx * 9 + phase) * Math.cos(nz * 7 - phase)
                + .025 * Math.sin(nx * 17 - nz * 11 + phase);
            double r = Math.sqrt(nx * nx + nz * nz) / warp;
            if (r >= 1) return Double.POSITIVE_INFINITY;
            double depth = Math.max(12, cy - minY - 10);
            return Math.max(minY, cy - depth * Math.pow(1 - r * r, .65)
                + 110 * Math.pow(r, 8) + 7 * Math.sin(x * .031 + phase) * Math.cos(z * .027));
        }

        public boolean contains(int x, int y, int z) {
            return y >= minY && y < maxY && y >= bottom(x, z);
        }
    }

    private static double unit(long h) { return (h >>> 11) * 0x1.0p-53; }
    private static long mix(long h) {
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
