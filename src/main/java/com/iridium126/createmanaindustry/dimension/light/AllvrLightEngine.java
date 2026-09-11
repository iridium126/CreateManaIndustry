package com.iridium126.createmanaindustry.dimension.light;

import java.util.ArrayDeque;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Sparse light engine for the cube world.
 *
 * <p>Allay cannot use {@code LevelLightEngine}: the vanilla engine addresses
 * column chunks and its sky source table is bounded by the normal world
 * height.  This engine keeps the same two queues and attenuation rules as
 * vanilla's block and sky engines, but stores only loaded block positions and
 * publishes immutable {@link DataLayer} snapshots per section.  Sky sources
 * are reset at each cube's upper boundary: that is the required local-sky
 * model for stacked islands, where a world-height column source would let one
 * upper island black out every island below it.</p>
 *
 * <p>The engine is deliberately independent of rendering. Cube publication
 * and block writes enqueue work on the owning game thread; Sodium/Voxy only
 * read the last completed section snapshot. A bounded {@link #tick(int)}
 * budget keeps a streamed cube from making the render thread walk its blocks.</p>
 */
public final class AllvrLightEngine {

    public interface Access {
        BlockState getBlockState(BlockPos pos);

        boolean isLoaded(BlockPos pos);
    }

    private record Node(long pos, int level) {}

    private static final int ALL_DIRECTIONS = (1 << Direction.values().length) - 1;

    private final Level level;
    private final Access access;
    private final Object lock = new Object();
    private final Long2ByteOpenHashMap block = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap sky = new Long2ByteOpenHashMap();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> blockKeysByCube =
        new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> skyKeysByCube =
        new Long2ObjectOpenHashMap<>();
    /** Local sky sources owned by each loaded cube. */
    private final Long2ObjectOpenHashMap<LongOpenHashSet> skySourcesByCube =
        new Long2ObjectOpenHashMap<>();
    /** Source position → all loaded cubes that contribute that source.  Two
     * adjacent cubes can legitimately share their boundary source cell. */
    private final Long2ObjectOpenHashMap<LongOpenHashSet> skySourceOwners =
        new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet skySourceKeys = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<AllvrCube> loadedCubes = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<Node> blockIncrease = new ArrayDeque<>();
    private final ArrayDeque<Node> blockDecrease = new ArrayDeque<>();
    private final ArrayDeque<Node> skyIncrease = new ArrayDeque<>();
    private final ArrayDeque<Node> skyDecrease = new ArrayDeque<>();
    /**
     * A cube can be reseeded by a packet, a border update, and a block edit
     * in the same tick.  Vanilla's light engine has a queued bit for each
     * node; an ArrayDeque alone keeps every duplicate.  That made the queue
     * grow without bound and, once the budget was exhausted, light appeared
     * to travel only one cell.  Keep the newest requested level per queue
     * entry so each position has at most one live increase/decrease node.
     */
    private final Long2ByteOpenHashMap blockIncreasePending = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap blockDecreasePending = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap skyIncreasePending = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap skyDecreasePending = new Long2ByteOpenHashMap();
    /** Vanilla QueueEntry's six directional bits, kept separately from the level. */
    private final Long2ByteOpenHashMap blockIncreaseDirections = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap blockDecreaseDirections = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap skyIncreaseDirections = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap skyDecreaseDirections = new Long2ByteOpenHashMap();
    private final Long2ObjectLinkedOpenHashMap<DataLayer[]> sectionCache =
        new Long2ObjectLinkedOpenHashMap<>();
    private final LongOpenHashSet dirtyCubes = new LongOpenHashSet();
    private static final int SECTION_CACHE_LIMIT = 4096;
    public AllvrLightEngine(Level level, Access access) {
        this.level = level;
        this.access = access;
        this.block.defaultReturnValue((byte) 0);
        this.sky.defaultReturnValue((byte) 0);
    }

    /** Publishes or replaces one cube and reseeds its one-cube light border. */
    public void onCubeLoaded(AllvrCube cube) {
        synchronized (lock) {
            long key = cube.getPos().asLong();
            boolean replacing = loadedCubes.containsKey(key);
            loadedCubes.put(key, cube);
            if (level.isClientSide) {
                if (cube.skyTopOpaque() == null) {
                    cube.prepareSkyTopOpaque();
                }
            } else {
                // The server has the BlockGetter needed by vanilla's
                // getLightBlock/face-occlusion sky source scan.
                cube.prepareSkyTopOpaque(level);
            }
            if (level.isClientSide && cube.skyLightLayer(0) == null) {
                cube.prepareSkyLightLayers();
            }
            DataLayer[] restoredSky = cube.takeRestoredSkyLight();
            DataLayer[] restoredBlock = cube.takeRestoredBlockLight();
            invalidateCubeSections(key);
            dirtyCubes.add(key);
            if (replacing) {
                clearRegion(cube.getPos(), true);
            }
            if (replacing) {
                // A replacement invalidates the one-cube light border, so
                // rebuild emitters from the neighbouring cubes.  A first
                // publication has no stale border and only needs its own
                // emitters; scanning 27 cubes here was the main-thread stall.
                reseedAround(cube.getPos());
            } else {
                if (restoredSky != null || restoredBlock != null) {
                    restoreCubeLight(cube, restoredSky, restoredBlock);
                }
                reseedCube(cube);
                seedSkyColumns(cube);
            }
            if (!replacing) {
                reseedBoundary(cube);
            }
        }
    }

    /** Removes a cube and retracts light that could have crossed its border. */
    public void onCubeUnloaded(AllvrCube cube) {
        synchronized (lock) {
            loadedCubes.remove(cube.getPos().asLong());
            dirtyCubes.add(cube.getPos().asLong());
            invalidateCubeSections(cube.getPos().asLong());
            clearRegion(cube.getPos(), true);
            reseedAround(cube.getPos());
        }
    }

    /** Recomputes both layers around a changed block using vanilla queues. */
    public void onBlockChanged(BlockPos pos) {
        synchronized (lock) {
            long key = pos.asLong();
            remove(block, blockKeysByCube, blockDecrease, key);
            remove(sky, skyKeysByCube, skyDecrease, key);
            seedBlock(pos);
            AllvrCubePos cubePos = AllvrCubePos.of(pos);
            AllvrCube cube = loadedCubes.get(cubePos.asLong());
            if (cube != null) {
                cube.refreshSkyTopOpaque(pos.getX() & 31, pos.getZ() & 31);
                // Direct-sky layers are copy-on-write, but the section cache
                // holds the previous immutable snapshot until explicitly
                // invalidated. Vanilla's section storage publishes the new
                // layer in the same block check; mirror that publication here.
                invalidateCubeSections(cubePos.asLong());
            }
            reseedSkyColumnWindow(cubePos, pos.getX() & 31, pos.getZ() & 31);
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pos.relative(direction);
                enqueueIfLit(block, blockIncrease, neighbour.asLong());
                enqueueIfLit(sky, skyIncrease, neighbour.asLong());
            }
        }
    }

    /** Processes a bounded amount of increase/decrease work on the owner thread. */
    public int tick(int budget) {
        if (budget <= 0) {
            return 0;
        }
        synchronized (lock) {
            // Vanilla keeps block and sky engines independent.  A single
            // combined queue lets a large open-sky fan-out consume the whole
            // tick and leaves newly placed emitters at level zero for many
            // seconds.  Reserve half the work for block light, then lend any
            // unused slice back to the other layer.
            if (level.isClientSide) {
                // The tick is owned by the client game thread; keep both
                // layers incremental so caves receive propagated sky light
                // without making Sodium's render thread perform a BFS.
                int blockQuota = budget / 2;
                int skyQuota = budget - blockQuota;
                int processed = processLayer(block, blockDecrease, blockIncrease, false, blockQuota);
                processed += processLayer(sky, skyDecrease, skyIncrease, true, skyQuota);
                int remaining = budget - processed;
                if (remaining > 0) {
                    processed += processLayer(block, blockDecrease, blockIncrease, false, remaining);
                    remaining = budget - processed;
                }
                if (remaining > 0) {
                    processed += processLayer(sky, skyDecrease, skyIncrease, true, remaining);
                }
                return processed;
            }
            int blockQuota = budget / 2;
            int skyQuota = budget - blockQuota;
            int processed = processLayer(block, blockDecrease, blockIncrease, false, blockQuota);
            processed += processLayer(sky, skyDecrease, skyIncrease, true, skyQuota);
            int remaining = budget - processed;
            if (remaining > 0) {
                int extra = processLayer(block, blockDecrease, blockIncrease, false, remaining);
                processed += extra;
                remaining -= extra;
            }
            if (remaining > 0) {
                processed += processLayer(sky, skyDecrease, skyIncrease, true, remaining);
            }
            return processed;
        }
    }

    private int processLayer(Long2ByteOpenHashMap levels, ArrayDeque<Node> decrease,
                             ArrayDeque<Node> increase, boolean skyLayer, int quota) {
        int processed = 0;
        while (processed < quota && !decrease.isEmpty()) {
            processDecrease(levels, decrease, increase);
            processed++;
        }
        while (processed < quota && !increase.isEmpty()) {
            processIncrease(levels, increase, skyLayer);
            processed++;
        }
        return processed;
    }

    public int blockLight(BlockPos pos) {
        synchronized (lock) {
            return block.get(pos.asLong()) & 0xFF;
        }
    }

    public int skyLight(BlockPos pos) {
        synchronized (lock) {
            int value = sky.get(pos.asLong()) & 0xFF;
            AllvrCube cube = loadedCubes.get(AllvrCubePos.asLong(pos));
            if (cube == null) {
                // Client cache misses are void air, so they are exposed to
                // the sky. This also supplies light to the air section above
                // a top-of-cube island boundary.
                return level.isClientSide ? 15 : value;
            }
            int[] tops = cube == null ? null : cube.skyTopOpaque();
            if (tops == null) {
                return value;
            }
            int top = tops[((pos.getZ() & 31) << 5) | (pos.getX() & 31)];
            if ((pos.getY() & 31) > top) {
                return 15;
            }
            return value;
        }
    }

    public DataLayer[] sectionData(SectionPos section) {
        long key = SectionPos.asLong(section.getX(), section.getY(), section.getZ());
        AllvrCube cube = null;
        int[] topMask = null;
        DataLayer precomputedSky = null;
        byte[] skyValues;
        byte[] blockValues;
        boolean anyBlockLight = false;
        boolean dynamicSky = false;
        synchronized (lock) {
            DataLayer[] cached = sectionCache.getAndMoveToLast(key);
            if (cached != null) {
                return cached;
            }
            int minX = section.minBlockX();
            int minY = section.minBlockY();
            int minZ = section.minBlockZ();
            long cubeKey = AllvrCubePos.asLong(section.getX() >> 1,
                section.getY() >> 1, section.getZ() >> 1);
            cube = loadedCubes.get(cubeKey);
            topMask = cube == null ? null : cube.skyTopOpaque();
            if (level.isClientSide) {
                precomputedSky = cube == null ? null : cube.skyLightLayer(
                    AllvrCube.sliceIndex(section.getX() & 1, section.getY() & 1, section.getZ() & 1));
            }
            // Client sections normally use the immutable cube sky layer and
            // have no block emitters. Avoid allocating two 4 KiB scratch
            // arrays for that hot render path.
            skyValues = !level.isClientSide || (precomputedSky == null && topMask != null)
                ? new byte[16 * 16 * 16] : null;
            blockValues = !level.isClientSide ? new byte[16 * 16 * 16] : null;
            if (level.isClientSide) {
                LongOpenHashSet skyKeys = skyKeysByCube.get(cubeKey);
                dynamicSky = skyKeys != null && !skyKeys.isEmpty();
                if (dynamicSky) {
                    skyValues = new byte[16 * 16 * 16];
                    LongIterator iterator = skyKeys.iterator();
                    while (iterator.hasNext()) {
                        long pos = iterator.nextLong();
                        int x = BlockPos.getX(pos) - minX;
                        int y = BlockPos.getY(pos) - minY;
                        int z = BlockPos.getZ(pos) - minZ;
                        if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
                            byte value = sky.get(pos);
                            skyValues[(y << 8) | (z << 4) | x] = value;
                        }
                    }
                }
                LongOpenHashSet blockKeys = blockKeysByCube.get(cubeKey);
                if (blockKeys != null && !blockKeys.isEmpty()) {
                    blockValues = new byte[16 * 16 * 16];
                    LongIterator iterator = blockKeys.iterator();
                    while (iterator.hasNext()) {
                        long pos = iterator.nextLong();
                        int x = BlockPos.getX(pos) - minX;
                        int y = BlockPos.getY(pos) - minY;
                        int z = BlockPos.getZ(pos) - minZ;
                        if (x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16) {
                            byte value = block.get(pos);
                            blockValues[(y << 8) | (z << 4) | x] = value;
                            anyBlockLight |= value != 0;
                        }
                    }
                }
            } else {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int index = (y << 8) | (z << 4) | x;
                            long pos = BlockPos.asLong(minX + x, minY + y, minZ + z);
                            skyValues[index] = sky.get(pos);
                            blockValues[index] = block.get(pos);
                            anyBlockLight |= blockValues[index] != 0;
                        }
                    }
                }
            }
        }
        boolean fullSky = level.isClientSide && cube == null;
        boolean usePrecomputedSky = precomputedSky != null && !dynamicSky;
        if (!usePrecomputedSky && topMask != null) {
            int sectionLocalY = (section.getY() & 1) << 4;
            fullSky = true;
            for (int z = 0; z < 16 && fullSky; z++) {
                for (int x = 0; x < 16; x++) {
                    int top = topMask[((z + ((section.getZ() & 1) << 4)) << 5)
                        | (x + ((section.getX() & 1) << 4))];
                    if (sectionLocalY <= top) {
                        fullSky = false;
                        break;
                    }
                }
            }
            if (!fullSky) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int top = topMask[((z + ((section.getZ() & 1) << 4)) << 5)
                            | (x + ((section.getX() & 1) << 4))];
                        for (int y = 0; y < 16; y++) {
                            if (sectionLocalY + y > top) {
                                skyValues[(y << 8) | (z << 4) | x] = 15;
                            }
                        }
                    }
                }
            }
        }
        DataLayer skyLayer = usePrecomputedSky
            ? precomputedSky : new DataLayer(fullSky ? 15 : 0);
        DataLayer blockLayer = new DataLayer(0);
        if (!usePrecomputedSky && !fullSky) {
            if (skyValues == null) {
                skyValues = new byte[16 * 16 * 16];
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int index = (y << 8) | (z << 4) | x;
                        int skyLevel = skyValues[index] & 0xFF;
                        if (skyLevel != 0) skyLayer.set(x, y, z, skyLevel);
                    }
                }
            }
        }
        if (anyBlockLight) {
            if (blockValues == null) {
                blockValues = new byte[16 * 16 * 16];
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int blockLevel = blockValues[(y << 8) | (z << 4) | x] & 0xFF;
                        if (blockLevel != 0) blockLayer.set(x, y, z, blockLevel);
                    }
                }
            }
        }
        DataLayer[] result = new DataLayer[] { skyLayer, blockLayer };
        synchronized (lock) {
            DataLayer[] existing = sectionCache.getAndMoveToLast(key);
            if (existing != null) return existing;
            if (sectionCache.size() >= SECTION_CACHE_LIMIT) sectionCache.removeFirst();
            sectionCache.putAndMoveToLast(key, result);
        }
        return result;
    }

    /**
     * Returns detached copies of the two vanilla-format layers for persistence.
     * The copies are essential: the snapshot can outlive this engine tick and
     * must never expose the mutable cached layer to the IO worker.
     */
    public DataLayer[] copySectionData(SectionPos section) {
        synchronized (lock) {
            DataLayer[] data = sectionData(section);
            return new DataLayer[] { data[0].copy(), data[1].copy() };
        }
    }

    /** Drops immutable section snapshots after a render-window change. */
    public void invalidateAllCachedSections() {
        synchronized (lock) {
            sectionCache.clear();
        }
    }

    /** Invalidates only the eight sections belonging to one cube. */
    public void invalidateCubeSections(long cubeKey) {
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        synchronized (lock) {
            for (int sy = 0; sy < 2; sy++) {
                for (int sz = 0; sz < 2; sz++) {
                    for (int sx = 0; sx < 2; sx++) {
                        sectionCache.remove(SectionPos.asLong((cube.getX() << 1) + sx,
                            (cube.getY() << 1) + sy, (cube.getZ() << 1) + sz));
                    }
                }
            }
        }
    }

    /** Returns cubes whose published light layers changed since the last drain. */
    public long[] drainDirtyCubes() {
        synchronized (lock) {
            long[] result = dirtyCubes.toLongArray();
            dirtyCubes.clear();
            return result;
        }
    }

    public boolean hasLoadedCube(long cubeKey) {
        synchronized (lock) {
            return loadedCubes.containsKey(cubeKey);
        }
    }

    public void clear() {
        synchronized (lock) {
            block.clear();
            sky.clear();
            blockKeysByCube.clear();
            skyKeysByCube.clear();
            skySourcesByCube.clear();
            skySourceOwners.clear();
            skySourceKeys.clear();
            loadedCubes.clear();
            blockIncrease.clear();
            blockDecrease.clear();
            skyIncrease.clear();
            skyDecrease.clear();
            blockIncreasePending.clear();
            blockDecreasePending.clear();
            skyIncreasePending.clear();
            skyDecreasePending.clear();
            blockIncreaseDirections.clear();
            blockDecreaseDirections.clear();
            skyIncreaseDirections.clear();
            skyDecreaseDirections.clear();
            sectionCache.clear();
            dirtyCubes.clear();
        }
    }

    private void reseedAround(AllvrCubePos center) {
        for (AllvrCube cube : loadedCubes.values()) {
            AllvrCubePos pos = cube.getPos();
            if (Math.abs(pos.getX() - center.getX()) <= 1
                && Math.abs(pos.getY() - center.getY()) <= 1
                && Math.abs(pos.getZ() - center.getZ()) <= 1) {
                seedEmitters(cube);
            }
        }
        reseedSkyNeighborhood(center);
    }

    private void reseedCube(AllvrCube cube) {
        seedEmitters(cube);
    }

    private void seedEmitters(AllvrCube cube) {
        for (var entry : cube.getEmitters().int2IntEntrySet()) {
            int cell = entry.getIntKey();
            BlockPos emitterPos = new BlockPos(cube.getPos().minBlockX() + (cell & 31),
                cube.getPos().minBlockY() + (cell >> 10),
                cube.getPos().minBlockZ() + ((cell >> 5) & 31));
            seed(block, blockKeysByCube, blockIncrease, emitterPos.asLong(), entry.getIntValue());
        }
    }

    /** Installs persisted sparse values without queueing or dirtying them. */
    private void restoreCubeLight(AllvrCube cube, DataLayer[] restoredSky,
                                  DataLayer[] restoredBlock) {
        for (int sectionIndex = 0; sectionIndex < AllvrCube.SECTIONS_PER_CUBE; sectionIndex++) {
            int sectionX = sectionIndex & 1;
            int sectionZ = (sectionIndex >> 1) & 1;
            int sectionY = (sectionIndex >> 2) & 1;
            int minX = cube.getPos().minBlockX() + (sectionX << 4);
            int minY = cube.getPos().minBlockY() + (sectionY << 4);
            int minZ = cube.getPos().minBlockZ() + (sectionZ << 4);
            DataLayer blockLayer = restoredBlock == null ? null : restoredBlock[sectionIndex];
            DataLayer skyLayer = restoredSky == null ? null : restoredSky[sectionIndex];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        long key = BlockPos.asLong(minX + x, minY + y, minZ + z);
                        if (blockLayer != null) {
                            int blockLevel = blockLayer.get(x, y, z);
                            if (blockLevel > 0) {
                                block.put(key, (byte) blockLevel);
                                blockKeysByCube.computeIfAbsent(cube.getPos().asLong(),
                                    ignored -> new LongOpenHashSet()).add(key);
                            }
                        }
                        if (skyLayer != null) {
                            int skyLevel = skyLayer.get(x, y, z);
                            if (skyLevel > 0 && directSkyLevel(key) < skyLevel) {
                                sky.put(key, (byte) skyLevel);
                                skyKeysByCube.computeIfAbsent(cube.getPos().asLong(),
                                    ignored -> new LongOpenHashSet()).add(key);
                            }
                        }
                    }
                }
            }
        }
    }

    private void reseedSkyNeighborhood(AllvrCubePos center) {
        // A cube is its own local sky volume.  Rebuild the full adjacent
        // neighborhood after an unload/replacement because clearRegion also
        // invalidates boundary cells in those cubes.
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    AllvrCube cube = loadedCubes.get(AllvrCubePos.asLong(
                        center.getX() + dx, center.getY() + dy, center.getZ() + dz));
                    if (cube != null) {
                        seedSkyColumns(cube);
                    }
                }
            }
        }
    }

    private void seedSkyColumns(AllvrCube cube) {
        // Empty columns are already exposed to local sky (skyLight() and the
        // client snapshot return 15 above the top mask).  Registering a
        // source for every one of the 1024 columns in an empty cube creates
        // 1024 map entries and BFS nodes for no visual benefit.
        int[] tops = cube.skyTopOpaque();
        if (tops == null) {
            return;
        }
        boolean anyOpaque = false;
        for (int top : tops) {
            if (top >= 0) {
                anyOpaque = true;
                break;
            }
        }
        for (int z = 0; z < 32; z++) {
            for (int x = 0; x < 32; x++) {
                if (anyOpaque && needsSkySource(tops, x, z)) {
                    // Keep holes and height transitions as sources: these
                    // are the vanilla sky entries that can illuminate a cave
                    // under a neighbouring island overhang. Flat interior
                    // columns use the height mask directly and need no BFS
                    // node of their own.
                    updateSkySource(cube, x, z);
                } else {
                    // A previous version may have registered this source
                    // before the column became empty.  Remove both possible
                    // source cells while preserving the sparse invariant.
                    long owner = cube.getPos().asLong();
                    int minX = cube.getPos().minBlockX();
                    int minY = cube.getPos().minBlockY();
                    int minZ = cube.getPos().minBlockZ();
                    removeSkySource(owner,
                        BlockPos.asLong(minX + x, minY + 31, minZ + z));
                    removeSkySource(owner,
                        BlockPos.asLong(minX + x, minY + 32, minZ + z));
                }
            }
        }
    }

    private static boolean needsSkySource(int[] tops, int x, int z) {
        // Cube boundaries may neighbour a column in another cube with a
        // different height, so retain that one-cell seam for cross-cube light.
        if (x == 0 || x == 31 || z == 0 || z == 31) {
            return true;
        }
        int top = tops[(z << 5) | x];
        return top != tops[(z << 5) | (x - 1)]
            || top != tops[(z << 5) | (x + 1)]
            || top != tops[((z - 1) << 5) | x]
            || top != tops[((z + 1) << 5) | x];
    }

    /** Recomputes only one X/Z column after a block edit. */
    private void reseedSkyColumnWindow(AllvrCubePos center, int localX, int localZ) {
        AllvrCube cube = loadedCubes.get(center.asLong());
        if (cube != null) {
            updateSkySource(cube, localX, localZ);
        }
    }

    private void updateSkySource(AllvrCube cube, int localX, int localZ) {
        long owner = cube.getPos().asLong();
        int minX = cube.getPos().minBlockX();
        int minY = cube.getPos().minBlockY();
        int minZ = cube.getPos().minBlockZ();
        long topKey = BlockPos.asLong(minX + localX, minY + 31, minZ + localZ);
        long outsideKey = BlockPos.asLong(minX + localX, minY + 32, minZ + localZ);
        // The source is above the edge, even when a custom block's
        // getLightBlock is zero but its face shape occludes sky (the same
        // distinction vanilla ChunkSkyLightSources makes).
        int top = cube.skyTopOpaque()[(localZ << 5) | localX];
        long sourceKey = top < 0 ? topKey : outsideKey;
        LongOpenHashSet ownedSources = skySourcesByCube.get(owner);
        if (ownedSources != null && ownedSources.contains(sourceKey)) {
            return;
        }
        // A column source can move between the top cell and the air cell just
        // above it when a top block is placed or removed.
        removeSkySource(owner, topKey);
        removeSkySource(owner, outsideKey);
        // Every stacked island gets a local sky source.  A global
        // highest-column source would make every lower island permanently
        // dark because another island is expected above it by design.
        addSkySource(owner, sourceKey, 15);
    }

    private void addSkySource(long owner, long source, int level) {
        skySourcesByCube.computeIfAbsent(owner, ignored -> new LongOpenHashSet()).add(source);
        skySourceOwners.computeIfAbsent(source, ignored -> new LongOpenHashSet()).add(owner);
        skySourceKeys.add(source);
        seed(sky, skyKeysByCube, skyIncrease, source, level);
    }

    private void removeSkySource(long cubeKey, long source) {
        LongOpenHashSet sources = skySourcesByCube.get(cubeKey);
        if (sources == null || !sources.remove(source)) {
            return;
        }
        if (sources.isEmpty()) {
            skySourcesByCube.remove(cubeKey);
        }
        LongOpenHashSet owners = skySourceOwners.get(source);
        if (owners != null) {
            owners.remove(cubeKey);
            if (owners.isEmpty()) {
                skySourceOwners.remove(source);
                skySourceKeys.remove(source);
                remove(sky, skyKeysByCube, skyDecrease, source);
            }
        }
    }

    private void reseedBoundary(AllvrCube cube) {
        int minX = cube.getPos().minBlockX();
        int minY = cube.getPos().minBlockY();
        int minZ = cube.getPos().minBlockZ();
        for (Direction direction : Direction.values()) {
            AllvrCubePos neighbour = AllvrCubePos.of(cube.getPos().getX() + direction.getStepX(),
                cube.getPos().getY() + direction.getStepY(),
                cube.getPos().getZ() + direction.getStepZ());
            if (!loadedCubes.containsKey(neighbour.asLong())) {
                continue;
            }
            if (blockKeysByCube.get(neighbour.asLong()) == null
                && skyKeysByCube.get(neighbour.asLong()) == null) {
                continue;
            }
            for (int i = 0; i < 32; i++) {
                for (int j = 0; j < 32; j++) {
                    long key = switch (direction) {
                        case NORTH -> BlockPos.asLong(minX + i, minY + j, minZ - 1);
                        case SOUTH -> BlockPos.asLong(minX + i, minY + j, minZ + 32);
                        case WEST -> BlockPos.asLong(minX - 1, minY + i, minZ + j);
                        case EAST -> BlockPos.asLong(minX + 32, minY + i, minZ + j);
                        case DOWN -> BlockPos.asLong(minX + i, minY - 1, minZ + j);
                        case UP -> BlockPos.asLong(minX + i, minY + 32, minZ + j);
                    };
                    enqueueIfLit(block, blockIncrease, key);
                    enqueueIfLit(sky, skyIncrease, key);
                }
            }
        }
    }

    private void seedBlock(BlockPos pos) {
        int emission = access.getBlockState(pos).getLightEmission(level, pos);
        if (emission > 0) {
            seed(block, blockKeysByCube, blockIncrease, pos.asLong(), emission);
        }
    }

    private void seed(Long2ByteOpenHashMap levels,
                      Long2ObjectOpenHashMap<LongOpenHashSet> index,
                      ArrayDeque<Node> queue, long pos, int value) {
        seed(levels, index, queue, pos, value, ALL_DIRECTIONS);
    }

    private void seed(Long2ByteOpenHashMap levels,
                      Long2ObjectOpenHashMap<LongOpenHashSet> index,
                      ArrayDeque<Node> queue, long pos, int value, int directions) {
        int level = Math.min(15, Math.max(0, value));
        if (levels == sky) {
            // Directly exposed air is represented by the cube height mask,
            // just like vanilla's sky light sections. Do not materialise a
            // sparse entry for every open-air voxel; it is enough to queue
            // the effective value so it can illuminate a nearby cave.
            int direct = directSkyLevel(pos);
            if (level <= direct) {
                enqueueIncrease(levels, queue, pos, Math.max(level, direct), directions);
                return;
            }
        }
        if (level > (levels.get(pos) & 0xFF)) {
            levels.put(pos, (byte) level);
            index.computeIfAbsent(cubeKey(pos), ignored -> new LongOpenHashSet()).add(pos);
            markSection(pos);
            enqueueIncrease(levels, queue, pos, level, directions);
        }
    }

    private void remove(Long2ByteOpenHashMap levels,
                        Long2ObjectOpenHashMap<LongOpenHashSet> index,
                        ArrayDeque<Node> queue, long pos) {
        cancelPending(pos, levels == sky);
        int old = levels.remove(pos) & 0xFF;
        if (old > 0) {
            removeFromIndex(index, pos);
            markSection(pos);
            enqueueDecrease(levels, queue, pos, old);
        }
    }

    private void enqueueIfLit(Long2ByteOpenHashMap levels, ArrayDeque<Node> queue, long pos) {
        int value = levels.get(pos) & 0xFF;
        if (value > 0) {
            enqueueIncrease(levels, queue, pos, value);
        }
    }

    private void enqueueIncrease(Long2ByteOpenHashMap levels, ArrayDeque<Node> queue,
                                 long pos, int level) {
        enqueueIncrease(levels, queue, pos, level, ALL_DIRECTIONS);
    }

    private void enqueueIncrease(Long2ByteOpenHashMap levels, ArrayDeque<Node> queue,
                                 long pos, int level, int directions) {
        Long2ByteOpenHashMap pending = levels == sky
            ? skyIncreasePending : blockIncreasePending;
        Long2ByteOpenHashMap pendingDirections = levels == sky
            ? skyIncreaseDirections : blockIncreaseDirections;
        int queued = pending.get(pos) & 0xFF;
        if (level > queued) {
            pending.put(pos, (byte) level);
            pendingDirections.put(pos, (byte) directions);
            queue.addLast(new Node(pos, level));
        } else if (level > 0) {
            pendingDirections.put(pos, (byte) ((pendingDirections.get(pos) & 0xFF) | directions));
        }
    }

    private void enqueueDecrease(Long2ByteOpenHashMap levels, ArrayDeque<Node> queue,
                                 long pos, int level) {
        enqueueDecrease(levels, queue, pos, level, ALL_DIRECTIONS);
    }

    private void enqueueDecrease(Long2ByteOpenHashMap levels, ArrayDeque<Node> queue,
                                 long pos, int level, int directions) {
        Long2ByteOpenHashMap pending = levels == sky
            ? skyDecreasePending : blockDecreasePending;
        Long2ByteOpenHashMap pendingDirections = levels == sky
            ? skyDecreaseDirections : blockDecreaseDirections;
        int queued = pending.get(pos) & 0xFF;
        if (level > queued) {
            pending.put(pos, (byte) level);
            pendingDirections.put(pos, (byte) directions);
            queue.addLast(new Node(pos, level));
        } else if (level > 0) {
            pendingDirections.put(pos, (byte) ((pendingDirections.get(pos) & 0xFF) | directions));
        }
    }

    private void cancelPending(long pos, boolean skyLayer) {
        (skyLayer ? skyIncreasePending : blockIncreasePending).remove(pos);
        (skyLayer ? skyDecreasePending : blockDecreasePending).remove(pos);
        (skyLayer ? skyIncreaseDirections : blockIncreaseDirections).remove(pos);
        (skyLayer ? skyDecreaseDirections : blockDecreaseDirections).remove(pos);
    }

    private void processIncrease(Long2ByteOpenHashMap levels, ArrayDeque<Node> queue, boolean skyLayer) {
        Node node = queue.removeFirst();
        Long2ByteOpenHashMap pending = skyLayer ? skyIncreasePending : blockIncreasePending;
        Long2ByteOpenHashMap pendingDirections = skyLayer ? skyIncreaseDirections : blockIncreaseDirections;
        if ((pending.get(node.pos()) & 0xFF) != node.level()) {
            return;
        }
        pending.remove(node.pos());
        int directions = pendingDirections.remove(node.pos()) & 0xFF;
        int current = levels.get(node.pos()) & 0xFF;
        if (skyLayer) {
            current = Math.max(current, directSkyLevel(node.pos()));
        }
        if (current < node.level()) {
            return;
        }
        BlockPos pos = BlockPos.of(node.pos());
        if (!access.isLoaded(pos) && !(skyLayer && skySourceKeys.contains(node.pos()))) {
            return;
        }
        for (Direction direction : Direction.values()) {
            int directionBit = 1 << direction.ordinal();
            if ((directions & directionBit) == 0) {
                continue;
            }
            BlockPos neighbour = pos.relative(direction);
            if (!access.isLoaded(neighbour)) {
                continue;
            }
            // Open-air sky is already 15 from the height mask. Traverse it
            // vertically so a shaft can reach a cave, but do not flood the
            // whole horizontal air volume with sparse queue entries.
            if (skyLayer && direction.getAxis() != Direction.Axis.Y
                && directSkyLevel(neighbour.asLong()) == 15) {
                continue;
            }
            int attenuation = attenuation(pos, neighbour, direction, skyLayer);
            int candidate = current - attenuation;
            if (candidate <= 0) {
                continue;
            }
            seed(levels, levels == sky ? skyKeysByCube : blockKeysByCube,
                queue, neighbour.asLong(), candidate, ALL_DIRECTIONS & ~(1 << direction.getOpposite().ordinal()));
        }
    }

    private void processDecrease(Long2ByteOpenHashMap levels, ArrayDeque<Node> decrease,
                                 ArrayDeque<Node> increase) {
        Node node = decrease.removeFirst();
        boolean skyLayer = levels == sky;
        Long2ByteOpenHashMap pending = skyLayer ? skyDecreasePending : blockDecreasePending;
        Long2ByteOpenHashMap pendingDirections = skyLayer ? skyDecreaseDirections : blockDecreaseDirections;
        if ((pending.get(node.pos()) & 0xFF) != node.level()) {
            return;
        }
        pending.remove(node.pos());
        int directions = pendingDirections.remove(node.pos()) & 0xFF;
        int replacement = levels.get(node.pos()) & 0xFF;
        if (skyLayer) {
            replacement = Math.max(replacement, directSkyLevel(node.pos()));
        }
        if (replacement >= node.level()) {
            return;
        }
        BlockPos pos = BlockPos.of(node.pos());
        for (Direction direction : Direction.values()) {
            int directionBit = 1 << direction.ordinal();
            if ((directions & directionBit) == 0) {
                continue;
            }
            BlockPos neighbour = pos.relative(direction);
            long key = neighbour.asLong();
            int value = levels.get(key) & 0xFF;
            if (value == 0) {
                continue;
            }
            int expected = node.level() - attenuation(pos, neighbour, direction, levels == sky);
            if (value <= expected) {
                int emission = 0;
                if (!skyLayer && access.isLoaded(neighbour)) {
                    emission = Math.max(0, access.getBlockState(neighbour)
                        .getLightEmission(level, neighbour));
                }
                cancelPending(key, skyLayer);
                levels.remove(key);
                removeFromIndex(levels == sky ? skyKeysByCube : blockKeysByCube, key);
                markSection(key);
                if (emission > 0) {
                    // Vanilla rechecks a surviving emitter while propagating
                    // a decrease; dropping it here would permanently erase a
                    // light source that was only brighter because of a
                    // neighbouring source.
                    seed(block, blockKeysByCube, increase, key, emission);
                } else {
                    enqueueDecrease(levels, decrease, key, value,
                        ALL_DIRECTIONS & ~(1 << direction.getOpposite().ordinal()));
                }
            } else {
                enqueueIncrease(levels, increase, key, value,
                    1 << direction.getOpposite().ordinal());
            }
        }
    }

    private int attenuation(BlockPos source, BlockPos target, Direction direction, boolean skyLayer) {
        if (shapeOccludes(source, target, direction)) {
            return 16;
        }
        int opacity = opacity(target);
        if (skyLayer && direction == Direction.DOWN && opacity == 0) {
            return 0;
        }
        return Math.max(1, opacity);
    }

    private boolean shapeOccludes(BlockPos source, BlockPos target, Direction direction) {
        BlockState first = access.getBlockState(source);
        BlockState second = access.getBlockState(target);
        // Match LightEngine#getOcclusionShape: either side may opt out of
        // shape-based occlusion. In that case its contribution is an empty
        // shape; checking only when both flags are false over-occludes glass,
        // leaves and custom partial blocks.
        VoxelShape firstShape = first.canOcclude() && first.useShapeForLightOcclusion()
            ? first.getFaceOcclusionShape(level, source, direction) : Shapes.empty();
        VoxelShape secondShape = second.canOcclude() && second.useShapeForLightOcclusion()
            ? second.getFaceOcclusionShape(level, target, direction.getOpposite()) : Shapes.empty();
        return Shapes.faceShapeOccludes(firstShape, secondShape);
    }

    private int opacity(BlockPos pos) {
        if (!access.isLoaded(pos)) {
            return 15;
        }
        BlockState state = access.getBlockState(pos);
        return Math.min(15, Math.max(0, state.getLightBlock(level, pos)));
    }

    /** Returns the non-sparse local sky baseline for one block position. */
    private int directSkyLevel(long packedPos) {
        if (skySourceKeys.contains(packedPos)) {
            return 15;
        }
        AllvrCube cube = loadedCubes.get(AllvrCubePos.asLong(BlockPos.of(packedPos)));
        if (cube == null) {
            return 0;
        }
        int[] tops = cube.skyTopOpaque();
        if (tops == null) {
            return 0;
        }
        BlockPos pos = BlockPos.of(packedPos);
        int top = tops[((pos.getZ() & 31) << 5) | (pos.getX() & 31)];
        return (pos.getY() & 31) > top ? 15 : 0;
    }

    private void clearRegion(AllvrCubePos center, boolean expanded) {
        int minX = (center.getX() - (expanded ? 1 : 0)) * 32;
        int minY = (center.getY() - (expanded ? 1 : 0)) * 32;
        int minZ = (center.getZ() - (expanded ? 1 : 0)) * 32;
        int maxX = (center.getX() + 1 + (expanded ? 1 : 0)) * 32;
        int maxY = (center.getY() + 1 + (expanded ? 1 : 0)) * 32;
        int maxZ = (center.getZ() + 1 + (expanded ? 1 : 0)) * 32;
        clearMap(block, minX, minY, minZ, maxX, maxY, maxZ);
        clearMap(sky, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void clearMap(Long2ByteOpenHashMap map, int minX, int minY, int minZ,
                          int maxX, int maxY, int maxZ) {
        Long2ObjectOpenHashMap<LongOpenHashSet> index = map == sky ? skyKeysByCube : blockKeysByCube;
        int minCubeX = Math.floorDiv(minX, 32);
        int minCubeY = Math.floorDiv(minY, 32);
        int minCubeZ = Math.floorDiv(minZ, 32);
        int maxCubeX = Math.floorDiv(maxX - 1, 32);
        int maxCubeY = Math.floorDiv(maxY - 1, 32);
        int maxCubeZ = Math.floorDiv(maxZ - 1, 32);
        for (int cubeY = minCubeY; cubeY <= maxCubeY; cubeY++) {
            for (int cubeZ = minCubeZ; cubeZ <= maxCubeZ; cubeZ++) {
                for (int cubeX = minCubeX; cubeX <= maxCubeX; cubeX++) {
                    long cubeKey = AllvrCubePos.asLong(cubeX, cubeY, cubeZ);
                    LongOpenHashSet keys = index.get(cubeKey);
                    if (keys == null) {
                        continue;
                    }
                    LongIterator iterator = keys.iterator();
                    while (iterator.hasNext()) {
                        long key = iterator.nextLong();
                        BlockPos pos = BlockPos.of(key);
                        if (pos.getX() >= minX && pos.getX() < maxX
                            && pos.getY() >= minY && pos.getY() < maxY
                            && pos.getZ() >= minZ && pos.getZ() < maxZ) {
                            cancelPending(key, map == sky);
                            map.remove(key);
                            if (map == sky) {
                                removeSkySourceIndex(key);
                            }
                            iterator.remove();
                            markSection(key);
                        }
                    }
                    if (keys.isEmpty()) {
                        index.remove(cubeKey);
                    }
                }
            }
        }
    }

    private static long cubeKey(long blockPos) {
        return AllvrCubePos.asLong(BlockPos.getX(blockPos) >> 5,
            BlockPos.getY(blockPos) >> 5, BlockPos.getZ(blockPos) >> 5);
    }

    private static void removeFromIndex(Long2ObjectOpenHashMap<LongOpenHashSet> index, long pos) {
        long cube = cubeKey(pos);
        LongOpenHashSet keys = index.get(cube);
        if (keys != null) {
            keys.remove(pos);
            if (keys.isEmpty()) {
                index.remove(cube);
            }
        }
    }

    private void removeSkySourceIndex(long pos) {
        LongOpenHashSet owners = skySourceOwners.remove(pos);
        if (owners == null) {
            return;
        }
        skySourceKeys.remove(pos);
        LongIterator iterator = owners.iterator();
        while (iterator.hasNext()) {
            long owner = iterator.nextLong();
            LongOpenHashSet sources = skySourcesByCube.get(owner);
            if (sources != null) {
                sources.remove(pos);
                if (sources.isEmpty()) {
                    skySourcesByCube.remove(owner);
                }
            }
        }
    }

    private void markSection(long blockPos) {
        long section = SectionPos.asLong(BlockPos.getX(blockPos) >> 4,
            BlockPos.getY(blockPos) >> 4, BlockPos.getZ(blockPos) >> 4);
        sectionCache.remove(section);
        long cubeKey = AllvrCubePos.asLong(BlockPos.getX(blockPos) >> 5,
            BlockPos.getY(blockPos) >> 5, BlockPos.getZ(blockPos) >> 5);
        dirtyCubes.add(cubeKey);
        AllvrCube cube = loadedCubes.get(cubeKey);
        if (cube != null) {
            cube.markLightDirty();
        }
    }
}
