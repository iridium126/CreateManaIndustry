package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import java.util.List;
import java.util.ArrayList;

import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderYWindow;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * The ALLVR-to-Sodium data boundary.  This class owns no render resources and
 * never registers a synthetic chunk with ClientChunkCache: it snapshots the
 * 3x3x3 section neighbourhood Sodium's LevelSlice expects directly from the
 * 32^3 cube cache.
 */
public final class AllvrSodiumSectionSource {

    /** LevelSlice's fixed one-section neighbour radius in Sodium 0.8.13.
     * The source constant is a two-block border rounded up to one section,
     * so its cloned context is exactly 3x3x3, not 5x5x5. */
    public static final int CONTEXT_RADIUS = 1;
    public static final int CONTEXT_SIDE = CONTEXT_RADIUS * 2 + 1;
    public static final int CONTEXT_SIZE = CONTEXT_SIDE * CONTEXT_SIDE * CONTEXT_SIDE;

    private static final Long2ObjectLinkedOpenHashMap<LightCacheEntry> LIGHT_CACHE =
        new Long2ObjectLinkedOpenHashMap<>();
    private static final int LIGHT_CACHE_LIMIT = 1024;
    /** Reuse immutable section copies per cube instance and mutation version.
     * A global content revision changes for every streamed cube; tying this
     * cache to that revision would recopy unchanged neighbours on every packet.
     */
    private static final int SECTION_COPY_CACHE_LIMIT = 512;
    private static final Long2ObjectLinkedOpenHashMap<SectionCopyCache> SECTION_COPY_CACHE =
        new Long2ObjectLinkedOpenHashMap<>();
    private static final Long2ObjectLinkedOpenHashMap<ClonedCache> CLONED_SECTION_CACHE =
        new Long2ObjectLinkedOpenHashMap<>();
    private static final int CLONED_SECTION_CACHE_LIMIT = 512;
    private static long clonedContentRevision = Long.MIN_VALUE;
    private static long clonedResourceRevision = Long.MIN_VALUE;
    private static long clonedWindowEpoch = Long.MIN_VALUE;

    private static ClientLevel airSectionLevel;
    private static LevelChunkSection cachedAirSection;

    private record SectionCopyCache(AllvrCube cube, long mutationVersion,
                                    LevelChunkSection[] sections) {}

    private record ClonedCache(long contentRevision, long sourceFingerprint,
                               long resourceRevision, long windowEpoch,
                               ClonedChunkSection section) {}

    private record LightInputs(int minY, int cubeY0, int[] columnMasks,
                               List<Emitter> emitters, long fingerprint) {}

    private record LightCacheEntry(long dependencyFingerprint, DataLayer[] data) {}

    private AllvrSodiumSectionSource() {}

    public static boolean isAllay(ClientLevel level) {
        return level != null && level.dimension() == AllvrDimensions.ALLAY_LEVEL;
    }

