package com.iridium126.createmanaindustry.dimension.gen.worldtree;

/** Immutable generation inputs; generated chunks/cubes remain the saved authority. */
public record WorldTreeDescriptor(int originX, int baseY, int originZ, long seed, int version) {
    public static final int VERSION = 1;
    public static WorldTreeDescriptor central(long worldSeed) {
        return new WorldTreeDescriptor(0, 64, 0, worldSeed, VERSION);
    }

    public WorldTreeDescriptor {
        if (version != VERSION) throw new IllegalArgumentException("Unsupported world tree version: " + version);
    }

    public long nodeSeed(long id) {
        return mix(seed ^ version * 0x9E3779B97F4A7C15L ^ id * 0xD1B54A32D192ED03L);
    }

    static long mix(long h) {
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    static double unit(long h) { return (h >>> 11) * 0x1.0p-53; }
}
