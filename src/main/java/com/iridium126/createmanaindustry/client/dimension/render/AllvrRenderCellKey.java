package com.iridium126.createmanaindustry.client.dimension.render;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Absolute 16³ render-cell coordinates.
 *
 * <p>The wire/storage unit is still a 32³ {@code AllvrCube}.  A cell key is a
 * runtime handle for section-like (block >> 4) coordinates.  Keeping this
 * conversion in one place prevents the renderer from accidentally applying
 * Voxy's virtual Y window or a floating-point camera origin to its identity.
 */
public final class AllvrRenderCellKey {

    public static final int SIZE = 16;
    public static final int CELLS_PER_CUBE = 2;

    /*
     * A 32³ cube fits in AllvrCubePos' 21-bit-per-axis wire key.  A 16³ cell
     * over the same ±30M block domain needs 22 bits per axis, which cannot be
     * losslessly packed into one long.  Cell keys are therefore runtime
     * handles, while this registry retains the exact absolute integer triple
     * used by workers and GPU node origins.  The handle never crosses the
     * network or save format.
     */
    private record Coordinates(int x, int y, int z) {}
    private static final java.util.concurrent.ConcurrentMap<Long, Coordinates> BY_ID =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Coordinates, Long> BY_COORDINATES =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ID =
        new java.util.concurrent.atomic.AtomicLong(1L);

    private AllvrRenderCellKey() {}

    public static long ofCell(int cellX, int cellY, int cellZ) {
        Coordinates coordinates = new Coordinates(cellX, cellY, cellZ);
        return BY_COORDINATES.computeIfAbsent(coordinates, value -> {
            long id = NEXT_ID.getAndIncrement();
            if (id == 0L) {
                throw new IllegalStateException("ALLVR render-cell handle space exhausted");
            }
            BY_ID.put(id, value);
            return id;
        });
    }

    public static long ofBlock(int blockX, int blockY, int blockZ) {
        return ofCell(blockX >> 4, blockY >> 4, blockZ >> 4);
    }

    public static long ofCube(long cubeKey, int localX, int localY, int localZ) {
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        if ((localX | localY | localZ) < 0 || localX > 1 || localY > 1 || localZ > 1) {
            throw new IllegalArgumentException("cell offset outside a 32³ cube");
        }
        return ofCell((cube.getX() << 1) + localX,
            (cube.getY() << 1) + localY,
            (cube.getZ() << 1) + localZ);
    }

    public static int cellX(long key) {
        return coordinates(key).x();
    }

    public static int cellY(long key) {
        return coordinates(key).y();
    }

    public static int cellZ(long key) {
        return coordinates(key).z();
    }

    public static int minBlockX(long key) {
        return cellX(key) << 4;
    }

    public static int minBlockY(long key) {
        return cellY(key) << 4;
    }

    public static int minBlockZ(long key) {
        return cellZ(key) << 4;
    }

    public static long cubeOf(long cellKey) {
        return AllvrCubePos.asLong(cellX(cellKey) >> 1, cellY(cellKey) >> 1, cellZ(cellKey) >> 1);
    }

    public static long neighbor(long key, int dx, int dy, int dz) {
        return ofCell(cellX(key) + dx, cellY(key) + dy, cellZ(key) + dz);
    }

    /** Releases a runtime handle after its render cell is forgotten. */
    public static void release(long key) {
        Coordinates coordinates = BY_ID.remove(key);
        if (coordinates != null) {
            BY_COORDINATES.remove(coordinates, key);
        }
    }

    public static String describe(long key) {
        Coordinates c = coordinates(key);
        return "CellPos{" + c.x() + "," + c.y() + "," + c.z() + "}";
    }

    private static Coordinates coordinates(long key) {
        Coordinates coordinates = BY_ID.get(key);
        if (coordinates == null) {
            throw new IllegalArgumentException("unknown ALLVR render-cell handle " + key);
        }
        return coordinates;
    }
}