    /** Returns the source section in the requested virtual key space. */
    public static AllvrSodiumSectionSnapshot snapshot(ClientLevel level,
                                                       SectionPos virtualPos,
                                                       long resourceRevision,
                                                       long windowEpoch) {
        if (!isAllay(level)) {
            return null;
        }
        AllvrRenderYWindow window = AllvrRenderWindowState.current();
        int originSectionY = window.originBlockY() >> 4;
        SectionPos absolutePos = SectionPos.of(virtualPos.getX(),
            virtualPos.getY() + originSectionY, virtualPos.getZ());
        long cubeKey = AllvrCubePos.asLong(new BlockPos(absolutePos.minBlockX(),
            absolutePos.minBlockY(), absolutePos.minBlockZ()));

        synchronized (AllvrClientCubeCache.LOCK) {
            AllvrCube cube = AllvrClientCubeCache.peekCubeUnsafe(cubeKey);
            if (cube == null) {
                return new AllvrSodiumSectionSnapshot(virtualPos, absolutePos, cubeKey,
                    0L, AllvrClientCubeCache.contentRevisionUnsafe(), resourceRevision,
                    windowEpoch, airSection(level));
            }
            int localX = absolutePos.getX() & 1;
            int localY = absolutePos.getY() & 1;
            int localZ = absolutePos.getZ() & 1;
            LevelChunkSection live = cube.getSections()[AllvrCube.sliceIndex(localX, localY, localZ)];
            long contentRevision = AllvrClientCubeCache.contentRevisionUnsafe();
            SectionCopyCache entry = SECTION_COPY_CACHE.getAndMoveToLast(cubeKey);
            LevelChunkSection[] cached = entry == null || entry.cube() != cube
                || entry.mutationVersion() != cube.mutationVersion() ? null : entry.sections();
            if (cached == null) {
                if (SECTION_COPY_CACHE.size() >= SECTION_COPY_CACHE_LIMIT) {
                    SECTION_COPY_CACHE.removeFirst();
                }
                cached = new LevelChunkSection[AllvrCube.SECTIONS_PER_CUBE];
                SECTION_COPY_CACHE.putAndMoveToLast(cubeKey,
                    new SectionCopyCache(cube, cube.mutationVersion(), cached));
            }
            int sectionIndex = AllvrCube.sliceIndex(localX, localY, localZ);
            LevelChunkSection copy = cached[sectionIndex];
            if (copy == null) {
                copy = copySection(live);
                cached[sectionIndex] = copy;
            }
            return new AllvrSodiumSectionSnapshot(virtualPos, absolutePos, cubeKey,
                cube.mutationVersion(), contentRevision,
                resourceRevision, windowEpoch, copy);
        }
    }

    /** True when a source section should have a native Sodium RenderSection. */
    public static boolean hasContent(ClientLevel level, SectionPos virtualPos,
                                      long resourceRevision, long windowEpoch) {
        if (!isAllay(level)) {
            return false;
        }
        AllvrRenderYWindow window = AllvrRenderWindowState.current();
        int originSectionY = window.originBlockY() >> 4;
        SectionPos absolutePos = SectionPos.of(virtualPos.getX(),
            virtualPos.getY() + originSectionY, virtualPos.getZ());
        long cubeKey = AllvrCubePos.asLong(new BlockPos(absolutePos.minBlockX(),
            absolutePos.minBlockY(), absolutePos.minBlockZ()));

        /*
         * This predicate is called for every section around an arriving cube
         * and for each boundary neighbour.  The previous implementation
         * called snapshot(), which copied a complete 16³ PalettedContainer
         * just to decide whether to enqueue Sodium work.  Vanilla's chunk
         * holder uses the section's non-empty counter for this gate and only
         * snapshots the section when the renderer actually builds it.
         */
        synchronized (AllvrClientCubeCache.LOCK) {
            AllvrCube cube = AllvrClientCubeCache.peekCubeUnsafe(cubeKey);
            if (cube == null) {
                return false;
            }
            int localX = absolutePos.getX() & 1;
            int localY = absolutePos.getY() & 1;
            int localZ = absolutePos.getZ() & 1;
            LevelChunkSection section = cube.getSections()[AllvrCube.sliceIndex(localX, localY, localZ)];
            return section != null && !section.hasOnlyAir();
        }
    }

