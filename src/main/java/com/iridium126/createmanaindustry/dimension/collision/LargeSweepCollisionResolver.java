package com.iridium126.createmanaindustry.dimension.collision;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Resolves very large entity sweeps as the same ordered axis clips used by
 * {@code Entity.collideWithShapes}, querying blocks only in each axis' swept
 * prism instead of the full three-dimensional bounding cuboid.
 */
public final class LargeSweepCollisionResolver {
    /** Keep the ordinary collision path untouched; only switch when its cell volume is large. */
    private static final long FULL_SCAN_CELL_THRESHOLD = 65_536L;
    /** Cursor3D stores its product in an int. Do not create an axis query whose count overflows it. */
    private static final long MAX_SAFE_CURSOR_CELLS = Integer.MAX_VALUE - 1_000_000L;

    private LargeSweepCollisionResolver() {}

    public static boolean shouldResolve(@Nullable Entity entity, Vec3 movement, AABB collisionBox) {
        if (!isFinite(movement) || !isFinite(collisionBox)) {
            return false;
        }
        if (!hasFiniteExpandedBounds(collisionBox, movement.x, movement.y, movement.z)) {
            return false;
        }
        if (estimatedCellVolume(collisionBox, movement) <= FULL_SCAN_CELL_THRESHOLD) {
            return false;
        }

        if (cellCountForExpansion(collisionBox, movement.x, movement.y, movement.z) <= FULL_SCAN_CELL_THRESHOLD) {
            return false;
        }

        // Every per-axis query must itself remain within Cursor3D's signed-int range.
        if (movement.y != 0.0 && !hasSafeCursorVolume(collisionBox, 0.0, movement.y, 0.0)) {
            return false;
        }
        if (movement.x != 0.0 && !hasSafeCursorVolume(collisionBox, movement.x, 0.0, 0.0)) {
            return false;
        }
        return movement.z == 0.0
            || hasSafeCursorVolume(collisionBox, 0.0, 0.0, movement.z);
    }

    /**
     * Performs the vanilla Y, then X/Z, then remaining-axis collision order.
     * Existing entity hits and the world-border shape are retained for every axis.
     */
    public static Vec3 resolve(@Nullable Entity entity, Vec3 movement, AABB collisionBox,
                               Level level, List<VoxelShape> potentialHits) {
        AABB fullSweep = collisionBox.expandTowards(movement);
        try (BlockCollisionRoutingScope ignored = BlockCollisionRoutingScope.preserveOriginalRoute(level, fullSweep)) {
            return resolveWithinOriginalRoute(entity, movement, collisionBox, level, potentialHits, fullSweep);
        }
    }

    private static Vec3 resolveWithinOriginalRoute(@Nullable Entity entity, Vec3 movement, AABB collisionBox,
                                                   Level level, List<VoxelShape> potentialHits, AABB fullSweep) {
        ArrayList<VoxelShape> fixedHits = new ArrayList<>(potentialHits.size() + 1);
        fixedHits.addAll(potentialHits);

        var worldBorder = level.getWorldBorder();
        if (entity != null && worldBorder.isInsideCloseToBorder(entity, fullSweep)) {
            fixedHits.add(worldBorder.getCollisionShape());
        }

        double dx = movement.x;
        double dy = movement.y;
        double dz = movement.z;

        if (dy != 0.0) {
            dy = collideAxis(entity, level, fixedHits, collisionBox, dy, Direction.Axis.Y);
            if (dy != 0.0) {
                collisionBox = collisionBox.move(0.0, dy, 0.0);
            }
        }

        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0.0) {
            dz = collideAxis(entity, level, fixedHits, collisionBox, dz, Direction.Axis.Z);
            if (dz != 0.0) {
                collisionBox = collisionBox.move(0.0, 0.0, dz);
            }
        }

        if (dx != 0.0) {
            dx = collideAxis(entity, level, fixedHits, collisionBox, dx, Direction.Axis.X);
            if (!zFirst && dx != 0.0) {
                collisionBox = collisionBox.move(dx, 0.0, 0.0);
            }
        }

        if (!zFirst && dz != 0.0) {
            dz = collideAxis(entity, level, fixedHits, collisionBox, dz, Direction.Axis.Z);
        }

