package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import java.util.List;

import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
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

    /** Reuse immutable section copies per cube instance and mutation version. */
    private static final int SECTION_COPY_CACHE_LIMIT = 512;
    private static final Long2ObjectLinkedOpenHashMap<SectionCopyCache> SECTION_COPY_CACHE =
        new Long2ObjectLinkedOpenHashMap<>();
    private static final Long2ObjectLinkedOpenHashMap<ClonedCache> CLONED_SECTION_CACHE =
        new Long2ObjectLinkedOpenHashMap<>();
    private static final int CLONED_SECTION_CACHE_LIMIT = 512;
    /** The synthetic ALLVR sections still need a real column carrier for
     * Sodium's platform light/model hooks. Reuse the vanilla carrier lookup
     * result across neighbouring rebuild contexts. */
    private static final Long2ObjectLinkedOpenHashMap<LevelChunk> CARRIER_CACHE =
        new Long2ObjectLinkedOpenHashMap<>();
    private static final int CARRIER_CACHE_LIMIT = 512;
    private static long clonedResourceRevision = Long.MIN_VALUE;
    private static long clonedWindowEpoch = Long.MIN_VALUE;

    private static ClientLevel airSectionLevel;
    private static LevelChunkSection cachedAirSection;
    private static ClientLevel carrierCacheLevel;

    private record SectionCopyCache(AllvrCube cube, long mutationVersion,
                                    LevelChunkSection[] sections) {}

    private record ClonedCache(long resourceRevision, long windowEpoch,
                               ClonedChunkSection section) {}

    private AllvrSodiumSectionSource() {}

    /** Drops all world-owned section/carrier references on level teardown. */
    public static void clear() {
        synchronized (AllvrClientCubeCache.LOCK) {
            SECTION_COPY_CACHE.clear();
            CLONED_SECTION_CACHE.clear();
            CARRIER_CACHE.clear();
            clonedResourceRevision = Long.MIN_VALUE;
            clonedWindowEpoch = Long.MIN_VALUE;
            carrierCacheLevel = null;
        }
        synchronized (AllvrSodiumSectionSource.class) {
            airSectionLevel = null;
            cachedAirSection = null;
        }
    }

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
        long cubeKey = AllvrCubePos.asLong(absolutePos.getX() >> 1,
            absolutePos.getY() >> 1, absolutePos.getZ() >> 1);

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
        return hasContent(level, virtualPos.getX(), virtualPos.getY(), virtualPos.getZ(),
            resourceRevision, windowEpoch);
    }

    /** Primitive-coordinate overload used by the section bridge's hot paths. */
    public static boolean hasContent(ClientLevel level, int virtualX, int virtualY, int virtualZ,
                                     long resourceRevision, long windowEpoch) {
        if (!isAllay(level)) {
            return false;
        }
        AllvrRenderYWindow window = AllvrRenderWindowState.current();
        int originSectionY = window.originBlockY() >> 4;
        int absoluteY = virtualY + originSectionY;
        long cubeKey = AllvrCubePos.asLong(virtualX >> 1, absoluteY >> 1, virtualZ >> 1);

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
            int localX = virtualX & 1;
            int localY = absoluteY & 1;
            int localZ = virtualZ & 1;
            LevelChunkSection section = cube.getSections()[AllvrCube.sliceIndex(localX, localY, localZ)];
            return section != null && !section.hasOnlyAir();
        }
    }

    /**
     * Invalidates cached cloned sections whose 3x3x3 mesh context or
     * sparse light dependency can observe a cube update. Cube updates
     * are published on the client thread before Sodium is asked to rebuild,
     * so this keeps the render-side cache exact without globally invalidating
     * every section on each packet.
     */
    public static void invalidateCube(long cubeKey) {
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        AllvrRenderYWindow window = AllvrRenderWindowState.current();
        int minX = cube.getX() << 1;
        int minY = window.virtualSectionY(cube.getY() << 1);
        int minZ = cube.getZ() << 1;
        /* Keep the cloned mesh boundary invalidated. Light snapshots are
         * owned by the shared sparse engine and are dropped below. */
        synchronized (AllvrClientCubeCache.LOCK) {
            // Direct sky is owned by the cube's immutable column snapshot;
            // only the cube and its one-section mesh boundary can change.
            for (int y = minY - 1; y <= minY + 2; y++) {
                for (int z = minZ - 1; z <= minZ + 2; z++) {
                    for (int x = minX - 1; x <= minX + 2; x++) {
                        CLONED_SECTION_CACHE.remove(SectionPos.asLong(x, y, z));
                    }
                }
            }
        }
        if (AllvrClientCubeCache.lightEngine() != null) {
            AllvrClientCubeCache.lightEngine().invalidateCubeSections(cubeKey);
        }
    }

    /** Invalidates one virtual section after a block mutation. */
    public static void invalidateSection(int virtualX, int virtualY, int virtualZ) {
        CLONED_SECTION_CACHE.remove(SectionPos.asLong(virtualX, virtualY, virtualZ));
    }

    /** Invalidates light data affected by one changed section column. */
    public static void invalidateLightingSection(int virtualX, int virtualY, int virtualZ) {
        if (AllvrClientCubeCache.lightEngine() != null) {
            AllvrClientCubeCache.lightEngine().invalidateCubeSections(
                AllvrCubePos.asLong(virtualX >> 1,
                    AllvrRenderWindowState.current().absoluteSectionY(virtualY) >> 1,
                    virtualZ >> 1));
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
        int originX = virtualOrigin.getX();
        int originY = virtualOrigin.getY();
        int originZ = virtualOrigin.getZ();
        int index = 0;
        for (int y = -CONTEXT_RADIUS; y <= CONTEXT_RADIUS; y++) {
            for (int z = -CONTEXT_RADIUS; z <= CONTEXT_RADIUS; z++) {
                for (int x = -CONTEXT_RADIUS; x <= CONTEXT_RADIUS; x++) {
                    int virtualX = originX + x;
                    int virtualY = originY + y;
                    int virtualZ = originZ + z;
                    LevelChunk carrier = carrier(level, virtualX, virtualZ);
                    sections[index++] = clonedSection(level, carrier, virtualX, virtualY, virtualZ,
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

    /** Supplies the last completed vanilla-style light snapshot for Sodium. */
    public static DataLayer[] lightData(ClientLevel level, SectionPos absolutePos) {
        return isAllay(level) ? AllvrClientCubeCache.lightData(absolutePos) : null;
    }

    private static void resetClonedCacheIfNeeded(long resourceRevision, long windowEpoch) {
        if (clonedResourceRevision == resourceRevision && clonedWindowEpoch == windowEpoch) {
            return;
        }
        CLONED_SECTION_CACHE.clear();
        CARRIER_CACHE.clear();
        carrierCacheLevel = null;
        clonedResourceRevision = resourceRevision;
        clonedWindowEpoch = windowEpoch;
    }

    private static LevelChunk carrier(ClientLevel level, int chunkX, int chunkZ) {
        if (carrierCacheLevel != level) {
            CARRIER_CACHE.clear();
            carrierCacheLevel = level;
        }
        long key = ChunkPos.asLong(chunkX, chunkZ);
        LevelChunk carrier = CARRIER_CACHE.getAndMoveToLast(key);
        if (carrier != null) {
            return carrier;
        }
        carrier = level.getChunk(chunkX, chunkZ);
        if (CARRIER_CACHE.size() >= CARRIER_CACHE_LIMIT) {
            CARRIER_CACHE.removeFirst();
        }
        CARRIER_CACHE.putAndMoveToLast(key, carrier);
        return carrier;
    }

    private static ClonedChunkSection clonedSection(ClientLevel level, LevelChunk carrier,
                                                    int virtualX, int virtualY, int virtualZ,
                                                    long resourceRevision, long windowEpoch) {
        long key = SectionPos.asLong(virtualX, virtualY, virtualZ);
        ClonedCache cached = CLONED_SECTION_CACHE.getAndMoveToLast(key);
        if (cached != null && cached.resourceRevision() == resourceRevision
            && cached.windowEpoch() == windowEpoch) {
            return cached.section();
        }
        SectionPos virtualPos = SectionPos.of(virtualX, virtualY, virtualZ);
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
            new ClonedCache(resourceRevision, windowEpoch, cloned));
        return cloned;
    }

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
