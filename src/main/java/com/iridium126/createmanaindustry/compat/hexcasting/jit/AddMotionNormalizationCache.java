package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import net.minecraft.world.phys.Vec3;

/** Per-cast cache for bit-identical Add Motion normalization inputs. */
public final class AddMotionNormalizationCache {
    /** The JIT owns one server thread, so its hot lookup avoids ThreadLocalMap entirely. */
    private static Cache serverCache;
    private static int serverDepth;
    /** Keep independent behavior for non-server callers. */
    private static final ThreadLocal<Cache> OTHER_THREADS = new ThreadLocal<>();

    private AddMotionNormalizationCache() {}

    /** Starts a cache lifetime; the holder is reused, so later casts add no cache allocation. */
    public static void beginCast() {
        if (HexJitRuntime.onServerThread()) {
            if (serverCache == null) serverCache = new Cache();
            serverDepth++;
        } else {
            Cache cache = OTHER_THREADS.get();
            if (cache == null) {
                cache = new Cache();
                OTHER_THREADS.set(cache);
            }
            cache.depth++;
        }
    }

    /** Ends a nested or top-level cast lifetime and drops retained vectors at the outer boundary. */
    public static void endCast() {
        if (HexJitRuntime.onServerThread()) {
            if (serverCache == null || serverDepth <= 0) return;
            if (--serverDepth == 0) serverCache.clear();
        } else {
            Cache cache = OTHER_THREADS.get();
            if (cache == null || cache.depth <= 0) return;
            if (--cache.depth == 0) cache.clear();
        }
    }

    public static Cache activeCache() {
        if (HexJitRuntime.onServerThread()) return serverDepth > 0 ? serverCache : null;
        Cache cache = OTHER_THREADS.get();
        return cache == null || cache.depth <= 0 ? null : cache;
    }

    public static void releaseThreadState() {
        serverDepth = 0;
        if (serverCache != null) serverCache.clear();
        serverCache = null;
        Cache cache = OTHER_THREADS.get();
        if (cache != null) {
            cache.depth = 0;
            cache.clear();
        }
        OTHER_THREADS.remove();
    }

    public static final class Cache {
        private int depth;
        private long x1, y1, z1, x2, y2, z2;
        private Vec3 value1, value2;
        private boolean replaceFirst;

        public Vec3 cached(Vec3 input) {
            long x = Double.doubleToRawLongBits(input.x);
            long y = Double.doubleToRawLongBits(input.y);
            long z = Double.doubleToRawLongBits(input.z);
            if (value1 != null && x1 == x && y1 == y && z1 == z) return value1;
            if (value2 != null && x2 == x && y2 == y && z2 == z) return value2;
            return null;
        }

        public void remember(Vec3 input, Vec3 normalized) {
            long x = Double.doubleToRawLongBits(input.x);
            long y = Double.doubleToRawLongBits(input.y);
            long z = Double.doubleToRawLongBits(input.z);
            if (value1 != null && x1 == x && y1 == y && z1 == z) {
                value1 = normalized;
            } else if (value2 != null && x2 == x && y2 == y && z2 == z) {
                value2 = normalized;
            } else if (replaceFirst) {
                x1 = x;
                y1 = y;
                z1 = z;
                value1 = normalized;
                replaceFirst = false;
            } else {
                x2 = x;
                y2 = y;
                z2 = z;
                value2 = normalized;
                replaceFirst = true;
            }
        }

        private void clear() {
            value1 = null;
            value2 = null;
            replaceFirst = false;
        }
    }
}
