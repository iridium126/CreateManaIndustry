package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.function.Consumer;
import com.simibubi.create.content.contraptions.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.fml.ModList;

/** Owner-thread capture. Optional APIs are isolated behind a lazily loaded bridge. */
public final class PackageMovingCollisionSources {
    private final ClientLevel level;
    private final Thread owner = Thread.currentThread();
    private final Map<PackageMovingGeometry.Key, Base> sources = new LinkedHashMap<>();
    /** Borrowed owner-thread view; callers must finish iteration before the next discovery. */
    private final Collection<Base> view = Collections.unmodifiableCollection(sources.values());
    private final OptionalBridge sable;
    private final String abiError;
    private static final int MAX_DISCOVERY_VISITS = PackageMovingCollisionGpu.MAX_STRUCTURES * 2;
    private String error;
    private Consumer<PackageMovingGeometry.Key> invalidated = key -> {};

    public PackageMovingCollisionSources(ClientLevel level) {
        this.level = level;
        OptionalBridge bridge = null;
        String failure = "";
        try {
            bridge = optionalBridge(ModList.get().isLoaded("sable"), this);
        } catch (RuntimeException | LinkageError mismatch) {
            failure = describe(mismatch);
        }
        sable = bridge;
        abiError = failure;
        error = failure;
    }

    // The false branch must not link Sable, including its companion classes.
    static OptionalBridge optionalBridge(boolean loaded, PackageMovingCollisionSources host) {
        return loaded ? new PackageSableCollisionSources(host, host.level) : null;
    }

    interface OptionalBridge {
        /** False means the scan was incomplete; no moving-scene coverage may be claimed. */
        boolean discover(long deadlineNanos, int remainingVisits);
        Vec3 projectContaining(Entity entity, Vec3 point, boolean previous);
        boolean unsupported(BlockState state);
        /** Null for ordinary blocks, otherwise copied unit-voxel geometry or a fail-closed box. */
        List<PackageMovingGeometry.Box> dynamicBoxes(BlockState state,BlockPos pos,int ox,int oy,int oz,float friction);
    }

    public void onInvalidated(Consumer<PackageMovingGeometry.Key> listener) {
        owner();
        invalidated = Objects.requireNonNull(listener);
    }

    public String error() { return error; }

    public Collection<? extends PackageMovingCollisionCache.Source> discover(long deadlineNanos) {
        owner();
        error = abiError;
        try {
            sources.values().removeIf(source -> !source.alive());
            if (!error.isEmpty() || System.nanoTime() - deadlineNanos >= 0) {
                if (error.isEmpty()) error = "Moving structure discovery budget exceeded";
                return view;
            }
            int visited = 0;
            for (var ref : ContraptionHandler.loadedContraptions.get(level).values()) {
                if (++visited > MAX_DISCOVERY_VISITS || System.nanoTime() - deadlineNanos >= 0) {
                    error = "Moving structure discovery budget exceeded";
                    break;
                }
                var entity = ref.get();
                if (entity == null || !entity.isAlive() || !entity.collisionEnabled() || entity.getContraption() == null) continue;
                var key = new PackageMovingGeometry.Key(0, entity.getUUID());
                var old = sources.get(key);
                if (!(old instanceof CreateSource create) || create.entity != entity) {
                    if (!install(key, new CreateSource(this, entity))) break;
                }
            }
            if (error.isEmpty() && sable != null && !sable.discover(deadlineNanos, MAX_DISCOVERY_VISITS - visited)
                    && error.isEmpty())
                error = "Moving sub level discovery budget exceeded";
            if (error.isEmpty() && System.nanoTime() - deadlineNanos >= 0)
                error = "Moving structure discovery budget exceeded";
        } catch (RuntimeException | LinkageError failure) {
            // Retaining a previous snapshot must never mean confirmed coverage.
            error = describe(failure);
        }
        return view;
    }

    private static String describe(Throwable failure) {
        return "Moving collision source unavailable: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    Base source(PackageMovingGeometry.Key key) { return sources.get(key); }

    boolean install(PackageMovingGeometry.Key key, Base source) {
        owner();
        if (!sources.containsKey(key) && sources.size() >= PackageMovingCollisionGpu.MAX_STRUCTURES) {
            error = "Moving structure capacity exceeded";
            return false;
        }
        sources.put(key, source);
        return true;
    }

    private void changed(Base source) {
        source.version++;
        invalidated.accept(source.key());
    }

    public void contraptionChanged(Contraption target) {
        owner();
        for (Base source : sources.values())
            if (source instanceof CreateSource create && create.contraption == target) changed(source);
    }

    /** A leave event identifies the exact source that disappeared; other known sources stay covered. */
    public boolean forget(AbstractContraptionEntity entity) {
        owner();var key=new PackageMovingGeometry.Key(0,entity.getUUID());Base source=sources.get(key);
        if(!(source instanceof CreateSource create)||create.entity!=entity)return false;
        sources.remove(key);return true;
    }

    public void blockChanged(BlockPos position) {
        owner();
        for (Base source : sources.values())
            if (source.key().kind() == 1 && source.raw != null
                    && source.raw.contains(position.getX(), position.getY(), position.getZ())) changed(source);
    }

    public void chunkChanged(int x, int z) {
        owner();
        for (Base source : sources.values())
            if (source.key().kind() == 1 && source.raw != null && x * 16.0 < source.raw.x1()
                    && x * 16.0 + 16 > source.raw.x0() && z * 16.0 < source.raw.z1()
                    && z * 16.0 + 16 > source.raw.z0()) changed(source);
    }

    void owner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Moving source off owner thread");
    }

