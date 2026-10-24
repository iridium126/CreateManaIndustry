package com.iridium126.createmanaindustry.dimension.light;

import java.util.*;
import java.util.function.*;
import it.unimi.dsi.fastutil.longs.*;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.cube.*;
import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.lighting.*;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;

/** Independent sky bands and overlapping block-light windows; only core sections own output. */
public final class AllvrLightEngine {
    public static final int CORE_SIZE = 2048;
    public static final int HALO = 16;
    public static final int VANILLA_MIN_Y = -CORE_SIZE / 2 - HALO;
    public static final int VANILLA_MAX_Y = CORE_SIZE / 2 + HALO - 1;
    public static final int VANILLA_WINDOW_SIZE = CORE_SIZE + 2 * HALO;
    public interface Access {
        BlockState getBlockState(BlockPos pos);
        boolean isLoaded(BlockPos pos);
        default BlockEntity getBlockEntity(BlockPos pos) { return null; }
    }
    private final Object lock = new Object();
    private final Level level;
    private final Access access;
    private final Long2ObjectOpenHashMap<AllvrCube> loaded = new Long2ObjectOpenHashMap<>();
    private final Map<Integer, LongOpenHashSet> cubesByY = new HashMap<>();
    private final Map<Integer, Window> windows = new HashMap<>();
    private final LongOpenHashSet dirtyCubes = new LongOpenHashSet();
    private final Set<AllvrSectionCoord> changedSections = new HashSet<>();
    private record LightValue(byte[] sky, byte[] block) {}
    private static final byte[][] FILLED = new byte[16][];
    static { for (int i = 0; i < 16; i++) FILLED[i] = new byte[]{(byte) (i | i << 4)}; }
    private static byte[] compact(DataLayer data) {
        if (data.isDefinitelyHomogenous()) return FILLED[data.get(0, 0, 0)];
        byte[] bytes = data.getData();
        byte first = bytes[0]; boolean uniform = true;
        for (byte value : bytes) if (value != first) { uniform = false; break; }
        return uniform && (first & 15) == ((first >>> 4) & 15) ? FILLED[first & 15] : bytes;
    }
    private final Map<AllvrSectionCoord, LightValue> published = new HashMap<>();
    private int cursor;
    public AllvrLightEngine(Level level, Access access) { this.level = level; this.access = access; }
    static int windowIndex(int y) { return Math.floorDiv(y, CORE_SIZE); }
    static int windowOriginY(int index) { return index * CORE_SIZE + CORE_SIZE / 2; }
    public void onCubeLoaded(AllvrCube cube) {
        synchronized (lock) {
            long key = cube.getPos().asLong();
            var old = loaded.get(key); if (old != null) onCubeUnloaded(old);
            loaded.put(key, cube);
            cubesByY.computeIfAbsent(cube.getPos().getY(), y -> new LongOpenHashSet()).add(key);
            int index = windowIndex(cube.getPos().minBlockY());
            Window owner = windows.get(index);
            if (owner == null) {
                owner = new Window(index); windows.put(index, owner);
                // Only one neighbouring cube layer intersects each 16-block halo.
                for (int cy : new int[]{(index * CORE_SIZE >> 5) - 1, ((index + 1) * CORE_SIZE >> 5)}) {
                    var keys = cubesByY.get(cy);
                    if (keys != null) for (long other : keys) owner.add(loaded.get(other));
                }
            }
            var restoredSky = cube.takeRestoredSkyLight(); var restoredBlock = cube.takeRestoredBlockLight();
            sections(cube, (coord, section, i) -> {
                if (restoredSky != null && restoredBlock != null) published.put(coord, new LightValue(compact(restoredSky[i]), compact(restoredBlock[i])));
            });
            for (Window window : List.copyOf(windows.values())) if (window.intersects(cube)) window.add(cube);
            AllvrNativeBlockHalo.publish(level, cube, false);
            dirtyCubes.add(key);
        }
    }
    public void onCubeUnloaded(AllvrCube cube) {
        synchronized (lock) {
            long key = cube.getPos().asLong(); if (loaded.get(key) != cube) return;
            loaded.remove(key);
            var keys = cubesByY.get(cube.getPos().getY());
            if (keys != null) { keys.remove(key); if (keys.isEmpty()) cubesByY.remove(cube.getPos().getY()); }
            for (Window window : List.copyOf(windows.values())) {
                window.remove(cube);
                if (window.coreReferences == 0) windows.remove(window.index);
            }
            sections(cube, (coord, section, i) -> { published.remove(coord); changedSections.remove(coord); });
            AllvrNativeBlockHalo.publish(level, cube, true);
            dirtyCubes.add(key);
        }
    }
    public void onBlockChanged(BlockPos pos) {
        if (!AllvrDimensionLimits.isInBounds(pos)) return;
        synchronized (lock) {
            for (Window window : windows.values()) if (window.containsBlockInput(pos.getY())) {
                window.block.checkBlock(window.local(pos));
                var column = window.columns.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                if (window.ownsSky(pos.getY()) && column != null) {
                    column.skySources.invalidate(); window.sky.checkBlock(window.local(pos));
                }
            }
            var cube = loaded.get(AllvrCubePos.asLong(pos));
            if (cube != null) AllvrNativeBlockHalo.changed(level, cube, pos);
        }
    }
    /** Native chunks supply only the single section bordering each cube/column seam. */
    public void onNativeColumnChanged(int x, int z) {
        synchronized (lock) { for (Window window : windows.values()) window.nativeHalo(x, z); }
    }
    public void onNativeColumnUnloaded(int x, int z) {
        AllvrNativeBlockHalo.nativeColumnUnloaded(level, x, z);
        synchronized (lock) { for (Window window : windows.values()) window.nativeHalo(x, z, true); }
    }
    public int tick(int budget) {
        if (budget <= 0) return 0;
        synchronized (lock) {
            AllvrNativeBlockHalo.flush(level);
            var active = windows.values().toArray(Window[]::new);
            int work = 0;
            for (int n = 0; n < active.length; n++) {
                var window = active[Math.floorMod(cursor++, active.length)];
                if (window.block.hasLightWork()) work += window.block.runLightUpdates();
                if (window.sky.hasLightWork()) work += window.sky.runLightUpdates();
                if (work >= budget) break;
            }
            for (var coord : List.copyOf(changedSections)) {
                var cube = loaded.get(coord.cubeKey());
                if (cube == null) continue;
                var data = sectionData(coord.sectionPos());
                var value = new LightValue(compact(data[0]), compact(data[1]));
                var old = published.put(coord, value);
                if (old == null || !Arrays.equals(old.sky, value.sky) || !Arrays.equals(old.block, value.block)) {
                    cube.markLightDirty(); dirtyCubes.add(coord.cubeKey());
                }
            }
            changedSections.clear(); return work;
        }
    }
    public int blockLight(BlockPos pos) { synchronized (lock) { var w = windows.get(windowIndex(pos.getY())); return w == null ? 0 : w.block.getLayerListener(LightLayer.BLOCK).getLightValue(w.local(pos)); } }
    public int skyLight(BlockPos pos) { synchronized (lock) { var w = windows.get(windowIndex(pos.getY())); return w == null ? 0 : w.sky.getLayerListener(LightLayer.SKY).getLightValue(w.local(pos)); } }
    public int light(LightLayer layer, BlockPos pos) { return layer == LightLayer.BLOCK ? blockLight(pos) : skyLight(pos); }
    public int rawLight(BlockPos pos, int amount) { return Math.max(blockLight(pos), skyLight(pos) - amount); }
    public DataLayer[] sectionData(SectionPos pos) {
        synchronized (lock) {
            var w = windows.get(windowIndex(pos.minBlockY()));
            if (w == null) return new DataLayer[]{new DataLayer(), new DataLayer()};
            var local = SectionPos.of(pos.getX(), pos.getY() - (w.origin >> 4), pos.getZ());
            return new DataLayer[]{copy(w.sky.getLayerListener(LightLayer.SKY).getDataLayerData(local)), copy(w.block.getLayerListener(LightLayer.BLOCK).getDataLayerData(local))};
        }
    }
    public DataLayer[] copySectionData(SectionPos pos) { return sectionData(pos); }
    private static DataLayer copy(DataLayer data) { return data == null ? new DataLayer() : data.copy(); }
    public boolean hasLoadedCube(long key) { synchronized (lock) { return loaded.containsKey(key); } }
    public LongOpenHashSet drainDirtyCubes() { synchronized (lock) { var result = new LongOpenHashSet(dirtyCubes); dirtyCubes.clear(); return result; } }
    public void invalidateAllCachedSections() {}
    public void invalidateCubeSections(long key) {}
    public void clear() { synchronized (lock) { windows.clear(); loaded.clear(); cubesByY.clear(); dirtyCubes.clear(); changedSections.clear(); published.clear(); AllvrNativeBlockHalo.clear(level); } }
    private interface SectionConsumer { void accept(AllvrSectionCoord coord, LevelChunkSection section, int index); }
    private static void sections(AllvrCube cube, SectionConsumer consumer) {
        for (int i = 0; i < 8; i++) consumer.accept(new AllvrSectionCoord((cube.getPos().getX() << 1) + (i & 1), (cube.getPos().getY() << 1) + (i >> 2), (cube.getPos().getZ() << 1) + ((i >> 1) & 1)), cube.getSections()[i], i);
    }
    private final class Window {
        final int index, origin, min, max;
        final View blockView, skyView;
        final LevelLightEngine block, sky;
        final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
        final LongOpenHashSet inputs = new LongOpenHashSet();
        final Long2IntOpenHashMap nativeReferences = new Long2IntOpenHashMap();
        int coreReferences;
        Window(int index) {
            this.index = index; origin = windowOriginY(index); min = index * CORE_SIZE; max = min + CORE_SIZE;
            blockView = new View(this, false); skyView = new View(this, true);
            block = new LevelLightEngine(new Getter(this, false), true, false);
            sky = new LevelLightEngine(new Getter(this, true), false, true);
        }
        boolean ownsSky(int y) { return y >= min && y < max && !AllvrDimensionLimits.isVanillaY(y); }
        boolean containsBlockInput(int y) { return y >= min - HALO && y < max + HALO; }
        boolean intersects(AllvrCube cube) { int y = cube.getPos().minBlockY(); return y < max + HALO && y + 32 > min - HALO; }
        BlockPos local(BlockPos pos) { return new BlockPos(pos.getX(), pos.getY() - origin, pos.getZ()); }
        Column column(int x, int z) { return columns.computeIfAbsent(ChunkPos.asLong(x, z), key -> new Column(this, x, z)); }
        void add(AllvrCube cube) {
            if (cube == null || !intersects(cube) || !inputs.add(cube.getPos().asLong())) return;
            if (windowIndex(cube.getPos().minBlockY()) == index) coreReferences++;
            var affected = new HashSet<ChunkPos>();
            sections(cube, (coord, section, i) -> {
                if (!containsBlockInput(coord.origin().getY())) return;
                var col = column(coord.x(), coord.z()); col.sections.put(coord, section);
                var local = SectionPos.of(coord.x(), coord.y() - (origin >> 4), coord.z());
                block.updateSectionStatus(local, false);
                if (ownsSky(coord.origin().getY())) { sky.updateSectionStatus(local, false); col.skySources.invalidate(); }
                affected.add(new ChunkPos(coord.x(), coord.z()));
            });
            if (cube.getPos().minBlockY() == 384 || cube.getPos().minBlockY() == -160)
                for (int z = (cube.getPos().getZ() << 1) - 1; z <= (cube.getPos().getZ() << 1) + 2; z++)
                    for (int x = (cube.getPos().getX() << 1) - 1; x <= (cube.getPos().getX() << 1) + 2; x++) {
                        nativeReferences.addTo(ChunkPos.asLong(x, z), 1); nativeHalo(x, z);
                    }
            for (var pos : affected) { block.propagateLightSources(pos); sky.propagateLightSources(pos); }
        }
        void remove(AllvrCube cube) {
            if (!inputs.remove(cube.getPos().asLong())) return;
            invalidateUnloadedSources(cube);
            invalidateUnloadedBoundary(cube);
            if (windowIndex(cube.getPos().minBlockY()) == index) coreReferences--;
            if (cube.getPos().minBlockY() == 384 || cube.getPos().minBlockY() == -160)
                for (int z = (cube.getPos().getZ() << 1) - 1; z <= (cube.getPos().getZ() << 1) + 2; z++)
                    for (int x = (cube.getPos().getX() << 1) - 1; x <= (cube.getPos().getX() << 1) + 2; x++) {
                        long column = ChunkPos.asLong(x, z);
                        if (nativeReferences.addTo(column, -1) <= 1) nativeReferences.remove(column);
                        nativeHalo(x, z);
                    }
            sections(cube, (coord, section, i) -> {
                var col = columns.get(coord.columnKey()); if (col == null || col.sections.remove(coord) == null) return;
                var local = SectionPos.of(coord.x(), coord.y() - (origin >> 4), coord.z());
                block.queueSectionData(LightLayer.BLOCK, local, null); block.updateSectionStatus(local, true);
                if (ownsSky(coord.origin().getY())) { sky.queueSectionData(LightLayer.SKY, local, null); sky.updateSectionStatus(local, true); col.skySources.invalidate(); }
                if (col.sections.isEmpty()) { block.setLightEnabled(new ChunkPos(coord.x(), coord.z()), false); sky.setLightEnabled(new ChunkPos(coord.x(), coord.z()), false); columns.remove(coord.columnKey()); }
            });
        }
        void invalidateUnloadedSources(AllvrCube cube) {
            sections(cube, (coord, section, i) -> {
                if (!containsBlockInput(coord.y() << 4) || !section.maybeHas(state -> state.hasDynamicLightEmission()
                    || state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) > 0)) return;
                var at = new BlockPos.MutableBlockPos();
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    var state = section.getBlockState(x, y, z);
                    at.set((coord.x() << 4) + x, (coord.y() << 4) + y - origin, (coord.z() << 4) + z);
                    if (state.hasDynamicLightEmission() || state.getLightEmission(blockView, at) > 0) block.checkBlock(at);
                }
            });
        }
        void invalidateUnloadedBoundary(AllvrCube cube) {
            // Removing section storage alone does not enqueue native decrease propagation.
            var p = cube.getPos(); var at = new BlockPos.MutableBlockPos();
            for (Direction face : Direction.values()) for (int a = 0; a < 32; a++) for (int b = 0; b < 32; b++) {
                switch (face.getAxis()) {
                    case X -> at.set(p.minBlockX() + (face == Direction.WEST ? -1 : 32), p.minBlockY() + a, p.minBlockZ() + b);
                    case Y -> at.set(p.minBlockX() + a, p.minBlockY() + (face == Direction.DOWN ? -1 : 32), p.minBlockZ() + b);
                    case Z -> at.set(p.minBlockX() + a, p.minBlockY() + b, p.minBlockZ() + (face == Direction.NORTH ? -1 : 32));
                }
                if (!containsBlockInput(at.getY()) || !access.isLoaded(at)) continue;
                var local = local(at);
                if (block.getLayerListener(LightLayer.BLOCK).getLightValue(local) != 0) block.checkBlock(local);
                if (ownsSky(at.getY()) && sky.getLayerListener(LightLayer.SKY).getLightValue(local) != 15) sky.checkBlock(local);
            }
        }
        void nativeHalo(int x, int z) { nativeHalo(x, z, false); }
        void nativeHalo(int x, int z, boolean unloading) {
            if (index != 0 && index != -1) return;
            int y = index == 0 ? 23 : -8;
            var coord = new AllvrSectionCoord(x, y, z);
            var col = columns.get(coord.columnKey());
            var local = SectionPos.of(x, y - (origin >> 4), z);
            if (unloading || nativeReferences.get(coord.columnKey()) <= 0 || !access.isLoaded(coord.origin())) {
                if (col != null && col.nativeSection != null) {
                    col.nativeSection = null;
                    for (var source : col.nativeEmitters) block.checkBlock(local(source));
                    col.nativeEmitters.clear();
                    int edgeY = index == 0 ? 384 : -129;
                    var edge = new BlockPos.MutableBlockPos();
                    for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++) {
                        edge.set((x << 4) + xx, edgeY, (z << 4) + zz);
                        if (access.isLoaded(edge)) block.checkBlock(local(edge));
                    }
                    block.queueSectionData(LightLayer.BLOCK, local, null); block.updateSectionStatus(local, true);
                    if (col.sections.isEmpty()) columns.remove(coord.columnKey());
                }
                return;
            }
            col = column(x, z);
            for (var source : col.nativeEmitters) block.checkBlock(local(source));
            col.nativeEmitters.clear();
            col.nativeSection = coord;
            block.updateSectionStatus(local, false); block.propagateLightSources(new ChunkPos(x, z));
        }
    }
    private final class View implements BlockGetter {
        final Window window; final boolean sky;
        View(Window window, boolean sky) { this.window = window; this.sky = sky; }
        BlockPos world(BlockPos pos) { return new BlockPos(pos.getX(), pos.getY() + window.origin, pos.getZ()); }
        public BlockState getBlockState(BlockPos local) {
            var pos = world(local);
            if (!AllvrDimensionLimits.isInBounds(pos) || !window.containsBlockInput(pos.getY()) || sky && !window.ownsSky(pos.getY()) || !access.isLoaded(pos)) return Blocks.VOID_AIR.defaultBlockState();
            if (AllvrDimensionLimits.isVanillaY(pos.getY())) {
                var column = window.columns.get(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                if (column == null || !AllvrSectionCoord.of(pos).equals(column.nativeSection)) return Blocks.VOID_AIR.defaultBlockState();
            }
            return access.getBlockState(pos);
        }
        public BlockEntity getBlockEntity(BlockPos local) { var pos = world(local); return access.isLoaded(pos) ? access.getBlockEntity(pos) : null; }
        public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        public int getMinBuildHeight() { return VANILLA_MIN_Y; }
        public int getHeight() { return VANILLA_WINDOW_SIZE; }
    }
    private final class Getter implements LightChunkGetter {
        final Window window; final boolean sky;
        Getter(Window window, boolean sky) { this.window = window; this.sky = sky; }
        public BlockGetter getLevel() { return sky ? window.skyView : window.blockView; }
        public LightChunk getChunkForLighting(int x, int z) { var column = window.columns.get(ChunkPos.asLong(x, z)); return column == null ? null : column.chunk(sky); }
        public void onLightUpdate(LightLayer layer, SectionPos local) { changedSections.add(new AllvrSectionCoord(local.getX(), local.getY() + (window.origin >> 4), local.getZ())); }
    }
    private final class Column {
        final Window window; final int x, z;
        final Map<AllvrSectionCoord, LevelChunkSection> sections = new HashMap<>();
        final SkySources skySources;
        final LightChunk blockChunk, skyChunk;
        AllvrSectionCoord nativeSection;
        final Set<BlockPos> nativeEmitters = new HashSet<>();
        Column(Window window, int x, int z) {
            this.window = window; this.x = x; this.z = z; skySources = new SkySources(this);
            blockChunk = createChunk(false); skyChunk = createChunk(true);
        }
        LightChunk chunk(boolean sky) { return sky ? skyChunk : blockChunk; }
        LightChunk createChunk(boolean sky) { return new LightChunk() {
            public BlockState getBlockState(BlockPos pos) { return (sky ? window.skyView : window.blockView).getBlockState(pos); }
            public BlockEntity getBlockEntity(BlockPos pos) { return (sky ? window.skyView : window.blockView).getBlockEntity(pos); }
            public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            public int getMinBuildHeight() { return VANILLA_MIN_Y; }
            public int getHeight() { return VANILLA_WINDOW_SIZE; }
            public ChunkSkyLightSources getSkyLightSources() { return skySources; }
            public void findBlockLightSources(BiConsumer<BlockPos, BlockState> emit) {
                for (var entry : sections.entrySet()) scanSources(entry.getKey(), entry.getValue(), emit);
                if (nativeSection != null && access.isLoaded(nativeSection.origin())) scanSources(nativeSection, null, emit);
            }
        }; }
        void scanSources(AllvrSectionCoord coord, LevelChunkSection section, BiConsumer<BlockPos, BlockState> emit) {
            if (section != null && !section.maybeHas(state -> state.hasDynamicLightEmission() || state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) != 0)) return;
            var pos = new BlockPos.MutableBlockPos();
            for (int y = 0; y < 16; y++) for (int zz = 0; zz < 16; zz++) for (int xx = 0; xx < 16; xx++) {
                pos.set((coord.x() << 4) + xx, (coord.y() << 4) + y - window.origin, (coord.z() << 4) + zz);
                var state = section == null ? window.blockView.getBlockState(pos) : section.getBlockState(xx, y, zz);
                if (state.getLightEmission(window.blockView, pos) > 0) {
                    if (section == null) nativeEmitters.add(new BlockPos(pos.getX(), pos.getY() + window.origin, pos.getZ()));
                    emit.accept(pos, state);
                }
            }
        }
    }
    private final class SkySources extends ChunkSkyLightSources {
        final Column column; final int[] lowest = new int[256]; boolean dirty = true;
        SkySources(Column column) { super(column.window.skyView); this.column = column; }
        void invalidate() { dirty = true; }
        void rebuild() {
            Arrays.fill(lowest, Integer.MIN_VALUE);
            var coords = column.sections.keySet().stream().filter(c -> column.window.ownsSky(c.y() << 4)).sorted(Comparator.comparingInt(AllvrSectionCoord::y).reversed()).toList();
            var pos = new BlockPos.MutableBlockPos();
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                boolean found = false;
                for (var coord : coords) {
                    for (int y = 15; y >= 0; y--) {
                        pos.set((column.x << 4) + x, (coord.y() << 4) + y - column.window.origin, (column.z << 4) + z);
                        var current = column.window.skyView.getBlockState(pos); var abovePos = pos.above(); var above = column.window.skyView.getBlockState(abovePos);
                        if (current.getLightBlock(column.window.skyView, pos) != 0 || Shapes.faceShapeOccludes(
                            LightEngine.getOcclusionShape(column.window.skyView, abovePos, above, Direction.DOWN),
                            LightEngine.getOcclusionShape(column.window.skyView, pos, current, Direction.UP))) {
                            lowest[(z << 4) | x] = pos.getY() + 1; found = true; break;
                        }
                    }
                    if (found) break;
                }
            }
            dirty = false;
        }
        @Override public int getLowestSourceY(int x, int z) { if (dirty) rebuild(); return lowest[((z & 15) << 4) | (x & 15)]; }
        @Override public int getHighestLowestSourceY() { if (dirty) rebuild(); return Arrays.stream(lowest).max().orElse(Integer.MIN_VALUE); }
        @Override public boolean update(BlockGetter ignored, int x, int y, int z) { invalidate(); return true; }
    }
}