    /**
     * Creates the exact ChunkRenderContext shape expected by Sodium's native
     * ChunkBuilderMeshingTask.  The LevelChunk passed to ClonedChunkSection is
     * only the existing column carrier for platform light/model hooks; all
     * block state and biome data come from the immutable ALLVR section copy.
     */
    public static ChunkRenderContext prepare(ClientLevel level, SectionPos virtualOrigin,
                                             long resourceRevision, long windowEpoch) {
        resetClonedCacheIfNeeded(resourceRevision, windowEpoch);
        ClonedChunkSection[] sections = new ClonedChunkSection[CONTEXT_SIZE];
        LevelChunk[] carriers = new LevelChunk[CONTEXT_SIDE * CONTEXT_SIDE];
        int index = 0;
        for (int y = -CONTEXT_RADIUS; y <= CONTEXT_RADIUS; y++) {
            for (int z = -CONTEXT_RADIUS; z <= CONTEXT_RADIUS; z++) {
                for (int x = -CONTEXT_RADIUS; x <= CONTEXT_RADIUS; x++) {
                    SectionPos virtualPos = SectionPos.of(virtualOrigin.getX() + x,
                        virtualOrigin.getY() + y, virtualOrigin.getZ() + z);
                    int carrierIndex = (z + CONTEXT_RADIUS) * CONTEXT_SIDE
                        + (x + CONTEXT_RADIUS);
                    LevelChunk carrier = carriers[carrierIndex];
                    if (carrier == null) {
                        carrier = level.getChunk(virtualPos.getX(), virtualPos.getZ());
                        carriers[carrierIndex] = carrier;
                    }
                    sections[index++] = clonedSection(level, carrier, virtualPos,
                        resourceRevision, windowEpoch);
                }
            }
        }
        int absoluteSectionY = AllvrSodiumBridge.window()
            .absoluteSectionY(virtualOrigin.getY());
        SectionPos absoluteOriginSection = SectionPos.of(virtualOrigin.getX(),
            absoluteSectionY, virtualOrigin.getZ());
        BoundingBox volume = new BoundingBox(
            absoluteOriginSection.minBlockX() - 2, absoluteOriginSection.minBlockY() - 2,
            absoluteOriginSection.minBlockZ() - 2,
            absoluteOriginSection.maxBlockX() + 2, absoluteOriginSection.maxBlockY() + 2,
            absoluteOriginSection.maxBlockZ() + 2);
        BlockPos absoluteOrigin = absoluteOriginSection.origin();
        List<?> appenders = SodiumApi_0813_1211.chunkMeshAppenders(level, absoluteOrigin);
        return SodiumApi_0813_1211.context(absoluteOriginSection, sections, volume, appenders);
    }

    /** Returns a source-backed one-slot array for RenderSectionManager's air check. */
    public static LevelChunkSection[] sectionArray(ClientLevel level, int virtualX, int virtualY, int virtualZ,
                                                   long resourceRevision, long windowEpoch) {
        LevelChunkSection[] sections = new LevelChunkSection[1];
        AllvrSodiumSectionSnapshot snapshot = snapshot(level,
            SectionPos.of(virtualX, virtualY, virtualZ), resourceRevision, windowEpoch);
        sections[0] = snapshot == null ? null : snapshot.section();
        return sections;
    }

    /**
     * Supplies immutable per-section light snapshots to Sodium's cloned
     * section constructor. The cache is keyed by the cube identities and
     * mutation versions that can affect this section's sky/block light, so an
     * unrelated streamed cube does not force every visible section to rebuild
     * its light field.
     */
    public static DataLayer[] lightData(ClientLevel level, SectionPos absolutePos) {
        if (!isAllay(level)) {
            return null;
        }
        int absoluteSectionY = absolutePos.getY();
        long key = SectionPos.asLong(absolutePos.getX(), absoluteSectionY, absolutePos.getZ());
        LightInputs inputs;
        synchronized (AllvrClientCubeCache.LOCK) {
            long fingerprint = lightFingerprint(absoluteSectionY,
                absolutePos.getX(), absolutePos.getZ());
            LightCacheEntry cached = LIGHT_CACHE.getAndMoveToLast(key);
            if (cached != null && cached.dependencyFingerprint() == fingerprint) {
                return cached.data();
            }
            /* Capture only immutable primitive masks while the cube-map lock
             * is held; the expensive light-field build happens after unlock. */
            inputs = captureLightInputs(absoluteSectionY, absolutePos.getX(), absolutePos.getZ(), fingerprint);
        }
        DataLayer[] built = buildLightData(inputs, absolutePos.getX(), absolutePos.getZ());
        synchronized (AllvrClientCubeCache.LOCK) {
            LightCacheEntry existing = LIGHT_CACHE.getAndMoveToLast(key);
            if (existing != null && existing.dependencyFingerprint() == inputs.fingerprint()) {
                return existing.data();
            }
            if (LIGHT_CACHE.size() >= LIGHT_CACHE_LIMIT) {
                LIGHT_CACHE.removeFirst();
            }
            LIGHT_CACHE.putAndMoveToLast(key,
                new LightCacheEntry(inputs.fingerprint(), built));
        }
        return built;
    }

