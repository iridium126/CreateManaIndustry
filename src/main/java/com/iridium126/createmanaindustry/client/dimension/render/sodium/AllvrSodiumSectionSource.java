package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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

    private static final Map<Long, DataLayer[]> LIGHT_CACHE = new HashMap<>();
    private static long lightCacheRevision = Long.MIN_VALUE;

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
            LevelChunkSection copy = copySection(live);
            return new AllvrSodiumSectionSnapshot(virtualPos, absolutePos, cubeKey,
                cube.mutationVersion(), AllvrClientCubeCache.contentRevisionUnsafe(),
                resourceRevision, windowEpoch, copy);
        }
    }

    /** True when a source section should have a native Sodium RenderSection. */
    public static boolean hasContent(ClientLevel level, SectionPos virtualPos,
                                      long resourceRevision, long windowEpoch) {
        AllvrSodiumSectionSnapshot snapshot = snapshot(level, virtualPos, resourceRevision, windowEpoch);
        return snapshot != null && !snapshot.isEmpty();
    }

    /**
     * Creates the exact ChunkRenderContext shape expected by Sodium's native
     * ChunkBuilderMeshingTask.  The LevelChunk passed to ClonedChunkSection is
     * only the existing column carrier for platform light/model hooks; all
     * block state and biome data come from the immutable ALLVR section copy.
     */
    public static ChunkRenderContext prepare(ClientLevel level, SectionPos virtualOrigin,
                                             long resourceRevision, long windowEpoch) {
        ClonedChunkSection[] sections = new ClonedChunkSection[CONTEXT_SIZE];
        int index = 0;
        for (int y = -CONTEXT_RADIUS; y <= CONTEXT_RADIUS; y++) {
            for (int z = -CONTEXT_RADIUS; z <= CONTEXT_RADIUS; z++) {
                for (int x = -CONTEXT_RADIUS; x <= CONTEXT_RADIUS; x++) {
                    SectionPos virtualPos = SectionPos.of(virtualOrigin.getX() + x,
                        virtualOrigin.getY() + y, virtualOrigin.getZ() + z);
                    AllvrSodiumSectionSnapshot snapshot = snapshot(level, virtualPos,
                        resourceRevision, windowEpoch);
                    LevelChunkSection section = snapshot == null ? null : snapshot.section();
                    SectionPos dataPos = snapshot == null ? virtualPos : snapshot.absolutePos();
                    int chunkX = virtualPos.getX();
                    int chunkZ = virtualPos.getZ();
                    LevelChunk carrier = level.getChunk(chunkX, chunkZ);
                    sections[index++] = SodiumApi_0813_1211.cloneSection(level, carrier,
                        section, dataPos);
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
     * section constructor. The cache is revision-scoped, so packet updates
     * invalidate old light without touching Sodium's worker or upload queues.
     */
    public static DataLayer[] lightData(ClientLevel level, SectionPos absolutePos,
                                        long contentRevision) {
        if (!isAllay(level)) {
            return null;
        }
        int absoluteSectionY = absolutePos.getY();
        long key = SectionPos.asLong(absolutePos.getX(), absoluteSectionY, absolutePos.getZ());
        synchronized (AllvrClientCubeCache.LOCK) {
            if (lightCacheRevision != contentRevision) {
                LIGHT_CACHE.clear();
                lightCacheRevision = contentRevision;
            }
            DataLayer[] cached = LIGHT_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            DataLayer[] built = buildLightData(absoluteSectionY,
                absolutePos.getX(), absolutePos.getZ());
            LIGHT_CACHE.put(key, built);
            return built;
        }
    }

    private static DataLayer[] buildLightData(int absoluteSectionY,
                                               int sectionX, int sectionZ) {
        int minX = sectionX << 4;
        int minY = absoluteSectionY << 4;
        int minZ = sectionZ << 4;
        DataLayer block = new DataLayer(0);
        DataLayer sky = new DataLayer(0);

        // Sky exposure is column-shaped. Scan each column once, then answer
        // all sixteen section Y samples from a prefix table.
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int[] opaque = new int[144];
                for (int rel = 1; rel <= 143; rel++) {
                    BlockPos pos = new BlockPos(minX + x, minY + rel, minZ + z);
                    opaque[rel] = opaque[rel - 1]
                        + (AllvrClientCubeCache.getBlockState(pos).canOcclude() ? 1 : 0);
                }
                for (int y = 0; y < 16; y++) {
                    if (opaque[y + 128] == opaque[y]) {
                        sky.set(x, y, z, 15);
                    }
                }
            }
        }

        // Gather the same 3x3x3 cube emitter neighborhood used by the entity
        // light sampler. Typical islands have no emitters, making this path
        // effectively a single zero-filled DataLayer.
        List<Emitter> emitters = new ArrayList<>();
        AllvrCubePos center = AllvrCubePos.of(new BlockPos(minX, minY, minZ));
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    AllvrCube cube = AllvrClientCubeCache.peekCubeUnsafe(AllvrCubePos.asLong(
                        new BlockPos(center.minBlockX() + dx * 32,
                            center.minBlockY() + dy * 32,
                            center.minBlockZ() + dz * 32)));
                    if (cube == null || cube.getEmitters().isEmpty()) continue;
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
        for (Emitter emitter : emitters) {
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
        return new LevelChunkSection(
            level.registryAccess().registryOrThrow(Registries.BIOME));
    }

    /** Small indirection prevents this source from owning the shared window. */
    private static final class AllvrRenderWindowState {
        static AllvrRenderYWindow current() {
            return AllvrSodiumBridge.window();
        }
    }
}
