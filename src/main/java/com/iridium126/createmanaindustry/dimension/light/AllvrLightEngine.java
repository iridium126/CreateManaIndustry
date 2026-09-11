package com.iridium126.createmanaindustry.dimension.light;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCoords;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;

/**
 * Vanilla light-engine bridge for the cube world.
 *
 * <p>The propagation algorithm and the section storage deliberately live in
 * {@link LevelLightEngine}. This class only supplies the two vanilla
 * adapters that cannot be obtained from an {@code AllvrCube}: a virtual
 * {@link LightChunk} for each loaded 16x16 chunk column and a
 * {@link ChunkSkyLightSources} backed by the loaded cube column.</p>
 *
 * <p>Allvr cubes are still the ownership and persistence unit. A cube maps to
 * four horizontal vanilla light columns and eight vanilla light sections.
 * Empty cube sections are registered as light-bearing sections while the cube
 * is resident so render consumers can read a stable {@link DataLayer} for
 * every streamed section.</p>
 *
 * <p>Vanilla {@code BlockPos.asLong()} stores Y in 12 bits. Consequently a
 * single vanilla engine can only cover {@value #VANILLA_WINDOW_SIZE} blocks.
 * Allay is much taller than that, so this bridge owns one vanilla engine per
 * aligned 4096-block window and translates local Y into the vanilla-safe
 * range. The public API and the cube storage remain in absolute Allay
 * coordinates; the translation is confined to the adapter boundary.</p>
 */
public final class AllvrLightEngine {

    public interface Access {
        BlockState getBlockState(BlockPos pos);

        boolean isLoaded(BlockPos pos);