    static abstract class Base implements PackageMovingCollisionCache.Source {
        final PackageMovingCollisionSources host;
        long version = 1;
        int ox, oy, oz;
        PackageMovingGeometry.Bounds raw;

        Base(PackageMovingCollisionSources host) { this.host = host; }
        final void owner() { host.owner(); }
        @Override public long revision() { owner(); refresh(); return version; }
        abstract void refresh();

        final void bounds(double x0, double y0, double z0, double x1, double y1, double z1) {
            var next = new PackageMovingGeometry.Bounds(x0, y0, z0, x1, y1, z1);
            if (!next.equals(raw)) {
                raw = next;
                version++;
                ox = (int) Math.floor((x0 + x1) * .5);
                oy = (int) Math.floor((y0 + y1) * .5);
                oz = (int) Math.floor((z0 + z1) * .5);
            }
        }

        @Override public PackageMovingGeometry.Bounds bounds() {
            owner(); refresh();
            return new PackageMovingGeometry.Bounds(raw.x0() - ox, raw.y0() - oy, raw.z0() - oz,
                    raw.x1() - ox, raw.y1() - oy, raw.z1() - oz);
        }

        abstract Vec3 project(double x, double y, double z, boolean previous);

        @Override public PackageMovingGeometry.Pose pose(boolean previous) {
            owner();
            Vec3 t = project(ox, oy, oz, previous), x = project(ox + 1., oy, oz, previous).subtract(t),
                    y = project(ox, oy + 1., oz, previous).subtract(t), z = project(ox, oy, oz + 1., previous).subtract(t);
            return new PackageMovingGeometry.Pose(x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z, t.x, t.y, t.z);
        }

        VoxelShape collisionShape(BlockState state, LevelReader world, BlockPos pos) {
            return state.getCollisionShape(world, pos, CollisionContext.empty());
        }

        final List<PackageMovingGeometry.Box> shapes(BlockState state, LevelReader world, BlockPos pos) {
            boolean unsupported = state.is(Blocks.MOVING_PISTON) || state.is(Blocks.POWDER_SNOW)
                    || state.is(Blocks.SCAFFOLDING) || !state.getFluidState().isEmpty() || state.is(Blocks.FIRE)
                    || host.sable != null && host.sable.unsupported(state);
            float friction = state.getFriction(world, pos, null);
            var result = new ArrayList<PackageMovingGeometry.Box>();
            if (unsupported) {
                result.add(new PackageMovingGeometry.Box(pos.getX() - ox, pos.getY() - oy, pos.getZ() - oz,
                        pos.getX() - ox + 1, pos.getY() - oy + 1, pos.getZ() - oz + 1,
                        friction, PackageCollisionCache.UNSUPPORTED));
            } else {
                List<PackageMovingGeometry.Box> dynamic=host.sable==null?null:
                        host.sable.dynamicBoxes(state,pos,ox,oy,oz,friction);
                if(dynamic!=null){result.addAll(dynamic);return result;}
                collisionShape(state, world, pos).forAllBoxes((a, b, c, d, e, f) -> {
                    if (a < d && b < e && c < f) result.add(new PackageMovingGeometry.Box(
                            (float) (pos.getX() - ox + a), (float) (pos.getY() - oy + b), (float) (pos.getZ() - oz + c),
                            (float) (pos.getX() - ox + d), (float) (pos.getY() - oy + e), (float) (pos.getZ() - oz + f), friction, 0));
                });
            }
            return result;
        }
    }

    private static final class CreateSource extends Base {
        final AbstractContraptionEntity entity;
        Contraption contraption;

        CreateSource(PackageMovingCollisionSources host, AbstractContraptionEntity entity) {
            super(host); this.entity = entity; refresh();
        }
        @Override public PackageMovingGeometry.Key key() { return new PackageMovingGeometry.Key(0, entity.getUUID()); }
        @Override public boolean alive() { return entity.isAlive() && entity.collisionEnabled() && entity.getContraption() != null; }
        @Override void refresh() {
            Contraption next = entity.getContraption();
            if (next != contraption) { contraption = next; version++; }
            var b = contraption.bounds;
            bounds(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        }
        @Override Vec3 project(double x, double y, double z, boolean previous) {
            Vec3 result = entity.toGlobalVector(new Vec3(x, y, z), previous ? 0 : 1, previous);
            return host.sable == null ? result : host.sable.projectContaining(entity, result, previous);
        }
        @Override public PackageMovingCollisionCache.Cursor open() {
            owner();
            var iterator = contraption.getBlocks().values().iterator();
            var world = contraption.getContraptionWorld();
            return new PackageMovingCollisionCache.Cursor() {
                public boolean hasNext() { return iterator.hasNext(); }
                public List<PackageMovingGeometry.Box> next() { owner(); var info = iterator.next(); return shapes(info.state(), world, info.pos()); }
            };
        }
    }
}
