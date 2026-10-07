package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.List;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.block.BlockSubLevelCollisionShape;
import dev.ryanhcode.sable.api.block.BlockSubLevelDynamicCollider;
import dev.ryanhcode.sable.api.physics.collider.VoxelColliderData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ChunkPos;
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
            PackageMovingGeometry.Key key;
            try { key = new PackageMovingGeometry.Key(1, sub.getUniqueId()); }
            catch (RuntimeException | LinkageError unavailable) { continue; }
            if (!trackable(sub)) { host.forget(key); continue; }
            var old = host.source(key);
            try {
                if (!(old instanceof Source source && source.sub == sub)) {
                    var source = new Source(sub);
                    if (!source.available()) host.forget(key);
                    else if (!host.install(key, source)) return false;
                }
            } catch (RuntimeException | LinkageError unavailable) {
                // One incomplete Sable plot must not make every package's shared moving
                // collision history unavailable. Skip this source and try it again later.
                host.forget(key);
            }
        }
        return true;
    }

    private static boolean trackable(ClientSubLevel sub) {
        try {
            var plot = sub.getPlot();
            return PackageSableCollisionPolicy.trackable(sub.isRemoved(), sub.isFinalized(), plot != null,
                    plot != null && !plot.getLoadedChunks().isEmpty());
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
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

    @Override public List<PackageMovingGeometry.Box> dynamicBoxes(BlockState state,BlockPos pos,int ox,int oy,int oz,float friction) {
        host.owner();
        if(!(state.getBlock() instanceof BlockSubLevelDynamicCollider dynamic))return null;
        return PackageMovingDynamicBoxes.capture(sink->dynamic.buildBoxes(new VoxelColliderData() {
            @Override public void addBox(org.joml.Vector3dc min,org.joml.Vector3dc max) {
                sink.add(min.x(),min.y(),min.z(),max.x(),max.y(),max.z());
            }
            @Override public void clearBoxes(){sink.clear();}
        }),pos.getX(),pos.getY(),pos.getZ(),ox,oy,oz,friction);
    }

    private final class Source extends PackageMovingCollisionSources.Base {
        final ClientSubLevel sub;
        final PackageMovingGeometry.Key key;
        LevelPlot plot;
        boolean finalized;
        final PackageMovingCollisionCells capturedCells=new PackageMovingCollisionCells();
        boolean contextualGeometry;

        Source(ClientSubLevel sub) {
            super(PackageSableCollisionSources.this.host);
            this.sub = sub;
            key = new PackageMovingGeometry.Key(1, sub.getUniqueId());
        }
        @Override public PackageMovingGeometry.Key key() { return key; }
        @Override public boolean alive() { owner(); return trackable(sub); }
        boolean available() {
            owner();
            if (!trackable(sub)) return false;
            try {
                refresh();
                return raw != null;
            } catch (RuntimeException | LinkageError unavailable) {
                return false;
            }
        }
        @Override void refresh() {
            boolean ready = sub.isFinalized();
            if (ready != finalized) { finalized = ready; clearCapturedCells(); version++; }
            LevelPlot nextPlot = sub.getPlot();
            if (nextPlot != plot) {
                plot = nextPlot;
                clearCapturedCells();
                version++;
            }
            var b = plot.getBoundingBox();
            // PlotChunkHolder keeps the parent storage chunk position. LevelPlot's bounds
            // are therefore already in the parent world's absolute block coordinates.
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
        @Override void clearCapturedCells(){capturedCells.clear();contextualGeometry=false;}
        @Override boolean cellChanged(BlockPos position) {
            owner();
            // Optional custom/dynamic shapes may query remote context. A local
            // numeric comparison cannot prove that the rest of this source is unchanged.
            if(contextualGeometry)return true;
            try {
                return capturedCells.changed(position.getX(),position.getY(),position.getZ(),raw,cell->{
                    var holder=plot.getChunkHolder(plot.toLocal(new ChunkPos(cell.x()>>4,cell.z()>>4)));
                    if(holder==null||holder.getChunk()==null)return null;
                    var chunk=holder.getChunk();int section=chunk.getSectionIndex(cell.y());
                    var sections=chunk.getSections();if(section<0||section>=sections.length)return null;
                    var cells=sections[section];BlockState state=cells==null?net.minecraft.world.level.block.Blocks.AIR.defaultBlockState():
                            cells.getBlockState(cell.x()&15,cell.y()&15,cell.z()&15);
                    if(contextualShape(state))return null;
                    BlockPos pos=new BlockPos(cell.x(),cell.y(),cell.z());
                    var contextPos=new BlockPos.MutableBlockPos();
                    PackageSableCollisionCoordinates.contextPosition(pos,plot.getCenterBlock(),contextPos);
                    return shapes(state,plot.getEmbeddedLevelAccessor(),pos,contextPos);
                });
            }catch(RuntimeException|LinkageError unavailable){return true;}
        }
        private boolean contextualShape(BlockState state){
            return state.getBlock().hasDynamicShape()||state.getBlock() instanceof BlockSubLevelCollisionShape
                    ||state.getBlock() instanceof BlockSubLevelDynamicCollider;
        }
        private void rememberEmptySection(LevelChunk chunk,int section){
            int x=chunk.getPos().getMinBlockX(),z=chunk.getPos().getMinBlockZ(),y=chunk.getSectionYFromSectionIndex(section);
            if(x<raw.x1()&&(double)x+16>raw.x0()&&z<raw.z1()&&(double)z+16>raw.z0()
                    &&y*16.0<raw.y1()&&(y+1.0)*16>raw.y0())
                capturedCells.emptySection(chunk.getPos().x,y,chunk.getPos().z);
        }
        private void rememberCell(BlockPos pos,List<PackageMovingGeometry.Box> boxes){
            if(raw.contains(pos.getX(),pos.getY(),pos.getZ()))capturedCells.record(pos.getX(),pos.getY(),pos.getZ(),boxes);
        }
        @Override public PackageMovingCollisionCache.Cursor open() {
            owner();
            if (!sub.isFinalized()) throw new IllegalStateException("Sable initial chunks not finalized");
            clearCapturedCells();
            LevelReader world = plot.getEmbeddedLevelAccessor();
            var center = plot.getCenterBlock();
            var holders = plot.getLoadedChunks().iterator();
            return new PackageMovingCollisionCache.Cursor() {
                LevelChunk chunk;
                int section, index;
                final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                final BlockPos.MutableBlockPos contextPos = new BlockPos.MutableBlockPos();

                public boolean hasNext() { owner(); return chunk != null || holders.hasNext(); }
                public List<PackageMovingGeometry.Box> next() {
                    owner();
                    if (chunk == null) { chunk = holders.next().getChunk(); section = 0; index = 0; }
                    var sections = chunk.getSections();
                    int nextSection=PackageSableCollisionSections.nextNonNull(sections, section);
                    for(int skipped=section;skipped<nextSection;skipped++)
                        rememberEmptySection(chunk,skipped);
                    section = nextSection;
                    if (section >= sections.length) { chunk = null; return List.of(); }
                    var cells = sections[section];
                    if (cells.hasOnlyAir()) {
                        rememberEmptySection(chunk,section);
                        section++; index = 0; return List.of();
                    }
                    pos.set(chunk.getPos().getMinBlockX() + (index & 15), chunk.getSectionYFromSectionIndex(section) * 16 + (index >>> 8),
                            chunk.getPos().getMinBlockZ() + ((index >>> 4) & 15));
                    PackageSableCollisionCoordinates.contextPosition(pos, center, contextPos);
                    // The plot holder owns this chunk. The parent Level's loaded-chunk test
                    // can reject a valid plot chunk, leaving a pose-only collider forever.
                    try {
                        var state=cells.getBlockState(index & 15, index >>> 8, (index >>> 4) & 15);
                        contextualGeometry|=contextualShape(state);
                        var boxes=shapes(state, world, pos, contextPos);
                        rememberCell(pos,boxes);return boxes;
                    } catch (RuntimeException | LinkageError unavailableShape) {
                        // One modded block with an unavailable shape must not make the
                        // entire plot BVH permanently unavailable. Keep a fail-closed
                        // marker at this cell so only bodies touching it pause.
                        var boxes=List.of(new PackageMovingGeometry.Box(pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz,
                                pos.getX() - ox + 1, pos.getY() - oy + 1, pos.getZ() - oz + 1,
                                .6f, PackageCollisionCache.UNSUPPORTED));
                        rememberCell(pos,boxes);return boxes;
                    } finally {
                        if (++index == 4096) { section++; index = 0; }
                    }
                }
            };
        }
    }
}
