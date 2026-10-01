package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.List;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.ryanhcode.sable.api.block.BlockSubLevelDynamicCollider;
import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

/** Direct Sable 2.0.5 API calls; instantiated only after the mod-presence gate. */
final class PackageSableCollisionSources implements PackageMovingCollisionSources.OptionalBridge {
    private final PackageMovingCollisionSources host;
    private final ClientLevel level;
    private final Vector3d scratch = new Vector3d();

    PackageSableCollisionSources(PackageMovingCollisionSources host, ClientLevel level) {
        this.host = host;
        this.level = level;
    }

    @Override public boolean discover(long deadlineNanos, int remainingVisits) {
        host.owner();
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return true;
        int visited = 0;
        for (ClientSubLevel sub : container.getAllSubLevels()) {
            if (++visited > remainingVisits || System.nanoTime() - deadlineNanos >= 0) return false;
            if (sub.isRemoved()) continue;
            var key = new PackageMovingGeometry.Key(1, sub.getUniqueId());
            var old = host.source(key);
            if (!(old instanceof Source source) || source.sub != sub) {
                if (!host.install(key, new Source(sub))) return false;
            }
        }
        return true;
    }

    @Override public Vec3 projectContaining(Entity entity, Vec3 point, boolean previous) {
        host.owner();
        var parent = Sable.HELPER.getContaining(entity);
        return parent == null ? point : project(previous ? parent.lastPose() : parent.logicalPose(), point, scratch);
    }

    static Vec3 project(Pose3dc pose, Vec3 point, Vector3d scratch) {
        scratch.set(point.x, point.y, point.z);
        pose.transformPosition(scratch, scratch);
        return new Vec3(scratch.x, scratch.y, scratch.z);
    }

    @Override public boolean unsupported(BlockState state) {
        host.owner();
        return state.getBlock() instanceof BlockSubLevelDynamicCollider || BlockWithSubLevelCollisionCallback.hasCallback(state);
    }

    private final class Source extends PackageMovingCollisionSources.Base {
        final ClientSubLevel sub;
        final PackageMovingGeometry.Key key;
        LevelPlot plot;
        boolean finalized;

        Source(ClientSubLevel sub) {
            super(PackageSableCollisionSources.this.host);
            this.sub = sub;
            key = new PackageMovingGeometry.Key(1, sub.getUniqueId());
            refresh();
        }
        @Override public PackageMovingGeometry.Key key() { return key; }
        @Override public boolean alive() { owner(); return !sub.isRemoved(); }
        @Override void refresh() {
            boolean ready = sub.isFinalized();
            if (ready != finalized) { finalized = ready; version++; }
            plot = sub.getPlot();
            var b = plot.getBoundingBox();
            // Inclusive absolute plot bounds, not coordinates relative to a sublevel.
            bounds(b.minX(), b.minY(), b.minZ(), b.maxX() + 1., b.maxY() + 1., b.maxZ() + 1.);
        }
        @Override Vec3 project(double x, double y, double z, boolean previous) {
            return PackageSableCollisionSources.project(previous ? sub.lastPose() : sub.logicalPose(), new Vec3(x, y, z), scratch);
        }
        @Override public PackageMovingGeometry.Pose pose(boolean previous) {
            owner();
            return PackageSablePose.capture(previous ? sub.lastPose() : sub.logicalPose(), ox, oy, oz, scratch);
        }
        @Override VoxelShape collisionShape(BlockState state, LevelReader world, BlockPos pos) {
            return state.getBlock() instanceof BlockSubLevelCollisionShape custom
                    ? custom.getSubLevelCollisionShape(world, state) : super.collisionShape(state, world, pos);
        }
        @Override public PackageMovingCollisionCache.Cursor open() {
            owner();
            if (!sub.isFinalized()) throw new IllegalStateException("Sable initial chunks not finalized");
            var holders = plot.getLoadedChunks().iterator();
            return new PackageMovingCollisionCache.Cursor() {
                LevelChunk chunk;
                int section, index;
                final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

                public boolean hasNext() { owner(); return chunk != null || holders.hasNext(); }
                public List<PackageMovingGeometry.Box> next() {
                    owner();
                    if (chunk == null) { chunk = holders.next().getChunk(); section = 0; index = 0; }
                    if (section >= chunk.getSections().length) { chunk = null; return List.of(); }
                    var cells = chunk.getSections()[section];
                    if (cells.hasOnlyAir()) { section++; index = 0; return List.of(); }
                    pos.set(chunk.getPos().getMinBlockX() + (index & 15), chunk.getSectionYFromSectionIndex(section) * 16 + (index >>> 8),
                            chunk.getPos().getMinBlockZ() + ((index >>> 4) & 15));
                    if (!level.hasChunkAt(pos)) return null;
                    if (index == 0) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        int nx = pos.getX() + dx * 16, nz = pos.getZ() + dz * 16;
                        if (nx + 16 > raw.x0() && nx < raw.x1() && nz + 16 > raw.z0() && nz < raw.z1()
                                && !level.hasChunkAt(new BlockPos(nx, pos.getY(), nz))) return null;
                    }
                    var result = shapes(cells.getBlockState(index & 15, index >>> 8, (index >>> 4) & 15), level, pos);
                    if (++index == 4096) { section++; index = 0; }
                    return result;
                }
            };
        }
    }
}
