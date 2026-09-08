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

    /** Developer-only migration switch; never exposed as a normal config. */
    private static final boolean LEGACY_ROLLBACK =
        Boolean.getBoolean("createmanaindustry.legacyAllvrTerrain");
    private static final AllvrRenderYWindow WINDOW = new AllvrRenderYWindow();
    private static final LongOpenHashSet OWNED = new LongOpenHashSet();
    private static final ArrayDeque<Long> ADD_QUEUE = new ArrayDeque<>();
    private static final ArrayDeque<Long> REMOVE_QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED_ADD = new HashSet<>();
    private static final Set<Long> QUEUED_REMOVE = new HashSet<>();
    private static final int SECTION_BUDGET = 128;

    private static volatile ClientLevel level;
    private static volatile long resourceRevision;
    private static volatile boolean initialized;
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

    public static boolean active() {
        return !LEGACY_ROLLBACK && initialized && AllvrSodiumSectionSource.isAllay(level);
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
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager != null && active()) {
            for (long key : OWNED) {
                SectionPos pos = SectionPos.of(key);
                SodiumApi_0813_1211.onSectionRemoved(manager, pos.getX(), pos.getY(), pos.getZ());
            }
        }
        OWNED.clear();
        ADD_QUEUE.clear();
        REMOVE_QUEUE.clear();
        QUEUED_ADD.clear();
        QUEUED_REMOVE.clear();
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
                QUEUED_ADD.clear();
                QUEUED_REMOVE.clear();
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
            drainRemoves();
            if (REMOVE_QUEUE.isEmpty() && OWNED.isEmpty()) {
                WINDOW.publishMove(WINDOW.nextOrigin(lastCameraY));
                enqueueAllResidentCubes();
            }
            return;
        }

        drainRemoves();
        drainAdds();
        if (WINDOW.phase() == AllvrRenderYWindow.Phase.REFILL
            && ADD_QUEUE.isEmpty() && REMOVE_QUEUE.isEmpty()) {
            WINDOW.endRebase();
        }
    }

    public static void onCubeApplied(long cubeKey) {
        if (!active() || !originInitialized) {
            return;
        }
        enqueueCubeSections(cubeKey);
        dirtyCubeBoundary(cubeKey);
    }

    public static void onCubeForgotten(long cubeKey) {
        if (!active() || !originInitialized) {
            return;
        }
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

    private static void enqueueAllResidentCubes() {
        for (long cubeKey : AllvrClientCubeCache.cubeKeys()) {
            enqueueCubeSections(cubeKey);
        }
    }

    private static void enqueueCubeSections(long cubeKey) {
        AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
        for (int sy = 0; sy < 2; sy++) {
            for (int sz = 0; sz < 2; sz++) {
                for (int sx = 0; sx < 2; sx++) {
                    int absX = (cube.getX() << 1) + sx;
                    int absY = (cube.getY() << 1) + sy;
                    int absZ = (cube.getZ() << 1) + sz;
                    long key = virtualKey(absX, absY, absZ);
                    if (AllvrSodiumSectionSource.hasContent(level, SectionPos.of(
                        absX, WINDOW.virtualSectionY(absY), absZ),
                        resourceRevision, WINDOW.epoch())) {
                        enqueueAdd(key);
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
                    scheduleDirty(virtualKey((cube.getX() << 1) + sx,
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
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager == null) {
            return;
        }
        if (AllvrSodiumSectionSource.hasContent(level, pos, resourceRevision, WINDOW.epoch())) {
            if (!OWNED.contains(key)) {
                enqueueAdd(key);
            } else {
                SodiumApi_0813_1211.scheduleRebuild(manager, pos.getX(), pos.getY(), pos.getZ(), true);
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

    private static void drainAdds() {
        RenderSectionManager manager = SodiumApi_0813_1211.sectionManager();
        if (manager == null) return;
        int budget = SECTION_BUDGET;
        while (budget-- > 0 && !ADD_QUEUE.isEmpty()) {
            long key = ADD_QUEUE.removeFirst();
            QUEUED_ADD.remove(key);
            if (!AllvrSodiumSectionSource.hasContent(level, SectionPos.of(key), resourceRevision, WINDOW.epoch())) continue;
            SectionPos pos = SectionPos.of(key);
            SodiumApi_0813_1211.onSectionAdded(manager, pos.getX(), pos.getY(), pos.getZ());
            OWNED.add(key);
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
        }
    }

    /** Called by the RenderSectionManager mixin around its native add method. */
    public static void nativeSectionAdd(RenderSectionManager manager, int x, int y, int z) {
        AllvrSodiumSectionLifecycle.nativeAdd(manager, x, y, z);
    }
}
