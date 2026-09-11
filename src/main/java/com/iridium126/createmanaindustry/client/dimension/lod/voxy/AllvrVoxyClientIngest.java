package com.iridium126.createmanaindustry.client.dimension.lod.voxy;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumSectionSource;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Client-only Allay bridge into Voxy's normal ingest service.
 *
 * <p>The server sends only ordinary near cubes.  Each cube is eight vanilla
 * 16³ sections; this class snapshots those sections and submits them through
 * {@code VoxelIngestService.rawIngest}.  Voxy performs conversion, mip
 * propagation, dirty tracking and persistence.  Forgetting a near cube never
 * forgets Voxy data, exactly like Voxy's normal chunk unload path.</p>
 */
public final class AllvrVoxyClientIngest {

    private static final int SECTION_BUDGET_PER_TICK = 16;
    private static final int PREWARM_DISTANCE_BLOCKS = 1024;
    private static final ExecutorService PREWARM_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "allvr-voxy-slab-prewarm");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicLong LEVEL_EPOCH = new AtomicLong();

    private static volatile ClientLevel level;
    private static volatile long slabId;
    private static volatile boolean initialized;
    /**
     * Voxy creates its renderer from LevelRenderer#allChanged(), which can
     * race the NeoForge level-load callback by one client frame. Keep a
     * refresh pending until the level and player are both usable so the
     * renderer's top-level Y range is built for the correct Allay slab.
     */
    private static volatile boolean rendererRefreshPending;
    private static boolean residentScanQueued;
    private static long prewarmedSlab = Long.MIN_VALUE;
    private static final ArrayDeque<Long> SECTION_QUEUE = new ArrayDeque<>();
    private static final HashSet<Long> QUEUED_SECTIONS = new HashSet<>();

    private AllvrVoxyClientIngest() {}

    public static void bindLevel(ClientLevel newLevel) {
        clear();
        level = newLevel;
        if (newLevel != null && AllvrDimensions.isAllay(newLevel)) {
            slabId = AllvrVoxyYSlab.slabIdForLevel(newLevel);
            initialized = true;
            rendererRefreshPending = true;
        }
    }

    /**
     * Returns the slab currently used by the ingest bridge for this level.
     * Camera and WorldIdentifier mixins must use this value instead of
     * independently consulting Minecraft#player: during a dimension switch
     * those two observations can describe different frames of the transition.
     */
    public static long activeSlabId(Level targetLevel) {
        if (targetLevel != null && targetLevel == level && initialized) {
            return slabId;
        }
        return AllvrVoxyYSlab.slabIdForLevel(targetLevel);
    }

    public static void clear() {
        LEVEL_EPOCH.incrementAndGet();
        SECTION_QUEUE.clear();
        QUEUED_SECTIONS.clear();
        residentScanQueued = false;
        prewarmedSlab = Long.MIN_VALUE;
        rendererRefreshPending = false;
        initialized = false;
        level = null;
    }

    public static void onCubeApplied(long cubeKey) {
        if (!initialized || !VoxyApi_0215_1211.modInstalled()) return;
        // A cube can be replaced repeatedly by edits or reloads. Section-level
        // de-duplication is the queue state, so every replacement must enqueue
        // all eight sections to refresh Voxy's L0 value.
        enqueueCubeSections(cubeKey);
    }

    public static void onBlockChanged(net.minecraft.core.BlockPos pos) {
        if (!initialized || !VoxyApi_0215_1211.modInstalled()) return;
        int sx = pos.getX() >> 4;
        int sy = pos.getY() >> 4;
        int sz = pos.getZ() >> 4;
        enqueueSection(sx, sy, sz);
        if ((pos.getX() & 15) == 0) enqueueSection(sx - 1, sy, sz);
        if ((pos.getX() & 15) == 15) enqueueSection(sx + 1, sy, sz);
        if ((pos.getY() & 15) == 0) enqueueSection(sx, sy - 1, sz);
        if ((pos.getY() & 15) == 15) enqueueSection(sx, sy + 1, sz);
        if ((pos.getZ() & 15) == 0) enqueueSection(sx, sy, sz - 1);
        if ((pos.getZ() & 15) == 15) enqueueSection(sx, sy, sz + 1);
    }

    public static void tick() {
        if (!initialized || !VoxyApi_0215_1211.modInstalled()
            || level == null || !AllvrDimensions.isAllay(level)
            || !ClientConfig.allvrLod) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long wantedSlab = AllvrVoxyYSlab.slabIdForBlockY(mc.player.blockPosition().getY());
        maybePrewarm(wantedSlab, mc.player.blockPosition().getY());
        if (wantedSlab != slabId) {
            switchSlab(wantedSlab);
            return;
        }

        // The first Voxy renderer may have been constructed before the
        // allay LevelEvent.Load callback ran. Recreate it once after the
        // active level/player are known, even when the initial slab already
        // happens to match the player.
        if (rendererRefreshPending) {
            if (refreshRenderer()) {
                rendererRefreshPending = false;
                prewarmedSlab = wantedSlab;
                CreateManaIndustry.LOGGER.info(
                    "[Allvr] refreshed Voxy renderer for Allay Y slab {}", wantedSlab);
            }
            return;
        }

        if (!residentScanQueued) {
            residentScanQueued = true;
            for (long cube : AllvrClientCubeCache.cubeKeys()) {
                onCubeApplied(cube);
            }
        }

        try {
            me.cortex.voxy.common.world.WorldEngine engine =
                VoxyApi_0215_1211.findEngine(level);
            if (engine == null) return;
            int budget = SECTION_BUDGET_PER_TICK;
            while (budget-- > 0 && !SECTION_QUEUE.isEmpty()) {
                long sectionKey = SECTION_QUEUE.removeFirst();
                QUEUED_SECTIONS.remove(sectionKey);
                try {
                    ingestSection(engine, SectionPos.of(sectionKey));
                } catch (Throwable sectionFailure) {
                    // Renderer/engine reloads can invalidate the engine between
                    // lookup and rawIngest. Preserve this section for the next
                    // tick instead of silently losing the cube update.
                    if (QUEUED_SECTIONS.add(sectionKey)) {
                        SECTION_QUEUE.addFirst(sectionKey);
                    }
                    if (CreateManaIndustry.LOGGER.isDebugEnabled()) {
                        CreateManaIndustry.LOGGER.debug("[Allvr] Voxy section ingest deferred", sectionFailure);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            // Optional Voxy may disappear during a renderer reload.  Keep the
            // queue intact for the next tick instead of breaking cube handling.
            if (CreateManaIndustry.LOGGER.isDebugEnabled()) {
                CreateManaIndustry.LOGGER.debug("[Allvr] Voxy cube ingest deferred", t);
            }
        }
    }

    private static void maybePrewarm(long wantedSlab, int playerY) {
        long current = slabId;
        int center = AllvrVoxyYSlab.slabCenterBlockY(current);
        long next = playerY >= center ? current + 1 : current - 1;
        int boundary = next > current
            ? AllvrVoxyYSlab.slabCenterBlockY(current) + AllvrVoxyYSlab.BLOCKS_PER_SLAB / 2
            : AllvrVoxyYSlab.slabCenterBlockY(current) - AllvrVoxyYSlab.BLOCKS_PER_SLAB / 2;
        if (Math.abs(playerY - boundary) > PREWARM_DISTANCE_BLOCKS || prewarmedSlab == next) {
            return;
        }
        prewarmedSlab = next;
        ClientLevel targetLevel = level;
        long targetEpoch = LEVEL_EPOCH.get();
        PREWARM_EXECUTOR.execute(() -> {
            try {
                var common = me.cortex.voxy.commonImpl.VoxyCommon.getInstance();
                if (common instanceof me.cortex.voxy.client.VoxyClientInstance client) {
                    if (targetLevel == null || targetEpoch != LEVEL_EPOCH.get() || level != targetLevel) return;
                    var base = ((me.cortex.voxy.commonImpl.IWorldGetIdentifier) targetLevel)
                        .voxy$getIdentifier();
                    client.getOrCreate(AllvrVoxyYSlab.withSlab(base, next));
                }
            } catch (Throwable t) {
                prewarmedSlab = Long.MIN_VALUE;
                if (CreateManaIndustry.LOGGER.isDebugEnabled()) {
                    CreateManaIndustry.LOGGER.debug("[Allvr] Voxy Y slab prewarm failed", t);
                }
            }
        });
    }

    private static boolean refreshRenderer() {
        try {
            var renderer = (me.cortex.voxy.client.core.IGetVoxyRenderSystem)
                Minecraft.getInstance().levelRenderer;
            renderer.voxy$shutdownRenderer();
            renderer.voxy$createRenderer();
            return true;
        } catch (Throwable t) {
            if (CreateManaIndustry.LOGGER.isDebugEnabled()) {
                CreateManaIndustry.LOGGER.debug("[Allvr] Voxy renderer refresh deferred", t);
            }
            return false;
        }
    }

    private static void switchSlab(long wantedSlab) {
        long previousSlab = slabId;
        slabId = wantedSlab;
        SECTION_QUEUE.clear();
        QUEUED_SECTIONS.clear();
        residentScanQueued = false;
        if (refreshRenderer()) {
            rendererRefreshPending = false;
            prewarmedSlab = wantedSlab;
            CreateManaIndustry.LOGGER.info("[Allvr] switched Voxy Y slab to {}", wantedSlab);
        } else {
            slabId = previousSlab;
            prewarmedSlab = Long.MIN_VALUE;
        }
    }

    private static void enqueueCubeSections(long cubeKey) {
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        for (int sy = 0; sy < 2; sy++) {
            for (int sz = 0; sz < 2; sz++) {
                for (int sx = 0; sx < 2; sx++) {
                    enqueueSection((cube.getX() << 1) + sx,
                        (cube.getY() << 1) + sy,
                        (cube.getZ() << 1) + sz);
                }
            }
        }
    }

    private static void enqueueSection(int sx, int sy, int sz) {
        if (!AllvrVoxyYSlab.containsSectionY(slabId, sy)) return;
        long key = SectionPos.asLong(sx, sy, sz);
        if (QUEUED_SECTIONS.add(key)) {
            SECTION_QUEUE.addLast(key);
        }
    }

    private static void ingestSection(me.cortex.voxy.common.world.WorldEngine engine,
                                      SectionPos absolute) {
        int virtualY = AllvrVoxyYSlab.virtualSectionY(slabId, absolute.getY());
        if (virtualY < AllvrVoxyYSlab.MIN_VIRTUAL_SECTION_Y
            || virtualY > AllvrVoxyYSlab.MAX_VIRTUAL_SECTION_Y) {
            return;
        }
        LevelChunkSection snapshot;
        synchronized (AllvrClientCubeCache.LOCK) {
            AllvrCube cube = AllvrClientCubeCache.peekCubeUnsafe(
                AllvrCubePos.asLong(absolute.getX() >> 1, absolute.getY() >> 1, absolute.getZ() >> 1));
            if (cube == null) return;
            int local = ((absolute.getY() & 1) << 2)
                | ((absolute.getZ() & 1) << 1) | (absolute.getX() & 1);
            LevelChunkSection source = cube.getSections()[local];
            if (source == null) return;
            snapshot = copySection(source);
        }
        DataLayer[] layers = AllvrSodiumSectionSource.lightData(level, absolute);
        DataLayer sky = layers == null || layers.length == 0 || layers[0] == null
            ? null : layers[0].copy();
        DataLayer block = layers == null || layers.length < 2 || layers[1] == null
            ? null : layers[1].copy();
        me.cortex.voxy.common.world.service.VoxelIngestService.rawIngest(
            engine, snapshot, absolute.getX(), virtualY, absolute.getZ(), block, sky);
    }

    private static LevelChunkSection copySection(LevelChunkSection source) {
        PalettedContainer<BlockState> states = source.getStates().copy();
        @SuppressWarnings("unchecked")
        PalettedContainer<Holder<net.minecraft.world.level.biome.Biome>> biomes =
            ((PalettedContainer<Holder<net.minecraft.world.level.biome.Biome>>)
                (PalettedContainer<?>) source.getBiomes()).copy();
        return new LevelChunkSection(states, biomes);
    }
}
