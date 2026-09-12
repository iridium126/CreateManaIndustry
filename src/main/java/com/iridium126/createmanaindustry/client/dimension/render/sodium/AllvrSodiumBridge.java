package com.iridium126.createmanaindustry.client.dimension.render.sodium;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderYWindow;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * Main-thread owner of ALLVR's Sodium section residency and revision gates.
 * Sodium still owns every build worker, mesh, render list and terrain draw;
 * this class only feeds it section keys and requests the normal lifecycle.
 */
public final class AllvrSodiumBridge {

    private static final AllvrRenderYWindow WINDOW = new AllvrRenderYWindow();
    private static final LongOpenHashSet VANILLA_COLUMNS = new LongOpenHashSet();
    private static final LongOpenHashSet OWNED = new LongOpenHashSet();
    private static final ArrayDeque<Long> ADD_QUEUE = new ArrayDeque<>();
    private static final ArrayDeque<Long> REMOVE_QUEUE = new ArrayDeque<>();
    private static final ArrayDeque<Long> REPLACE_QUEUE = new ArrayDeque<>();
    private static final ArrayDeque<Long> REBUILD_QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED_ADD = new HashSet<>();
    private static final Set<Long> QUEUED_REMOVE = new HashSet<>();
    private static final Set<Long> QUEUED_REPLACE = new HashSet<>();
    private static final Set<Long> QUEUED_REBUILD = new HashSet<>();
    /** Vanilla spreads section registration across client ticks; cap both
     * count and CPU time so a burst of cube packets cannot monopolise a frame. */
    private static final int SECTION_BUDGET = 128;
    private static final long SECTION_BUDGET_NANOS = 1_000_000L;
    private static final int REBUILD_BUDGET = 64;
    private static final long REBUILD_BUDGET_NANOS = 1_000_000L;

    private static volatile ClientLevel level;
    private static volatile long resourceRevision;
    private static volatile boolean initialized;
    /**
     * Voxy's Sodium CUTOUT hook runs inside RenderSectionManager#renderLayer
     * after that method's camera Y has been converted into the Sodium window.
     * Keep this scoped to the wrapped chunk-render call so Voxy can restore
     * the absolute camera before applying its own slab conversion.
     */
    private static final ThreadLocal<Integer> VOXY_WINDOW_CAMERA_DEPTH =
        ThreadLocal.withInitial(() -> 0);
    /** No cube event may be mapped until the first camera-centered origin is published. */
    private static boolean originInitialized;
    private static double lastCameraY;

    private AllvrSodiumBridge() {}

    public static AllvrRenderYWindow window() {
        return WINDOW;
    }

    public static ClientLevel level() {
        return level;
    }

    public static long resourceRevision() {
        return resourceRevision;
    }

    public static void enterVoxyWindowCameraFrame() {
        VOXY_WINDOW_CAMERA_DEPTH.set(VOXY_WINDOW_CAMERA_DEPTH.get() + 1);
    }

    public static void exitVoxyWindowCameraFrame() {
        int depth = VOXY_WINDOW_CAMERA_DEPTH.get() - 1;
        if (depth <= 0) {
            VOXY_WINDOW_CAMERA_DEPTH.remove();
        } else {
            VOXY_WINDOW_CAMERA_DEPTH.set(depth);
        }
    }

    public static boolean voxyWindowCameraFrameActive() {
        return VOXY_WINDOW_CAMERA_DEPTH.get() > 0;
    }

    public static boolean active() {
        return initialized && AllvrSodiumSectionSource.isAllay(level);
    }

    public static void bindLevel(ClientLevel newLevel) {
        clear();
        level = newLevel;
        if (newLevel == null || newLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        AllvrSodiumCompatibilityProbe.Availability availability =
            AllvrSodiumCompatibilityProbe.probe();
        if (!availability.available()) {
            throw new IllegalStateException("Sodium "
                + AllvrSodiumCompatibilityProbe.VERSION_PREFIX
                + " is required for Allay terrain, but its ABI probe failed: "
                + availability.reason());
        }
        initialized = true;
        resourceRevision++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == newLevel && mc.player != null) {
            lastCameraY = mc.gameRenderer.getMainCamera().getPosition().y;
            WINDOW.initializeAt(WINDOW.nextOrigin(lastCameraY));
            originInitialized = true;
            enqueueAllResidentCubes();
        }
    }

