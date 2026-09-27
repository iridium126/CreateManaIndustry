package com.iridium126.createmanaindustry.dimension.collision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("createmanaindustry")
@PrefixGameTestTemplate(false)
public final class LargeSweepCollisionGameTests {

    @GameTest(template = "worldgen_test", timeoutTicks = 1200)
    public static void largeSweepMatchesVanillaCollisionOrder(GameTestHelper helper) {
        Level level = helper.getLevel();
        var base = helper.absolutePos(new net.minecraft.core.BlockPos(2, 100, 2));
        int x = base.getX();
        int y = base.getY();
        int z = base.getZ();

        // A ceiling clips Y first, then the two walls clip the horizontal axes.
        level.setBlock(new net.minecraft.core.BlockPos(x, y + 5, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        for (int wallY = y + 3; wallY <= y + 6; wallY++) {
            for (int wallZ = z + 1; wallZ <= z + 30; wallZ++) {
                level.setBlock(new net.minecraft.core.BlockPos(x + 9, wallY, wallZ), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            }
            for (int wallX = x + 1; wallX <= x + 9; wallX++) {
                level.setBlock(new net.minecraft.core.BlockPos(wallX, wallY, z + 12), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            }
            for (int wallZ = z - 30; wallZ < z; wallZ++) {
                level.setBlock(new net.minecraft.core.BlockPos(x - 9, wallY, wallZ), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            }
            for (int wallX = x - 9; wallX <= x + 9; wallX++) {
                level.setBlock(new net.minecraft.core.BlockPos(wallX, wallY, z - 12), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(new net.minecraft.core.BlockPos(x, y - 1, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        // Include a non-cube collision shape in the candidate path as well.
        level.setBlock(new net.minecraft.core.BlockPos(x + 4, y + 3, z), net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState(), 3);

        AABB box = new AABB(x + 0.2, y, z + 0.2, x + 0.8, y + 1.8, z + 0.8);
        List<VoxelShape> existingEntityHits = List.of(
            Shapes.create(new AABB(x + 2.0, y + 3.0, z + 0.2, x + 3.0, y + 4.0, z + 0.8)));
        List<Vec3> movements = List.of(
            new Vec3(65.0, 40.0, 45.0),  // X before Z
            new Vec3(55.0, 40.0, 65.0),  // Z before X
            new Vec3(-65.0, -20.0, -45.0),
            new Vec3(-55.0, 20.0, 65.0));
        for (Vec3 movement : movements) {
            helper.assertTrue(LargeSweepCollisionResolver.shouldResolve(null, movement, box),
                "The test sweep did not cross the large-volume optimization threshold: " + movement);
            Vec3 expected = vanillaReference(null, movement, box, level, existingEntityHits);
            Vec3 actual = Entity.collideBoundingBox(null, movement, box, level, existingEntityHits);
            helper.assertTrue(actual.equals(expected),
                "Optimized collision differs from vanilla for " + movement + ": expected " + expected + ", got " + actual);
            helper.assertTrue(!actual.equals(movement), "The fixture must exercise block collision clipping for " + movement);
        }
        helper.succeed();
    }

    @GameTest(template = "worldgen_test", timeoutTicks = 1200)
    public static void extremeEmptySweepCompletesWithoutCursorOverflow(GameTestHelper helper) {
        Level level = helper.getLevel();
        var base = helper.absolutePos(new net.minecraft.core.BlockPos(2, 100, 2));
        AABB box = new AABB(base.getX() + 0.2, base.getY(), base.getZ() + 0.2,
            base.getX() + 0.8, base.getY() + 1.8, base.getZ() + 0.8);
        Vec3 movement = new Vec3(20_000.0, 20_000.0, 20_000.0);

        helper.assertTrue(LargeSweepCollisionResolver.shouldResolve(null, movement, box),
            "The extreme sweep must use the decomposed collision path");
        long started = System.nanoTime();
        Vec3 actual = Entity.collideBoundingBox(null, movement, box, level, List.of());
        long elapsedNanos = System.nanoTime() - started;
        helper.assertTrue(actual.equals(movement),
            "An empty, unloaded sweep should preserve the requested movement; got " + actual);
        helper.assertTrue(elapsedNanos < java.util.concurrent.TimeUnit.SECONDS.toNanos(10),
            "The extreme sweep took too long: " + elapsedNanos / 1_000_000.0 + " ms");
        com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info(
            "[Collision GameTest] 20k-block 3-axis sweep completed in {} ms",
            elapsedNanos / 1_000_000.0);
        helper.succeed();
    }

    @GameTest(template = "worldgen_test", timeoutTicks = 1200)
    public static void verticalSweepOutsideBuildHeightMatchesVanillaAndPrunesEmptyRange(GameTestHelper helper) {
        Level level = helper.getLevel();
        var base = helper.absolutePos(new net.minecraft.core.BlockPos(2, 100, 2));
        int x = base.getX();
        int z = base.getZ();
        int topBlockY = level.getMaxBuildHeight() - 1;
        double startY = topBlockY - 10.0;
        AABB box = new AABB(x + 0.2, startY, z + 0.2, x + 0.8, startY + 1.8, z + 0.8);
        Vec3 movement = new Vec3(0.0, 20_000.0, 0.0);
        level.setBlock(new net.minecraft.core.BlockPos(x, topBlockY, z),
            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);

        Vec3 expected = vanillaReference(null, movement, box, level, List.of());
        Vec3 actual = Entity.collideBoundingBox(null, movement, box, level, List.of());
        helper.assertTrue(actual.equals(expected),
            "Build-height clipping changed the vanilla result: expected " + expected + ", got " + actual);
        helper.assertTrue(actual.y < movement.y,
            "The upper-world obstacle must still clip a sweep that continues beyond build height");

        // Entity-hit shapes are supplied separately from BlockCollisions and must still clip
        // movement even when the block-query portion of the sweep is outside build height.
        AABB entityBox = box.move(4.0, 0.0, 0.0);
        VoxelShape entityHit = Shapes.create(new AABB(x + 4.2, startY + 5_000.0, z + 0.2,
            x + 4.8, startY + 5_001.0, z + 0.8));
        List<VoxelShape> entityHits = List.of(entityHit);
        Vec3 entityExpected = vanillaReference(null, movement, entityBox, level, entityHits);
        Vec3 entityActual = Entity.collideBoundingBox(null, movement, entityBox, level, entityHits);
        helper.assertTrue(entityActual.equals(entityExpected),
            "Build-height pruning discarded an entity collision: expected " + entityExpected + ", got " + entityActual);
        helper.assertTrue(entityActual.y < movement.y,
            "An entity collision beyond build height must still clip movement");

        AABB emptyBox = box.move(8.0, 0.0, 0.0);
        CollisionCall legacy = () -> vanillaReference(null, movement, emptyBox, level, List.of());
        CollisionCall optimized = () -> Entity.collideBoundingBox(null, movement, emptyBox, level, List.of());
        double legacyMedianMs = medianMillis(legacy);
        double optimizedMedianMs = medianMillis(optimized);
        com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info(
            "[Collision GameTest] 20k vertical sweep median: legacy={} ms, build-height bounded={} ms, speedup={}x",
            legacyMedianMs, optimizedMedianMs,
            optimizedMedianMs == 0.0 ? "n/a" : legacyMedianMs / optimizedMedianMs);
        helper.succeed();
    }

    @GameTest(template = "worldgen_test", timeoutTicks = 1200)
    public static void largeBlockCollisionScanMemoizesChunkGettersWithoutChangingShapes(GameTestHelper helper) {
        Level level = helper.getLevel();
        var base = helper.absolutePos(new net.minecraft.core.BlockPos(2, 100, 2));
        int x = base.getX();
        int y = base.getY();
        int z = base.getZ();
        for (int i = 0; i < 7; i++) {
            level.setBlock(new net.minecraft.core.BlockPos(x + 3 + i * 8, y + 1, z + 2 + i * 4),
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        }
        level.setBlock(new net.minecraft.core.BlockPos(x + 29, y + 1, z + 17),
            net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState(), 3);

        AABB query = new AABB(x + 0.25, y + 0.25, z + 0.25,
            x + 60.75, y + 3.75, z + 36.75);
        BlockCollisions<VoxelShape> legacyIterator = new BlockCollisions<>(
            level, null, query, false, (pos, shape) -> shape);
        BlockCollisionChunkCacheAccess legacyStats = (BlockCollisionChunkCacheAccess) legacyIterator;
        legacyStats.cmi$configureChunkGetterCacheForTest(false, true);
        List<VoxelShape> expected = drain(legacyIterator);
        long legacyChunkLookups = legacyStats.cmi$getOriginalChunkGetterCallCount();

        BlockCollisions<VoxelShape> optimizedIterator = new BlockCollisions<>(
            level, null, query, false, (pos, shape) -> shape);
        BlockCollisionChunkCacheAccess optimizedStats = (BlockCollisionChunkCacheAccess) optimizedIterator;
        optimizedStats.cmi$countOriginalChunkGetterCallsForTest();
        List<VoxelShape> actual = drain(optimizedIterator);
        int memoizedChunkCount = optimizedStats.cmi$getCachedChunkGetterCount();
        long optimizedCacheHits = optimizedStats.cmi$getChunkGetterCacheHitCount();
        long optimizedChunkLookups = optimizedStats.cmi$getOriginalChunkGetterCallCount();

        helper.assertTrue(sameShapeSequence(expected, actual),
            "Chunk-getter memoization changed block-collision shapes or their order");
        helper.assertTrue(memoizedChunkCount > 0,
            "The large scan did not activate per-iterator chunk-getter memoization");
        helper.assertTrue(optimizedCacheHits > 0 && legacyChunkLookups > optimizedChunkLookups,
            "The test scan did not exercise repeated chunk lookups: legacy=" + legacyChunkLookups
                + ", optimized=" + optimizedChunkLookups + ", cache hits=" + optimizedCacheHits);

        com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info(
            "[Collision GameTest] large BlockCollisions scan: legacy chunk getter calls={}, optimized calls={}, cache hits={}, cached chunks={}, shapes={}",
            legacyChunkLookups, optimizedChunkLookups, optimizedCacheHits, memoizedChunkCount, actual.size());
        helper.succeed();
    }

    private static List<VoxelShape> drain(BlockCollisions<VoxelShape> collisions) {
        ArrayList<VoxelShape> shapes = new ArrayList<>();
        while (collisions.hasNext()) {
            shapes.add(collisions.next());
        }
        return shapes;
    }

    private static boolean sameShapeSequence(List<VoxelShape> expected, List<VoxelShape> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            VoxelShape a = expected.get(i);
            VoxelShape b = actual.get(i);
            if (!a.toAabbs().equals(b.toAabbs())) return false;
            for (Direction.Axis axis : Direction.Axis.values()) {
                if (!a.getCoords(axis).equals(b.getCoords(axis))) return false;
            }
        }
        return true;
    }

    /** Mirrors Entity.collideBoundingBox and collideWithShapes as the unoptimized differential baseline. */
    private static Vec3 vanillaReference(Entity entity, Vec3 movement, AABB collisionBox,
                                        Level level, List<VoxelShape> potentialHits) {
        ArrayList<VoxelShape> shapes = new ArrayList<>(potentialHits.size() + 16);
        shapes.addAll(potentialHits);
        AABB broadSweep = collisionBox.expandTowards(movement);
        var border = level.getWorldBorder();
        if (entity != null && border.isInsideCloseToBorder(entity, broadSweep)) {
            shapes.add(border.getCollisionShape());
        }
        for (VoxelShape shape : level.getBlockCollisions(entity, broadSweep)) {
            shapes.add(shape);
        }

        double dx = movement.x;
        double dy = movement.y;
        double dz = movement.z;
        if (dy != 0.0) {
            dy = Shapes.collide(Direction.Axis.Y, collisionBox, shapes, dy);
            if (dy != 0.0) collisionBox = collisionBox.move(0.0, dy, 0.0);
        }
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0.0) {
            dz = Shapes.collide(Direction.Axis.Z, collisionBox, shapes, dz);
            if (dz != 0.0) collisionBox = collisionBox.move(0.0, 0.0, dz);
        }
        if (dx != 0.0) {
            dx = Shapes.collide(Direction.Axis.X, collisionBox, shapes, dx);
            if (!zFirst && dx != 0.0) collisionBox = collisionBox.move(dx, 0.0, 0.0);
        }
        if (!zFirst && dz != 0.0) {
            dz = Shapes.collide(Direction.Axis.Z, collisionBox, shapes, dz);
        }
        return new Vec3(dx, dy, dz);
    }

    private static double medianMillis(CollisionCall call) {
        for (int i = 0; i < 3; i++) call.run();
        long[] samples = new long[9];
        for (int i = 0; i < samples.length; i++) {
            long started = System.nanoTime();
            call.run();
            samples[i] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2] / 1_000_000.0;
    }

    @FunctionalInterface
    private interface CollisionCall {
        Vec3 run();
    }

    private LargeSweepCollisionGameTests() {}
}