    private static LightInputs captureLightInputs(int absoluteSectionY,
                                                   int sectionX, int sectionZ,
                                                   long fingerprint) {
        int minX = sectionX << 4;
        int minY = absoluteSectionY << 4;
        int minZ = sectionZ << 4;
        int firstCubeY = Math.floorDiv(minY + 1, 32);
        int lastCubeY = Math.floorDiv(minY + 143, 32);
        int cubeLayers = lastCubeY - firstCubeY + 1;
        int[] columnMasks = new int[cubeLayers * 256];
        int cubeX = Math.floorDiv(minX, 32);
        int cubeZ = Math.floorDiv(minZ, 32);
        int localSectionX = Math.floorMod(minX, 32);
        int localSectionZ = Math.floorMod(minZ, 32);

        for (int layer = 0; layer < cubeLayers; layer++) {
            AllvrCube cube = AllvrClientCubeCache.peekCubeUnsafe(
                AllvrCubePos.asLong(cubeX, firstCubeY + layer, cubeZ));
            if (cube == null) {
                continue;
            }
            int[] masks = opacityMasks(cube);
            int target = layer * 256;
            for (int z = 0; z < 16; z++) {
                int sourceZ = localSectionZ + z;
                for (int x = 0; x < 16; x++) {
                    columnMasks[target + (z << 4) + x] =
                        masks[(sourceZ << 5) + localSectionX + x];
                }
            }
        }

        List<Emitter> emitters = new ArrayList<>();
        int centerCubeY = Math.floorDiv(minY, 32);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    AllvrCube cube = AllvrClientCubeCache.peekCubeUnsafe(
                        AllvrCubePos.asLong(cubeX + dx, centerCubeY + dy, cubeZ + dz));
                    if (cube == null || cube.getEmitters().isEmpty()) {
                        continue;
                    }
                    for (it.unimi.dsi.fastutil.ints.Int2IntMap.Entry entry
                        : cube.getEmitters().int2IntEntrySet()) {
                        int cell = entry.getIntKey();
                        emitters.add(new Emitter(
                            cube.getPos().minBlockX() + (cell & 31),
                            cube.getPos().minBlockY() + (cell >> 10),
                            cube.getPos().minBlockZ() + ((cell >> 5) & 31),
                            entry.getIntValue()));
                    }
                }
            }
        }
        return new LightInputs(minY, firstCubeY, columnMasks, emitters, fingerprint);
    }

    private static long lightFingerprint(int absoluteSectionY, int sectionX, int sectionZ) {
        int minX = sectionX << 4;
        int minY = absoluteSectionY << 4;
        int minZ = sectionZ << 4;
        int cubeX = Math.floorDiv(minX, 32);
        int cubeZ = Math.floorDiv(minZ, 32);
        int firstCubeY = Math.floorDiv(minY + 1, 32);
        int lastCubeY = Math.floorDiv(minY + 143, 32);
        long fingerprint = 0xcbf29ce484222325L;
        for (int cubeY = firstCubeY; cubeY <= lastCubeY; cubeY++) {
            fingerprint = mixCube(fingerprint, cubeX, cubeY, cubeZ);
        }
        int centerCubeY = Math.floorDiv(minY, 32);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    fingerprint = mixCube(fingerprint, cubeX + dx, centerCubeY + dy, cubeZ + dz);
                }
            }
        }
        return fingerprint;
    }

    private static long mixCube(long fingerprint, int cubeX, int cubeY, int cubeZ) {
        long key = AllvrCubePos.asLong(cubeX, cubeY, cubeZ);
        AllvrCube cube = AllvrClientCubeCache.peekCubeUnsafe(key);
        long value = key ^ (cube == null ? 0x9e3779b97f4a7c15L :
            ((long) System.identityHashCode(cube) << 32) ^ cube.mutationVersion());
        fingerprint ^= value;
        return Long.rotateLeft(fingerprint, 27) * 0x9e3779b97f4a7c15L + 0x517cc1b727220a95L;
    }

    private static int[] opacityMasks(AllvrCube cube) {
        return cube.opacityColumns();
    }

    private static void resetClonedCacheIfNeeded(long resourceRevision, long windowEpoch) {
        long contentRevision = AllvrClientCubeCache.contentRevisionVolatile();
        if (clonedResourceRevision == resourceRevision
            && clonedWindowEpoch == windowEpoch) {
            clonedContentRevision = contentRevision;
            return;
        }
        CLONED_SECTION_CACHE.clear();
        clonedContentRevision = contentRevision;
        clonedResourceRevision = resourceRevision;
        clonedWindowEpoch = windowEpoch;
    }

    private static ClonedChunkSection clonedSection(ClientLevel level, LevelChunk carrier,
                                                    SectionPos virtualPos,
                                                    long resourceRevision,
                                                    long windowEpoch) {
        long key = SectionPos.asLong(virtualPos.getX(), virtualPos.getY(), virtualPos.getZ());
        ClonedCache cached = CLONED_SECTION_CACHE.getAndMoveToLast(key);
        if (cached != null && cached.contentRevision() == clonedContentRevision
            && cached.resourceRevision() == resourceRevision
            && cached.windowEpoch() == windowEpoch) {
            return cached.section();
        }
        long sourceFingerprint = sectionFingerprint(virtualPos);
        if (cached != null && cached.sourceFingerprint() == sourceFingerprint
            && cached.resourceRevision() == resourceRevision
            && cached.windowEpoch() == windowEpoch) {
            CLONED_SECTION_CACHE.putAndMoveToLast(key,
                new ClonedCache(clonedContentRevision, sourceFingerprint,
                    resourceRevision, windowEpoch, cached.section()));
            return cached.section();
        }
        AllvrSodiumSectionSnapshot snapshot = snapshot(level, virtualPos,
            resourceRevision, windowEpoch);
        LevelChunkSection section = snapshot == null ? null : snapshot.section();
        SectionPos dataPos = snapshot == null ? virtualPos : snapshot.absolutePos();
        ClonedChunkSection cloned = SodiumApi_0813_1211.cloneSection(level, carrier,
            section, dataPos);
        if (CLONED_SECTION_CACHE.size() >= CLONED_SECTION_CACHE_LIMIT) {
            CLONED_SECTION_CACHE.removeFirst();
        }
        CLONED_SECTION_CACHE.putAndMoveToLast(key,
            new ClonedCache(clonedContentRevision, sourceFingerprint,
                resourceRevision, windowEpoch, cloned));
        return cloned;
    }

    private static long sectionFingerprint(SectionPos virtualPos) {
        AllvrRenderYWindow window = AllvrRenderWindowState.current();
        int absoluteSectionY = virtualPos.getY() + (window.originBlockY() >> 4);
        long cubeKey = AllvrCubePos.asLong(
            Math.floorDiv(virtualPos.getX(), 2),
            Math.floorDiv(absoluteSectionY, 2),
            Math.floorDiv(virtualPos.getZ(), 2));
        synchronized (AllvrClientCubeCache.LOCK) {
            AllvrCube cube = AllvrClientCubeCache.peekCubeUnsafe(cubeKey);
            long source = cube == null ? 0x9e3779b97f4a7c15L
                : cubeKey ^ ((long) System.identityHashCode(cube) << 32)
                    ^ cube.mutationVersion();
            long light = lightFingerprint(absoluteSectionY,
                virtualPos.getX(), virtualPos.getZ());
            return Long.rotateLeft(source ^ light, 17) * 0x9e3779b97f4a7c15L;
        }
    }

    private static DataLayer[] buildLightData(LightInputs inputs,
                                               int sectionX, int sectionZ) {
        int minX = sectionX << 4;
        int minY = inputs.minY();
        int minZ = sectionZ << 4;
        DataLayer block = new DataLayer(0);
        DataLayer sky = new DataLayer(0);

        // Sky exposure is column-shaped. Check the compact 32-block masks
        // directly, so each sample visits at most five cube layers instead of
        // first expanding 143 samples into a temporary bitset.
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = (z << 4) + x;
                for (int y = 0; y < 16; y++) {
                    int from = minY + y + 1;
                    if (!hasOpaque(inputs.columnMasks(), inputs.cubeY0(), column,
                        from, from + 128)) {
                        sky.set(x, y, z, 15);
                    }
                }
            }
        }

        for (Emitter emitter : inputs.emitters()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int value = emitter.level - Math.abs(emitter.x - (minX + x))
                            - Math.abs(emitter.y - (minY + y))
                            - Math.abs(emitter.z - (minZ + z));
                        if (value > 0 && value > block.get(x, y, z)) {
                            block.set(x, y, z, Math.min(15, value));
                        }
                    }
                }
            }
        }
        // LightLayer is ordered SKY(0), BLOCK(1) in 1.21.1.
        return new DataLayer[] { sky, block };
    }

    private static boolean hasOpaque(int[] columns, int cubeY0, int column,
                                     int fromInclusive, int toExclusive) {
        int firstLayer = Math.floorDiv(fromInclusive, 32) - cubeY0;
        int lastLayer = Math.floorDiv(toExclusive - 1, 32) - cubeY0;
        for (int layer = firstLayer; layer <= lastLayer; layer++) {
            int from = layer == firstLayer ? fromInclusive & 31 : 0;
            int to = layer == lastLayer ? ((toExclusive - 1) & 31) + 1 : 32;
            int mask = -1 << from;
            if (to < 32) {
                mask &= (1 << to) - 1;
            }
            if ((columns[layer * 256 + column] & mask) != 0) {
                return true;
            }
        }
        return false;
    }

    private record Emitter(int x, int y, int z, int level) {}

    private static LevelChunkSection copySection(LevelChunkSection source) {
        if (source == null) {
            return null;
        }
        PalettedContainer<?> states = source.getStates().copy();
        @SuppressWarnings("unchecked")
        PalettedContainerRO<Holder<Biome>> biomes =
            (PalettedContainerRO<Holder<Biome>>) (PalettedContainer<?>) source.getBiomes();
        @SuppressWarnings("unchecked")
        PalettedContainer<Holder<Biome>> biomeCopy =
            ((PalettedContainer<Holder<Biome>>) biomes).copy();
        @SuppressWarnings("unchecked")
        PalettedContainer<net.minecraft.world.level.block.state.BlockState> stateCopy =
            (PalettedContainer<net.minecraft.world.level.block.state.BlockState>) states;
        return new LevelChunkSection(stateCopy, biomeCopy);
    }

    /**
     * Sodium's cloned-section constructor always reads the biome container,
     * including for an all-air section.  Keep misses as real vanilla section
     * values so they are safe inputs without registering a synthetic chunk.
     */
    private static LevelChunkSection airSection(ClientLevel level) {
        if (level == airSectionLevel && cachedAirSection != null) {
            return cachedAirSection;
        }
        synchronized (AllvrSodiumSectionSource.class) {
            if (level != airSectionLevel || cachedAirSection == null) {
                cachedAirSection = new LevelChunkSection(
                    level.registryAccess().registryOrThrow(Registries.BIOME));
                airSectionLevel = level;
            }
            return cachedAirSection;
        }
    }

    /** Small indirection prevents this source from owning the shared window. */
    private static final class AllvrRenderWindowState {
        static AllvrRenderYWindow current() {
            return AllvrSodiumBridge.window();
        }
    }
}