    public static void clear() {
        AllvrSodiumSectionSource.clear();
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager != null && active()) {
            for (long key : OWNED) {
                SectionPos pos = SectionPos.of(key);
                SodiumApi_0813_1211.onSectionRemoved(manager, pos.getX(), pos.getY(), pos.getZ());
            }
        }
        OWNED.clear();
        VANILLA_COLUMNS.clear();
        ADD_QUEUE.clear();
        REMOVE_QUEUE.clear();
        REPLACE_QUEUE.clear();
        REBUILD_QUEUE.clear();
        QUEUED_ADD.clear();
        QUEUED_REMOVE.clear();
        QUEUED_REPLACE.clear();
        QUEUED_REBUILD.clear();
        WINDOW.reset();
        initialized = false;
        originInitialized = false;
        lastCameraY = 0.0D;
        level = null;
    }

    public static void tick() {
        if (!active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            lastCameraY = mc.gameRenderer.getMainCamera().getPosition().y;
            if (!originInitialized) {
                // Packets can arrive before LevelEvent.Load has a usable
                // camera.  Discard any pre-init mapping and seed the whole
                // resident set against the real camera position instead.
                ADD_QUEUE.clear();
                REMOVE_QUEUE.clear();
                REPLACE_QUEUE.clear();
                REBUILD_QUEUE.clear();
                QUEUED_ADD.clear();
                QUEUED_REMOVE.clear();
                QUEUED_REPLACE.clear();
                QUEUED_REBUILD.clear();
                WINDOW.initializeAt(WINDOW.nextOrigin(lastCameraY));
                originInitialized = true;
                enqueueAllResidentCubes();
            }
            if (WINDOW.isSteady() && WINDOW.needsRebase(lastCameraY)) {
                WINDOW.beginDetach();
                for (long key : OWNED) {
                    enqueueRemove(key);
                }
            }
        }

        if (WINDOW.isDetaching()) {
            REPLACE_QUEUE.clear();
            QUEUED_REPLACE.clear();
            REBUILD_QUEUE.clear();
            QUEUED_REBUILD.clear();
            drainRemoves();
            if (REMOVE_QUEUE.isEmpty() && OWNED.isEmpty()) {
                WINDOW.publishMove(WINDOW.nextOrigin(lastCameraY));
                enqueueAllResidentCubes();
            }
            return;
        }

        drainRemoves();
        drainReplacements();
        drainAdds();
        drainRebuilds();
        if (WINDOW.phase() == AllvrRenderYWindow.Phase.REFILL
            && ADD_QUEUE.isEmpty() && REMOVE_QUEUE.isEmpty() && REBUILD_QUEUE.isEmpty()) {
            WINDOW.endRebase();
        }
    }

    public static void onCubeApplied(long cubeKey) {
        if (!active() || !originInitialized) {
            return;
        }
        AllvrSodiumSectionSource.invalidateCube(cubeKey);
        enqueueCubeSections(cubeKey, true);
        dirtyCubeBoundary(cubeKey);
    }

    public static void onCubeForgotten(long cubeKey) {
        if (!active() || !originInitialized) {
            return;
        }
        AllvrSodiumSectionSource.invalidateCube(cubeKey);
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        for (int sy = 0; sy < 2; sy++) {
            for (int sz = 0; sz < 2; sz++) {
                for (int sx = 0; sx < 2; sx++) {
                    int absoluteX = (cube.getX() << 1) + sx;
                    int absoluteY = (cube.getY() << 1) + sy;
                    int absoluteZ = (cube.getZ() << 1) + sz;
                    enqueueRemove(virtualKey(absoluteX, absoluteY, absoluteZ));
                }
            }
        }
    }

    public static void onBlockChanged(net.minecraft.core.BlockPos absolutePos,
                                      BlockState oldState, BlockState newState) {
        if (!active() || !originInitialized) {
            return;
        }
        int sx = absolutePos.getX() >> 4;
        int sy = absolutePos.getY() >> 4;
        int sz = absolutePos.getZ() >> 4;
        AllvrSodiumSectionSource.invalidateLightingSection(sx,
            WINDOW.virtualSectionY(sy), sz);
        enqueueCubeSections(AllvrCubePos.asLong(absolutePos), false);
        long key = virtualKey(sx, sy, sz);
        scheduleDirty(key);
        if ((absolutePos.getX() & 15) == 0) scheduleDirty(virtualKey(sx - 1, sy, sz));
        if ((absolutePos.getX() & 15) == 15) scheduleDirty(virtualKey(sx + 1, sy, sz));
        if ((absolutePos.getY() & 15) == 0) scheduleDirty(virtualKey(sx, sy - 1, sz));
        if ((absolutePos.getY() & 15) == 15) scheduleDirty(virtualKey(sx, sy + 1, sz));
        if ((absolutePos.getZ() & 15) == 0) scheduleDirty(virtualKey(sx, sy, sz - 1));
        if ((absolutePos.getZ() & 15) == 15) scheduleDirty(virtualKey(sx, sy, sz + 1));
    }

    public static void onResourceReload() {
        if (!active() || !originInitialized) {
            return;
        }
        resourceRevision++;
        for (long key : OWNED) {
            SectionPos pos = SectionPos.of(key);
            scheduleDirty(key);
            if (CreateManaIndustry.LOGGER.isDebugEnabled()) {
                CreateManaIndustry.LOGGER.debug("[Allvr] Sodium resource revision {} dirtied {}",
                    resourceRevision, pos);
            }
        }
    }

    /** Called by Sodium's normal column lifecycle; keep native keys near the chunk band. */
    public static boolean onVanillaSectionAdded(int x, int y, int z) {
        VANILLA_COLUMNS.add(net.minecraft.world.level.ChunkPos.asLong(x, z));
        if (WINDOW.originBlockY() == 0 && !WINDOW.isDetaching()) {
            OWNED.add(SectionPos.asLong(x, y, z));
            return false;
        }
        int virtualY = WINDOW.virtualSectionY(y);
        if (virtualY >= -8192 && virtualY < 8192 && !WINDOW.isDetaching()) {
            enqueueAdd(SectionPos.asLong(x, virtualY, z));
        }
        return true;
    }

    public static boolean onVanillaSectionRemoved(int x, int y, int z) {
        VANILLA_COLUMNS.remove(net.minecraft.world.level.ChunkPos.asLong(x, z));
        if (WINDOW.originBlockY() == 0) {
            OWNED.remove(SectionPos.asLong(x, y, z));
            return false;
        }
        int virtualY = WINDOW.virtualSectionY(y);
        if (virtualY >= -8192 && virtualY < 8192) enqueueRemove(SectionPos.asLong(x, virtualY, z));
        return true;
    }

    private static void enqueueAllResidentCubes() {
        for (long column : VANILLA_COLUMNS) {
            int x = net.minecraft.world.level.ChunkPos.getX(column);
            int z = net.minecraft.world.level.ChunkPos.getZ(column);
            for (int y = -8; y < 24; y++) {
                int virtualY = WINDOW.virtualSectionY(y);
                if (virtualY >= -8192 && virtualY < 8192) enqueueAdd(SectionPos.asLong(x, virtualY, z));
            }
        }
        for (long cubeKey : AllvrClientCubeCache.cubeKeys()) {
            enqueueCubeSections(cubeKey, false);
        }
    }

    private static void enqueueCubeSections(long cubeKey, boolean replaceExisting) {
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        boolean register = AllvrSodiumSectionSource.cubeIsLoaded(level, cubeKey);
        for (int sy = 0; sy < 2; sy++) {
            for (int sz = 0; sz < 2; sz++) {
                for (int sx = 0; sx < 2; sx++) {
                    int absX = (cube.getX() << 1) + sx;
                    int absY = (cube.getY() << 1) + sy;
                    int absZ = (cube.getZ() << 1) + sz;
                    long key = virtualKey(absX, absY, absZ);
                    SectionPos virtualPos = SectionPos.of(key);
                    if (register) {
                        if (!OWNED.contains(key)) {
                            enqueueAdd(key);
                        } else if (replaceExisting) {
                            enqueueReplace(key);
                        } else if (AllvrSodiumSectionSource.hasContent(level, absX,
                            WINDOW.virtualSectionY(absY), absZ,
                            resourceRevision, WINDOW.epoch())) {
                            RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
                            if (manager != null && SodiumApi_0813_1211.isSectionBuilt(manager,
                                virtualPos.getX(), virtualPos.getY(), virtualPos.getZ())) {
                                enqueueRebuild(key);
                            } else {
                                enqueueReplace(key);
                            }
                        } else {
                            RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
                            if (manager != null && SodiumApi_0813_1211.isSectionBuilt(manager,
                                virtualPos.getX(), virtualPos.getY(), virtualPos.getZ())) {
                                // A formerly solid section must be rebuilt to
                                // publish Sodium's EMPTY built-info state.
                                enqueueRebuild(key);
                            }
                        }
                    } else {
                        enqueueRemove(key);
                    }
                }
            }
        }
    }

    private static void dirtyCubeBoundary(long cubeKey) {
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        for (int sy = -1; sy <= 2; sy++) {
            for (int sz = -1; sz <= 2; sz++) {
                for (int sx = -1; sx <= 2; sx++) {
                    if (sx >= 0 && sx < 2 && sy >= 0 && sy < 2 && sz >= 0 && sz < 2) continue;
                    scheduleExistingDirty(virtualKey((cube.getX() << 1) + sx,
                        (cube.getY() << 1) + sy, (cube.getZ() << 1) + sz));
                }
            }
        }
    }

    private static long virtualKey(int absoluteSectionX, int absoluteSectionY, int absoluteSectionZ) {
        return SectionPos.asLong(absoluteSectionX,
            WINDOW.virtualSectionY(absoluteSectionY), absoluteSectionZ);
    }

    private static void scheduleDirty(long key) {
        if (WINDOW.isDetaching()) {
            return;
        }
        SectionPos pos = SectionPos.of(key);
        AllvrSodiumSectionSource.invalidateSection(pos.getX(), pos.getY(), pos.getZ());
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager == null) {
            return;
        }
        if (AllvrSodiumSectionSource.shouldRegisterSection(level, pos,
            resourceRevision, WINDOW.epoch())) {
            if (!OWNED.contains(key)) {
                enqueueAdd(key);
            } else if (AllvrSodiumSectionSource.hasContent(level, pos,
                resourceRevision, WINDOW.epoch())) {
                if (SodiumApi_0813_1211.isSectionBuilt(manager,
                    pos.getX(), pos.getY(), pos.getZ())) {
                    enqueueRebuild(key);
                } else {
                    enqueueReplace(key);
                }
            } else if (SodiumApi_0813_1211.isSectionBuilt(manager,
                pos.getX(), pos.getY(), pos.getZ())) {
                // A formerly solid section must be rebuilt to publish
                // Sodium's EMPTY built-info state.
                enqueueRebuild(key);
            }
        } else {
            enqueueRemove(key);
        }
    }

    private static void enqueueAdd(long key) {
        if (OWNED.contains(key) || !QUEUED_ADD.add(key)) return;
        ADD_QUEUE.add(key);
    }

    private static void enqueueRemove(long key) {
        if ((!OWNED.contains(key) && !QUEUED_ADD.contains(key)) || !QUEUED_REMOVE.add(key)) return;
        REMOVE_QUEUE.add(key);
    }

    private static void enqueueReplace(long key) {
        if (!OWNED.contains(key) || !QUEUED_REPLACE.add(key)) return;
        REPLACE_QUEUE.add(key);
    }

    private static void enqueueRebuild(long key) {
        if (OWNED.contains(key) && QUEUED_REBUILD.add(key)) {
            REBUILD_QUEUE.add(key);
        }
    }

    /** Boundary invalidation cannot create a new render section. New
     * sections are admitted by enqueueCubeSections or the direct block-write
     * path; skip the expensive content/lock check for absent neighbours. */
    private static void scheduleExistingDirty(long key) {
        if (!OWNED.contains(key) && !QUEUED_ADD.contains(key)) {
            return;
        }
        scheduleDirty(key);
    }

    private static void drainAdds() {
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager == null) return;
        int budget = SECTION_BUDGET;
        long deadline = System.nanoTime() + SECTION_BUDGET_NANOS;
        int processed = 0;
        while (budget-- > 0 && !ADD_QUEUE.isEmpty()
            && (processed == 0 || System.nanoTime() < deadline)) {
            long key = ADD_QUEUE.removeFirst();
            QUEUED_ADD.remove(key);
            if (!AllvrSodiumSectionSource.shouldRegisterSection(level, SectionPos.of(key),
                resourceRevision, WINDOW.epoch())) continue;
            SectionPos pos = SectionPos.of(key);
            SodiumApi_0813_1211.onSectionRemoved(manager, pos.getX(), pos.getY(), pos.getZ());
            AllvrSodiumSectionLifecycle.nativeAdd(manager, pos.getX(), pos.getY(), pos.getZ());
            OWNED.add(key);
            processed++;
        }
    }

    /** Replaces an existing node when an unbuilt/queued section changed. */
    private static void drainReplacements() {
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager == null) return;
        int budget = SECTION_BUDGET;
        while (budget-- > 0 && !REPLACE_QUEUE.isEmpty()) {
            long key = REPLACE_QUEUE.removeFirst();
            QUEUED_REPLACE.remove(key);
            if (!OWNED.contains(key)) continue;
            SectionPos pos = SectionPos.of(key);
            SodiumApi_0813_1211.onSectionRemoved(manager, pos.getX(), pos.getY(), pos.getZ());
            OWNED.remove(key);
            if (AllvrSodiumSectionSource.shouldRegisterSection(level, pos,
                resourceRevision, WINDOW.epoch())) {
                enqueueAdd(key);
            }
        }
    }

    private static void drainRemoves() {
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager == null) return;
        int budget = SECTION_BUDGET;
        while (budget-- > 0 && !REMOVE_QUEUE.isEmpty()) {
            long key = REMOVE_QUEUE.removeFirst();
            QUEUED_REMOVE.remove(key);
            SectionPos pos = SectionPos.of(key);
            SodiumApi_0813_1211.onSectionRemoved(manager, pos.getX(), pos.getY(), pos.getZ());
            OWNED.remove(key);
            QUEUED_ADD.remove(key);
            QUEUED_REPLACE.remove(key);
            QUEUED_REBUILD.remove(key);
        }
    }

    /** Coalesced neighbour invalidations are fed to Sodium gradually. */
    private static void drainRebuilds() {
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager == null) return;
        int budget = REBUILD_BUDGET;
        long deadline = System.nanoTime() + REBUILD_BUDGET_NANOS;
        int processed = 0;
        while (budget-- > 0 && !REBUILD_QUEUE.isEmpty()
            && (processed == 0 || System.nanoTime() < deadline)) {
            long key = REBUILD_QUEUE.removeFirst();
            QUEUED_REBUILD.remove(key);
            if (!OWNED.contains(key)) continue;
            SectionPos pos = SectionPos.of(key);
            if (AllvrSodiumSectionSource.shouldRegisterSection(level, pos,
                resourceRevision, WINDOW.epoch())) {
                SodiumApi_0813_1211.scheduleRebuild(manager, pos.getX(), pos.getY(), pos.getZ(), true);
            } else {
                enqueueRemove(key);
            }
            processed++;
        }
    }

}
