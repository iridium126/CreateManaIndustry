package com.iridium126.createmanaindustry.dimension.storage;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Cube coordinate → region3d file/slot routing (plan §5.2). Pure Java — the
 * negative-coordinate arithmetic ({@code floorDiv}/{@code floorMod}, never
 * shift+mask) is exactly what P0's unit tests pin down.
 * <p>
 * One region covers 16×16×16 cubes; the slot index is Y-major
 * ({@code (ly << 8) | (lz << 4) | lx}), mirroring the project's
 * {@code AllvrCube#sliceIndex} / {@code AllvrCube#localIndex} convention.
 */
public final class AllvrRegionIndex {

    /** Cubes per region axis. */
    public static final int DIAMETER = AllvrStorageFormat.REGION_DIAMETER_CUBES;

    /**
     * Region key packing: 18 bits per axis, biased by 2^17 (region coords
     * span ±65 536 for the ±33.5 M cube range — comfortably inside 18 bits).
     * Pure numeric identity for the LRU map; the file name carries the
     * readable signed values.
     */
    public static final long REGION_BIAS = 1L << 17;

    /** region coordinate for a cube coordinate. */
    public static int region(int cubeCoord) {
        return Math.floorDiv(cubeCoord, DIAMETER);
    }

    /** in-region cube coordinate (0..15) for a cube coordinate. */
    public static int local(int cubeCoord) {
        return Math.floorMod(cubeCoord, DIAMETER);
    }

    /** Y-major slot index of the cube's in-region coordinates. */
    public static int slot(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    /** Slot index for a world cube position. */
    public static int slot(AllvrCubePos pos) {
        return slot(local(pos.getX()), local(pos.getY()), local(pos.getZ()));
    }

    public static long regionKey(int regionX, int regionY, int regionZ) {
        return ((regionX + REGION_BIAS) << 36) | ((regionY + REGION_BIAS) << 18) | (regionZ + REGION_BIAS);
    }

    public static int regionKeyX(long key) {
        return ((int) (key >>> 36)) - (int) REGION_BIAS;
    }

    public static int regionKeyY(long key) {
        return ((int) (key >>> 18) & 0x3FFFF) - (int) REGION_BIAS;
    }

    public static int regionKeyZ(long key) {
        return ((int) key & 0x3FFFF) - (int) REGION_BIAS;
    }

    /** {@code r.<rx>.<ry>.<rz>.3dr} — the on-disk name for one region file. */
    public static String fileName(int regionX, int regionY, int regionZ) {
        return "r." + regionX + "." + regionY + "." + regionZ + ".3dr";
    }

    /**
     * Parses a region file name back into its coordinates, or {@code null}
     * when the name is not a region3d file (foreign files in the folder are
     * ignored, not treated as corruption).
     */
    public static int[] parseFileName(String name) {
        if (!name.startsWith("r.") || !name.endsWith(".3dr")) {
            return null;
        }
        String body = name.substring(2, name.length() - 4);
        String[] parts = body.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private AllvrRegionIndex() {}
}