        default net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }
    }

    /** Signed 12-bit Y range used by vanilla's packed block-light queues. */
    public static final int VANILLA_MIN_Y = -(1 << 11);
    public static final int VANILLA_MAX_Y = (1 << 11) - 1;
    public static final int VANILLA_WINDOW_SIZE = VANILLA_MAX_Y - VANILLA_MIN_Y + 1;

    private final Access access;
    private final Object lock = new Object();
    private final Long2ObjectOpenHashMap<AllvrCube> loadedCubes = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<EngineShard> shards = new Int2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirtyCubes = new LongOpenHashSet();

    public AllvrLightEngine(Level level, Access access) {
        this.access = access;
    }

    /** Publishes or replaces one cube in vanilla's light section storage. */
    public void onCubeLoaded(AllvrCube cube) {
        synchronized (lock) {
            long key = cube.getPos().asLong();
            AllvrCube old = loadedCubes.remove(key);
            if (old != null) {
                unregisterCube(old);
            }

            loadedCubes.put(key, cube);
            EngineShard shard = shardForCube(cube, true);
            shard.registerCube(cube);

            DataLayer[] restoredSky = cube.takeRestoredSkyLight();
            DataLayer[] restoredBlock = cube.takeRestoredBlockLight();
            forEachSection(cube, (section, index) -> {
                SectionPos localSection = shard.toLocalSection(section);
                // A resident Allvr section is a real light-storage section,
                // even when its block palette is all air. This keeps the
                // section-data contract deterministic for Sodium/Voxy.
                shard.vanilla.updateSectionStatus(localSection, false);
                if (restoredSky != null) {
                    shard.vanilla.queueSectionData(LightLayer.SKY, localSection, restoredSky[index]);
                }
                if (restoredBlock != null) {
                    shard.vanilla.queueSectionData(LightLayer.BLOCK, localSection, restoredBlock[index]);
                }
            });

            // This is the vanilla chunk-load source pass, limited to the four
            // virtual chunk columns represented by this cube. It discovers
            // sources from BlockState values; neighbouring columns are not
            // replayed here.
            forEachChunkColumn(cube, shard.vanilla::propagateLightSources);
            markCubeDirty(key);
        }
    }

    /** Removes a cube and lets vanilla retract light crossing its boundary. */
    public void onCubeUnloaded(AllvrCube cube) {
        synchronized (lock) {
            long key = cube.getPos().asLong();
            if (loadedCubes.get(key) != cube) {
                return;
            }
            loadedCubes.remove(key);
            unregisterCube(cube);
            dirtyCubes.add(key);
            invalidateCube(cube.getPos());
        }
    }

    /** Queues the vanilla block and sky checks for a changed block. */
    public void onBlockChanged(BlockPos pos) {
        if (!isSupportedPosition(pos)) {
            return;
        }
        synchronized (lock) {
            if (!access.isLoaded(pos)) {
                return;
            }
            EngineShard shard = shardForBlockY(pos.getY(), false);
            if (shard != null) {
                shard.onBlockChanged(pos);
            }
        }
    }

    /**
     * Runs vanilla's complete pending propagation pass. The old custom
     * implementation exposed a node budget; LevelLightEngine intentionally
     * owns the queue and drains it atomically, so the argument is retained
     * only for the existing cube-map call sites.
     */
    public int tick(int budget) {
        if (budget <= 0) {
            return 0;
        }
        synchronized (lock) {
            int processed = 0;
            for (EngineShard shard : shards.values()) {
                processed += shard.vanilla.runLightUpdates();
            }
            return processed;
        }
    }

    public int blockLight(BlockPos pos) {
        if (!isSupportedPosition(pos)) {
            return 0;
        }
        synchronized (lock) {
            EngineShard shard = shardForBlockY(pos.getY(), false);
            return shard == null ? 0 : shard.blockLight(pos);
        }
    }

    public int skyLight(BlockPos pos) {
        if (!isSupportedPosition(pos)) {
            return 0;
        }
        synchronized (lock) {
            EngineShard shard = shardForBlockY(pos.getY(), false);
            return shard == null ? 0 : shard.skyLight(pos);
        }
    }

    /**
     * Returns detached copies in the historical order {@code [sky, block]}.
     * Vanilla's visible section map is immutable between propagation passes;
     * copying here also prevents Sodium/Voxy from mutating engine-owned data.
     */
    public DataLayer[] sectionData(SectionPos section) {
        synchronized (lock) {
            EngineShard shard = shardForSectionY(section.getY(), false);
            if (shard == null) return emptySectionData();
            SectionPos localSection = shard.toLocalSection(section);
            return new DataLayer[] {
                copyOrEmpty(shard.vanilla.getLayerListener(LightLayer.SKY).getDataLayerData(localSection)),
                copyOrEmpty(shard.vanilla.getLayerListener(LightLayer.BLOCK).getDataLayerData(localSection))
            };
        }
    }

    public DataLayer[] copySectionData(SectionPos section) {
        return sectionData(section);
    }

    /** Kept as a compatibility hook for the render adapters; vanilla has no local cache here. */
    public void invalidateAllCachedSections() {
    }

    /** Kept as a compatibility hook for the render adapters; callbacks invalidate on publication. */
    public void invalidateCubeSections(long cubeKey) {
    }

    public boolean hasLoadedCube(long cubeKey) {
        synchronized (lock) {
            return loadedCubes.containsKey(cubeKey);
        }
    }

    /** Returns and clears cubes whose published light data changed. */
    public LongOpenHashSet drainDirtyCubes() {
        synchronized (lock) {
            LongOpenHashSet result = new LongOpenHashSet(dirtyCubes);
            dirtyCubes.clear();
            return result;
        }
    }

    /** Unregisters all virtual chunks; called when the owning cube map closes. */
    public void clear() {
        synchronized (lock) {
            for (AllvrCube cube : new ArrayList<>(loadedCubes.values())) unregisterCube(cube);
            loadedCubes.clear();
            for (EngineShard shard : shards.values()) shard.vanilla.runLightUpdates();
            shards.clear();
            dirtyCubes.clear();
        }
    }

    private void registerCube(AllvrCube cube) {
        shardForCube(cube, true).registerCube(cube);
    }

    private void unregisterCube(AllvrCube cube) {
        EngineShard shard = shardForCube(cube, false);
        if (shard == null) return;
        shard.unregisterCube(cube);
        if (shard.cubeReferences == 0) {
            shards.remove(shard.windowIndex);
        }
    }

    private void invalidateCube(AllvrCubePos cubePos) {
        for (EngineShard shard : shards.values()) {
            shard.invalidateCube(cubePos);
        }
    }

    private EngineShard shardForCube(AllvrCube cube, boolean create) {
        return shardForBlockY(cube.getPos().minBlockY(), create);
    }

    private EngineShard shardForBlockY(int worldY, boolean create) {
        int windowIndex = windowIndex(worldY);
        EngineShard shard = shards.get(windowIndex);
        if (shard == null && create) {
            shard = new EngineShard(windowIndex);
            shards.put(windowIndex, shard);
        }
        return shard;
    }

    private EngineShard shardForSectionY(int worldSectionY, boolean create) {
        return shardForBlockY(SectionPos.sectionToBlockCoord(worldSectionY), create);
    }

    static int windowIndex(int worldY) {
        return Math.floorDiv(worldY, VANILLA_WINDOW_SIZE);
    }

    static int windowOriginY(int windowIndex) {
        return windowIndex * VANILLA_WINDOW_SIZE + (VANILLA_WINDOW_SIZE / 2);
    }

    private final class EngineShard {
        private final int windowIndex;
        private final int originY;
        private final int originSectionY;
        private final LightLevelView lightLevelView;
        private final LightChunkGetter chunkSource;
        private final LevelLightEngine vanilla;
        private final Long2IntOpenHashMap columnReferences = new Long2IntOpenHashMap();
        private final Long2ObjectOpenHashMap<AllvrLightChunk> lightChunks = new Long2ObjectOpenHashMap<>();
        private int cubeReferences;

        private EngineShard(int windowIndex) {
            this.windowIndex = windowIndex;
            this.originY = windowOriginY(windowIndex);
            this.originSectionY = originY >> 4;
            this.lightLevelView = new LightLevelView(this);
            this.chunkSource = new CubeLightChunkGetter(this);
            this.vanilla = new LevelLightEngine(this.chunkSource, true, true);
            this.columnReferences.defaultReturnValue(0);
        }

        private BlockPos toLocal(BlockPos worldPos) {
            return new BlockPos(worldPos.getX(), worldPos.getY() - originY, worldPos.getZ());
        }

        private BlockPos toWorld(BlockPos localPos) {
            return new BlockPos(localPos.getX(), localPos.getY() + originY, localPos.getZ());
        }

        private SectionPos toLocalSection(SectionPos worldSection) {
            return SectionPos.of(worldSection.getX(), worldSection.getY() - originSectionY,
                worldSection.getZ());
        }

        private SectionPos toWorldSection(SectionPos localSection) {
            return SectionPos.of(localSection.getX(), localSection.getY() + originSectionY,
                localSection.getZ());
        }

        private boolean ownsCube(AllvrCube cube) {
            return windowIndex(cube.getPos().minBlockY()) == windowIndex;
        }

        private void registerCube(AllvrCube cube) {
            if (!ownsCube(cube)) {
                throw new IllegalArgumentException("cube crosses light window: " + cube.getPos());
            }
            cubeReferences++;
            forEachChunkColumn(cube, chunk -> {
                long key = ChunkPos.asLong(chunk.x, chunk.z);
                columnReferences.addTo(key, 1);
                AllvrLightChunk lightChunk = lightChunks.computeIfAbsent(key,
                    ignored -> new AllvrLightChunk(this, chunk.x, chunk.z));
                lightChunk.cubeColumns.put(cube.getPos().asLong(), cube);
                lightChunk.skySources.invalidate();
            });
        }

        private void unregisterCube(AllvrCube cube) {
            forEachChunkColumn(cube, chunk -> {
                long key = ChunkPos.asLong(chunk.x, chunk.z);
                int references = columnReferences.get(key);
                if (references <= 1) {
                    vanilla.setLightEnabled(chunk, false);
                    columnReferences.remove(key);
                    lightChunks.remove(key);
                } else {
                    columnReferences.put(key, references - 1);
                    AllvrLightChunk lightChunk = lightChunks.get(key);
                    if (lightChunk != null) {
                        lightChunk.cubeColumns.remove(cube.getPos().asLong());
                        lightChunk.skySources.invalidate();
                    }
                }
            });
            forEachSection(cube, (section, ignored) -> {
                SectionPos localSection = toLocalSection(section);
                vanilla.queueSectionData(LightLayer.SKY, localSection, null);
                vanilla.queueSectionData(LightLayer.BLOCK, localSection, null);
                vanilla.updateSectionStatus(localSection, true);
            });
            cubeReferences--;
        }

        private void onBlockChanged(BlockPos worldPos) {
            BlockPos localPos = toLocal(worldPos);
            AllvrLightChunk column = lightChunks.get(ChunkPos.asLong(
                SectionPos.blockToSectionCoord(localPos.getX()), SectionPos.blockToSectionCoord(localPos.getZ())));
            if (column != null) column.skySources.invalidate();
            vanilla.checkBlock(localPos);
        }

        private int blockLight(BlockPos worldPos) {
            return vanilla.getLayerListener(LightLayer.BLOCK).getLightValue(toLocal(worldPos));
        }

        private int skyLight(BlockPos worldPos) {
            return vanilla.getLayerListener(LightLayer.SKY).getLightValue(toLocal(worldPos));
        }

        private void invalidateCube(AllvrCubePos cubePos) {
            for (int chunkZ = 0; chunkZ < 2; chunkZ++) {
                for (int chunkX = 0; chunkX < 2; chunkX++) {
                    int x = (cubePos.getX() << 1) + chunkX;
                    int z = (cubePos.getZ() << 1) + chunkZ;
                    AllvrLightChunk lightChunk = lightChunks.get(ChunkPos.asLong(x, z));
                    if (lightChunk != null) lightChunk.skySources.invalidate();
                }
            }
        }
    }

    private void markCubeDirty(long key) {
        dirtyCubes.add(key);
        AllvrCube cube = loadedCubes.get(key);
        if (cube != null) {
            cube.markLightDirty();
        }
    }

    private void markSectionDirty(SectionPos section) {
        markCubeDirty(AllvrCubePos.asLong(section.getX() >> 1,
            section.getY() >> 1, section.getZ() >> 1));
    }

    private static boolean isSupportedPosition(BlockPos pos) {
        return AllvrDimensionLimits.isInBounds(pos);
    }

    private static boolean isSupportedLocalSection(SectionPos section) {
        // Keep one padding section available on each side for the vanilla
        // sky engine. The actual cube sections are always inside this range.
        return section.getY() >= -129 && section.getY() <= 128;
    }

    private static DataLayer copyOrEmpty(@Nullable DataLayer data) {
        return data == null ? new DataLayer() : data.copy();
    }

    private static DataLayer[] emptySectionData() {
        return new DataLayer[] { new DataLayer(), new DataLayer() };
    }

    private interface SectionConsumer {
        void accept(SectionPos section, int index);
    }

    private static void forEachSection(AllvrCube cube, SectionConsumer consumer) {
        AllvrCubePos pos = cube.getPos();
        for (int sy = 0; sy < 2; sy++) {
            for (int sz = 0; sz < 2; sz++) {
                for (int sx = 0; sx < 2; sx++) {
                    SectionPos section = SectionPos.of(
                        AllvrCoords.cubeToSection(pos.getX(), sx),
                        AllvrCoords.cubeToSection(pos.getY(), sy),
                        AllvrCoords.cubeToSection(pos.getZ(), sz));
                    consumer.accept(section, AllvrCube.sliceIndex(sx, sy, sz));
                }
            }
        }
    }

    private interface ChunkConsumer {
        void accept(ChunkPos chunk);
    }

    private static void forEachChunkColumn(AllvrCube cube, ChunkConsumer consumer) {
        AllvrCubePos pos = cube.getPos();
        for (int z = 0; z < 2; z++) {
            for (int x = 0; x < 2; x++) {
                consumer.accept(new ChunkPos((pos.getX() << 1) + x, (pos.getZ() << 1) + z));
            }
        }
    }

    private final class CubeLightChunkGetter implements LightChunkGetter {
        private final EngineShard owner;

        private CubeLightChunkGetter(EngineShard owner) {
            this.owner = owner;
        }

        @Override
        @Nullable
        public LightChunk getChunkForLighting(int chunkX, int chunkZ) {
            return owner.lightChunks.get(ChunkPos.asLong(chunkX, chunkZ));
        }

        @Override
        public void onLightUpdate(LightLayer layer, SectionPos section) {
            synchronized (lock) {
                markSectionDirty(owner.toWorldSection(section));
            }
        }

        @Override
        public BlockGetter getLevel() {
            return owner.lightLevelView;
        }
    }

    private final class LightLevelView implements BlockGetter {
        private final EngineShard owner;

        private LightLevelView(EngineShard owner) {
            this.owner = owner;
        }

        @Override
        @Nullable
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos localPos) {
            BlockPos worldPos = owner.toWorld(localPos);
            return AllvrDimensionLimits.isInBounds(worldPos) && access.isLoaded(worldPos)
                ? access.getBlockEntity(worldPos) : null;
        }

        @Override
        public BlockState getBlockState(BlockPos localPos) {
            BlockPos worldPos = owner.toWorld(localPos);
            return AllvrDimensionLimits.isInBounds(worldPos) && access.isLoaded(worldPos)
                ? access.getBlockState(worldPos)
                : Blocks.VOID_AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getMinBuildHeight() {
            return VANILLA_MIN_Y;
        }

        @Override
        public int getHeight() {
            return VANILLA_WINDOW_SIZE;
        }
    }

    private final class AllvrLightChunk implements LightChunk {
        private final EngineShard owner;
        private final int chunkX;
        private final int chunkZ;
        /** Cubes in this exact horizontal 16x16 column, indexed by cube key. */
        private final Long2ObjectOpenHashMap<AllvrCube> cubeColumns = new Long2ObjectOpenHashMap<>();
        private final AllvrSkyLightSources skySources;

        private AllvrLightChunk(EngineShard owner, int chunkX, int chunkZ) {
            this.owner = owner;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.skySources = new AllvrSkyLightSources(this);
        }

        @Override
        public void findBlockLightSources(BiConsumer<BlockPos, BlockState> output) {
            int cubeX = chunkX >> 1;
            int cubeZ = chunkZ >> 1;
            for (AllvrCube cube : cubeColumns.values()) {
                AllvrCubePos cubePos = cube.getPos();
                if (!owner.ownsCube(cube) || cubePos.getX() != cubeX || cubePos.getZ() != cubeZ) {
                    continue;
                }
                int baseX = cubePos.minBlockX();
                int baseY = cubePos.minBlockY();
                int baseZ = cubePos.minBlockZ();
                int localChunkX = chunkX - (cubeX << 1);
                int localChunkZ = chunkZ - (cubeZ << 1);
                for (int localZ = localChunkZ * 16; localZ < localChunkZ * 16 + 16; localZ++) {
                    for (int localX = localChunkX * 16; localX < localChunkX * 16 + 16; localX++) {
                        for (int localY = 0; localY < AllvrCoords.DIAMETER_IN_BLOCKS; localY++) {
                            BlockPos localPos = new BlockPos(
                                baseX + localX, baseY + localY - owner.originY, baseZ + localZ);
                            BlockState state = getBlockState(localPos);
                            if (state.getLightEmission() > 0) {
                                output.accept(localPos, state);
                            }
                        }
                    }
                }
            }
        }

        @Override
        public ChunkSkyLightSources getSkyLightSources() {
            return skySources;
        }

        @Override
        public int getMinBuildHeight() {
            return owner.lightLevelView.getMinBuildHeight();
        }

        @Override
        public int getHeight() {
            return owner.lightLevelView.getHeight();
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return owner.lightLevelView.getBlockState(pos);
        }

        @Override
        @Nullable
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return owner.lightLevelView.getBlockEntity(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return owner.lightLevelView.getFluidState(pos);
        }
    }

    /** Vanilla sky source table semantics over the cubes in one light window. */
    private final class AllvrSkyLightSources extends ChunkSkyLightSources {
        private final EngineShard owner;
        private final int[] lowestSources = new int[16 * 16];
        private final int chunkX;
        private final int chunkZ;
        private final AllvrLightChunk column;
        private boolean dirty = true;

        private AllvrSkyLightSources(AllvrLightChunk column) {
            super(column.owner.lightLevelView);
            this.owner = column.owner;
            this.chunkX = column.chunkX;
            this.chunkZ = column.chunkZ;
            this.column = column;
        }

        private void invalidate() {
            dirty = true;
        }

        private void ensureFresh() {
            if (dirty) rebuild();
        }

        private void rebuild() {
            for (int i = 0; i < lowestSources.length; i++) {
                lowestSources[i] = NEGATIVE_INFINITY;
            }
            int cubeX = chunkX >> 1;
            int cubeZ = chunkZ >> 1;
            List<AllvrCube> cubes = new ArrayList<>();
            for (AllvrCube cube : column.cubeColumns.values()) {
                AllvrCubePos pos = cube.getPos();
                if (owner.ownsCube(cube) && pos.getX() == cubeX && pos.getZ() == cubeZ) {
                    cubes.add(cube);
                }
            }
            cubes.sort(Comparator.comparingInt((AllvrCube cube) -> cube.getPos().getY()).reversed());

            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int highest = NEGATIVE_INFINITY;
                    for (AllvrCube cube : cubes) {
                        AllvrCubePos cubePos = cube.getPos();
                        int baseX = cubePos.minBlockX();
                        int localBaseY = cubePos.minBlockY() - owner.originY;
                        int baseZ = cubePos.minBlockZ();
                        if (localBaseY > VANILLA_MAX_Y || localBaseY + 31 < VANILLA_MIN_Y) {
                            continue;
                        }
                        int cubeLocalX = (chunkX - (cubePos.getX() << 1)) * 16 + localX;
                        int cubeLocalZ = (chunkZ - (cubePos.getZ() << 1)) * 16 + localZ;
                        BlockPos.MutableBlockPos abovePos = new BlockPos.MutableBlockPos(
                            baseX + cubeLocalX, localBaseY + 32, baseZ + cubeLocalZ);
                        BlockState above = owner.lightLevelView.getBlockState(abovePos);
                        for (int localY = 31; localY >= 0; localY--) {
                            BlockPos.MutableBlockPos currentPos = new BlockPos.MutableBlockPos(
                                baseX + cubeLocalX, localBaseY + localY, baseZ + cubeLocalZ);
                            BlockState current = owner.lightLevelView.getBlockState(currentPos);
                            if (isEdgeOccluded(abovePos, above, currentPos, current)) {
                                highest = Math.max(highest, currentPos.getY() + 1);
                                break;
                            }
                            abovePos.set(currentPos);
                            above = current;
                        }
                    }
                    lowestSources[(localZ << 4) | localX] = highest;
                }
            }
            dirty = false;
        }

        private boolean isEdgeOccluded(BlockPos abovePos, BlockState above,
                                       BlockPos currentPos, BlockState current) {
            if (current.getLightBlock(owner.lightLevelView, currentPos) != 0) return true;
            return Shapes.faceShapeOccludes(
                LightEngine.getOcclusionShape(owner.lightLevelView, abovePos, above, Direction.DOWN),
                LightEngine.getOcclusionShape(owner.lightLevelView, currentPos, current, Direction.UP));
        }

        @Override
        public int getLowestSourceY(int x, int z) {
            ensureFresh();
            return lowestSources[(z & 15) << 4 | (x & 15)];
        }

        @Override
        public int getHighestLowestSourceY() {
            ensureFresh();
            int highest = NEGATIVE_INFINITY;
            for (int source : lowestSources) highest = Math.max(highest, source);
            return highest;
        }

        @Override
        public boolean update(BlockGetter ignored, int x, int y, int z) {
            dirty = true;
            return true;
        }
    }
}
