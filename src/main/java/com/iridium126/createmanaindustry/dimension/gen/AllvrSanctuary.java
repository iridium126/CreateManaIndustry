package com.iridium126.createmanaindustry.dimension.gen;

/** Pure, world-coordinate field shared by generation, previews and regression checks. */
public final class AllvrSanctuary {
    public static final int GROUND = 95, INNER = 100, OUTER = 200, DEPTH = 100;
    public static final int FLAT_RADIUS = 90, CALM_RADIUS = 500, BLEND_RADIUS = 700;
    private final long seed;
    public AllvrSanctuary(long seed) { this.seed = seed; }

    public static double smooth(double t) {
        t = Math.max(0, Math.min(1, t));
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    /** Flat inner land, low rolling grassland, C2 blend back into original terrain. */
    public static int surface(int x, int z, int original) {
        double r = Math.hypot(x, z);
        if (r >= BLEND_RADIUS) return original;
        double calm = GROUND + smooth((r - 220) / 160) *
            (2.5 * Math.sin(x / 95.0) * Math.cos(z / 110.0) + 1.5 * Math.sin((x + z) / 160.0));
        return (int) Math.round(calm + smooth((r - CALM_RADIUS) / 200) * (original - calm));
    }

    public Column column(int x, int z) {
        double radius = Math.hypot(x, z), theta = Math.atan2(z, x);
        double floor = GROUND - DEPTH + 4 * Math.abs(noise(x / 23.0, 0, z / 23.0))
            + 3 * Math.abs(noise(x / 7.0, 0, z / 7.0));
        return new Column(x, z, radius, theta, (int) Math.floor(floor));
    }

    public record Column(int x, int z, double radius, double theta, int floor) {}

    static boolean suppressSideGroundVegetation(Column c, int y) {
        return y == GROUND && (c.radius() < INNER || c.radius() > OUTER);
    }

    public static boolean lavaSurfaceExcluded(int x, int z) {
        return (long) x * x + (long) z * z <= 200L * 200L;
    }

    /** Eroded limestone walls: fluting, strata, overhangs and a talus toe; never cuts r<=90. */
    public boolean cavity(Column c, int y) {
        if (c.radius < 91 || c.radius > 213 || y <= c.floor || y > GROUND) return false;
        double strata = 2.2 * Math.sin(y * .19 + Math.sin(c.theta * 5));
        // World-space multiscale fluting avoids the repeating teeth of angular sine waves.
        double flutes = 5 * noise(c.x / 35.0, 0, c.z / 35.0)
            + 2 * noise(c.x / 9.0, y / 90.0, c.z / 9.0);
        double erosion = 3 * noise(c.x / 12.0, y / 17.0, c.z / 12.0);
        double toe = 9 * (1 - smooth((y - c.floor) / 22.0));
        double rim = smooth((GROUND - y) / 12.0);
        double inner = INNER + strata + flutes + erosion + toe - 2 * rim;
        double outer = OUTER + strata + flutes - erosion - toe + 2 * rim;
        return c.radius > Math.max(91, inner) && c.radius < outer;
    }

    public double noise(double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = smooth(x - ix), fy = smooth(y - iy), fz = smooth(z - iz), result = 0;
        for (int dz = 0; dz < 2; dz++) for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
            result += (unit(hash(ix + dx, iy + dy, iz + dz)) * 2 - 1)
                * (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
        return result;
    }

    public long hash(int x, int y, int z) {
        return mix(seed ^ x * 0x632be59bd9b4e019L ^ y * 0x9e3779b97f4a7c15L ^ z * 0x85157af5d66d9eabL);
    }
    public static double unit(long h) { return (mix(h) >>> 11) * 0x1.0p-53; }
    private static long mix(long h) {
        h = (h ^ h >>> 30) * 0xbf58476d1ce4e5b9L;
        h = (h ^ h >>> 27) * 0x94d049bb133111ebL;
        return h ^ h >>> 31;
    }
}