        return new Vec3(dx, dy, dz);
    }

    private static double collideAxis(@Nullable Entity entity, Level level, List<VoxelShape> fixedHits,
                                      AABB box, double movement, Direction.Axis axis) {
        AABB axisSweep = switch (axis) {
            case X -> box.expandTowards(movement, 0.0, 0.0);
            case Y -> box.expandTowards(0.0, movement, 0.0);
            case Z -> box.expandTowards(0.0, 0.0, movement);
        };

        ArrayList<VoxelShape> hits = new ArrayList<>(fixedHits.size() + 16);
        hits.addAll(fixedHits);
        AABB blockScan = axisSweep;
        if (!AllvrDimensions.isAllay(level)) {
            // Vanilla chunk-backed levels return air outside their build-height range. Large
            // vertical movements can otherwise make BlockCollisions visit tens of thousands
            // of empty Y cells. The Allay dimension stores collision data in cubes beyond the
            // vanilla build-height range, so it must keep the original unbounded query.
            double minBuildY = level.getMinBuildHeight();
            double maxBuildY = level.getMaxBuildHeight();
            double minY = Math.max(blockScan.minY, minBuildY);
            double maxY = Math.min(blockScan.maxY, maxBuildY);
            if (minY >= maxY) {
                return Shapes.collide(axis, box, hits, movement);
            }
            if (minY != blockScan.minY || maxY != blockScan.maxY) {
                blockScan = new AABB(blockScan.minX, minY, blockScan.minZ,
                    blockScan.maxX, maxY, blockScan.maxZ);
            }
        }
        for (VoxelShape shape : level.getBlockCollisions(entity, blockScan)) {
            hits.add(shape);
        }
        return Shapes.collide(axis, box, hits, movement);
    }

    private static boolean hasSafeCursorVolume(AABB box, double dx, double dy, double dz) {
        long x = axisCellCount(expandedMin(box.minX, dx), expandedMax(box.maxX, dx));
        long y = axisCellCount(expandedMin(box.minY, dy), expandedMax(box.maxY, dy));
        long z = axisCellCount(expandedMin(box.minZ, dz), expandedMax(box.maxZ, dz));
        // Later axes run after the box has been translated by a clipped prior axis;
        // reserve two cells per side for floor-boundary changes from that translation.
        return safeProduct(x + 2L, y + 2L, z + 2L) <= MAX_SAFE_CURSOR_CELLS;
    }

    /** Matches BlockCollisions' one-block border around its Cursor3D bounds, with 64-bit math. */
    private static long cellCountForExpansion(AABB box, double dx, double dy, double dz) {
        return safeProduct(
            axisCellCount(expandedMin(box.minX, dx), expandedMax(box.maxX, dx)),
            axisCellCount(expandedMin(box.minY, dy), expandedMax(box.maxY, dy)),
            axisCellCount(expandedMin(box.minZ, dz), expandedMax(box.maxZ, dz)));
    }

    private static long safeProduct(long x, long y, long z) {
        if (x <= 0 || y <= 0 || z <= 0 || x > MAX_SAFE_CURSOR_CELLS || y > MAX_SAFE_CURSOR_CELLS
            || z > MAX_SAFE_CURSOR_CELLS || x > MAX_SAFE_CURSOR_CELLS / y) {
            return Long.MAX_VALUE;
        }
        long xy = x * y;
        return xy > MAX_SAFE_CURSOR_CELLS / z ? Long.MAX_VALUE : xy * z;
    }

    private static double expandedMin(double min, double movement) {
        return movement < 0.0 ? min + movement : min;
    }

    private static double expandedMax(double max, double movement) {
        return movement > 0.0 ? max + movement : max;
    }

    private static boolean hasFiniteExpandedBounds(AABB box, double dx, double dy, double dz) {
        return Double.isFinite(expandedMin(box.minX, dx)) && Double.isFinite(expandedMax(box.maxX, dx))
            && Double.isFinite(expandedMin(box.minY, dy)) && Double.isFinite(expandedMax(box.maxY, dy))
            && Double.isFinite(expandedMin(box.minZ, dz)) && Double.isFinite(expandedMax(box.maxZ, dz));
    }

    /** Allocation-free upper bound, allowing the overwhelmingly common small-sweep path to skip floor math. */
    private static double estimatedCellVolume(AABB box, Vec3 movement) {
        double x = box.maxX - box.minX + Math.abs(movement.x) + 4.0;
        double y = box.maxY - box.minY + Math.abs(movement.y) + 4.0;
        double z = box.maxZ - box.minZ + Math.abs(movement.z) + 4.0;
        return x * y * z;
    }

    private static long axisCellCount(double min, double max) {
        long start = (long) Mth.floor(min - 1.0E-7) - 1L;
        long end = (long) Mth.floor(max + 1.0E-7) + 1L;
        return end - start + 1L;
    }

    private static boolean isFinite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static boolean isFinite(AABB box) {
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
            && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }
}
