package com.iridium126.createmanaindustry.client.dimension;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.lod.AllvrLodBackendManager;
import com.iridium126.createmanaindustry.client.dimension.render.AllvrRenderer;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodBands;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodPos;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionCodec;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodBitmapPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodForgetPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodSectionPacket;
import com.iridium126.createmanaindustry.dimension.net.ServerboundAllvrLodRequestPacket;

/**
 * Client half of the LOD pipeline (sodium-parity plan §6.3): holds the
 * per-level surface-node bitmaps streamed by the server, walks them once per
 * client tick to issue batched section requests, and routes the answers to
 * the active backend (Voxy or disabled — there is no other). The request gate
 * looks only at the backend state (voxy active, rebase not freezing); no GPU
 * capability is consulted. A backend switch clears the pending/resident
 * bookkeeping so the walk refills.
 * <p>
 * Nodes whose cells fall inside the full-resolution streaming radius
 * (Chebyshev 8 cubes) are never requested, and requests are additionally
 * cropped to the fixed active vertical radius. All apply/forget/tick run on
 * the main thread, which is the render thread.
 */
public final class AllvrLodClientState {

    /** Section requests sent per tick at throttle scale 1.0 (grilling Q5). */
    private static final int REQUESTS_PER_TICK = 64;
    /** Per-level in-flight cap. */
    private static final int MAX_PENDING = 256;
    private static final class LevelState {
        int originX;
        int originY;
        int originZ;
        int dim;
        long[] words;
    }

    private static final LevelState[] levels = new LevelState[4];
    private static final LongOpenHashSet[] pending = new LongOpenHashSet[4];
    /**
     * Sections the active backend accepted ("resident" — the voxy backend owns
     * the node; plan §6.3 renames the old "meshed" since no ALLVR mesh is
     * implied). Rejected publishes stay out, so the walk re-issues them.
     */
    private static final LongOpenHashSet[] resident = new LongOpenHashSet[4];
    /** All-air responses have no backend node, but are still completed
     *  requests and must not be re-issued every tick. */
    private static final LongOpenHashSet[] empty = new LongOpenHashSet[4];
    private static boolean loggedFirstBitmap;

    static {
        for (int i = 0; i < 4; i++) {
            pending[i] = new LongOpenHashSet();
            resident[i] = new LongOpenHashSet();
            empty[i] = new LongOpenHashSet();
        }
    }

    // ------------------------------------------------------------------
    // packet application (main thread)
    // ------------------------------------------------------------------

    public static void applyBitmap(ClientboundAllvrLodBitmapPacket packet) {
        if (!inDimension()) {
            return; // level switched — the bitmap died with it
        }
        int lvl = packet.level();
        if (lvl < 0 || lvl > AllvrLodPos.MAX_LEVEL) {
            return;
        }
        if (packet.dimCells() <= 0 || packet.words() == null || packet.words().length == 0) {
            clearLevel(lvl);
            return;
        }
        // structural checks (sodium-parity plan §7.1): the box must stay in
        // the server-generated range (≤ 32³ cells, band table §6.2) and the
        // word array must cover exactly dim³ bits — a short word array would
        // crash the walk's isSurface read, an oversized one is garbage.
        int dim = packet.dimCells();
        long cells = (long) dim * dim * dim;
        if (dim > 64 || packet.words().length != (int) ((cells + 63) >>> 6)) {
            CreateManaIndustry.LOGGER.warn(
                "[Allvr] malformed LOD bitmap for L{} (dim={}, words={}) — dropped",
                lvl, dim, packet.words().length);
            return;
        }
        LevelState state = new LevelState();
        state.originX = packet.originCellX();
        state.originY = packet.originCellY();
        state.originZ = packet.originCellZ();
        state.dim = packet.dimCells();
        state.words = packet.words();
        levels[lvl] = state;
        if (!loggedFirstBitmap) {
            loggedFirstBitmap = true;
            CreateManaIndustry.LOGGER.info(
                "[Allvr] LOD bitmap L{}: box {}³ cells at ({},{},{}) — {} surface nodes",
                lvl, state.dim, state.originX, state.originY, state.originZ, countBits(state));
        }
    }

    public static void applySection(ClientboundAllvrLodSectionPacket packet) {
        if (!inDimension()) {
            return;
        }
        int lvl = packet.level();
        if (lvl < 0 || lvl > AllvrLodPos.MAX_LEVEL) {
            return;
        }
        // A response can race an eviction, a forget, or a zero-dimension
        // bitmap. Only publish sections for requests still owned by this
        // level; otherwise a late payload would resurrect inner-band nodes.
        if (levels[lvl] == null) {
            AllvrLodBackendManager.forget(lvl, packet.cellLong());
            return;
        }
        if (!pending[lvl].remove(packet.cellLong())) {
            return; // duplicate, stale, or an old-epoch re-send — dropped
        }
        AllvrLodSectionData data;
        try {
            data = AllvrLodSectionCodec.decode(lvl, packet.cellLong(), packet.generation(),
                packet.payload());
        } catch (Exception e) {
            CreateManaIndustry.LOGGER.warn("[Allvr] malformed LOD section payload for {}",
                AllvrLodPos.fromCellLong(lvl, packet.cellLong()), e);
            return; // pending already consumed — the walk re-requests fresh
        }
        if (data == null) {
            // format=0 is the valid all-air response, not a section that can
            // be injected into Voxy. Clear any old backend node as well: an
            // all-air rebuild may race a previous resident payload.
            if (resident[lvl].remove(packet.cellLong())) {
                AllvrLodBackendManager.forget(lvl, packet.cellLong());
            }
            empty[lvl].add(packet.cellLong());
            return;
        }
        empty[lvl].remove(packet.cellLong());
        // the biome rides the injection; sample it here on the main thread
        // (the writer thread must not touch the level)
        Minecraft mc = Minecraft.getInstance();
        AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, packet.cellLong());
        BlockPos center = new BlockPos(
            pos.minBlockX() + pos.stride() * 15,
            pos.minBlockY() + pos.stride() * 15,
            pos.minBlockZ() + pos.stride() * 15);
        net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome =
            mc.level.getBiome(center);
        if (AllvrLodBackendManager.apply(data, biome)) {
            resident[lvl].add(packet.cellLong());
        }
    }

    public static void applyForget(ClientboundAllvrLodForgetPacket packet) {
        if (!inDimension()) {
            return;
        }
        int lvl = packet.level();
        if (lvl < 0 || lvl > AllvrLodPos.MAX_LEVEL) {
            return;
        }
        pending[lvl].remove(packet.cellLong());
        resident[lvl].remove(packet.cellLong());
        empty[lvl].remove(packet.cellLong());
        AllvrLodBackendManager.forget(lvl, packet.cellLong());
    }

    /** Drops all LOD state (level unload / dimension switch / logout). */
    public static void clear() {
        for (int i = 0; i < 4; i++) {
            clearLevel(i);
        }
        AllvrLodBackendManager.leave();
        loggedFirstBitmap = false;
    }

    /** Level join: binds the backend manager to the new client level. */
    public static void onLevelChanged(net.minecraft.client.multiplayer.ClientLevel level) {
        AllvrLodBackendManager.enter(level);
    }

    /** Config reload: re-runs the backend selection for the current level. */
    public static void onConfigReloaded() {
        Minecraft mc = Minecraft.getInstance();
        AllvrLodBackendManager.reselect(mc.level);
        // a backend switch invalidates the request bookkeeping — the walk
        // re-issues every node through the new backend
        for (int i = 0; i < 4; i++) {
            pending[i].clear();
            resident[i].clear();
            empty[i].clear();
        }
    }

    // ------------------------------------------------------------------
    // per-tick request walk (main thread)
    // ------------------------------------------------------------------

    public static void tick() {
        if (!farTerrainEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != AllvrDimensions.ALLAY_LEVEL || mc.player == null) {
            return;
        }
        if (!AllvrLodBackendManager.requestsOpen()) {
            // near-only (voxy missing, disabled, or failed): no new requests,
            // pending drained, no legacy retry (sodium-parity plan §6.3)
            for (int lvl = 0; lvl <= AllvrLodPos.MAX_LEVEL; lvl++) {
                if (!pending[lvl].isEmpty() || !resident[lvl].isEmpty() || !empty[lvl].isEmpty()) {
                    clearLevel(lvl);
                }
            }
            return;
        }
        AllvrLodBackendManager.tick(
            mc.gameRenderer.getMainCamera().getPosition().x,
            mc.gameRenderer.getMainCamera().getPosition().y,
            mc.gameRenderer.getMainCamera().getPosition().z);
        BlockPos player = mc.player.blockPosition();
        // frame-time EMA throttle (4c-2): the inflow of NEW requests scales
        // down while frames run hot and recovers when healthy; the per-level
        // in-flight cap is unchanged, so a throttle can never strand pending
        // entries — they just complete slower
        int perTick = (int) Math.max(1,
            Math.round(REQUESTS_PER_TICK * AllvrRenderer.INSTANCE.lodRequestScale()));
        List<long[]> entries = new ArrayList<>();
        if (AllvrLodBackendManager.refillMode()) {
            // rebase REFILL: coarsest levels first so the horizon fills rough
            for (int lvl = AllvrLodPos.MAX_LEVEL; lvl >= 0; lvl--) {
                LevelState state = levels[lvl];
                if (state != null && state.words != null) {
                    walkLevel(lvl, state, player, entries, perTick);
                }
            }
        } else {
            for (int lvl = 0; lvl <= AllvrLodPos.MAX_LEVEL; lvl++) {
                LevelState state = levels[lvl];
                if (state == null || state.words == null) {
                    continue;
                }
                walkLevel(lvl, state, player, entries, perTick);
            }
        }
        // the request packet carries at most 16 entries — flush in chunks
        for (int i = 0; i < entries.size(); i += ServerboundAllvrLodRequestPacket.MAX_ENTRIES) {
            int end = Math.min(entries.size(), i + ServerboundAllvrLodRequestPacket.MAX_ENTRIES);
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                ServerboundAllvrLodRequestPacket.of(
                    ServerboundAllvrLodRequestPacket.CAPABILITY_VOXEL_SECTION,
                    entries.subList(i, end)));
        }
    }

    /**
     * Master gate for the far-terrain half (§6.1 precedence): the legacy
     * {@code allvrLod} boolean survives as the master switch — {@code false}
     * wins over every backend mode, and {@code lodBackend=OFF} is equivalent.
     */
    private static boolean farTerrainEnabled() {
        // unique precedence (sodium-parity plan §6.1): the master boolean kills
        // every backend mode, and lodBackend=OFF is equivalent to it
        return ClientConfig.allvrLod && ClientConfig.allvrLodBackend != ClientConfig.AllvrLodBackendMode.OFF;
    }

    private static void walkLevel(int lvl, LevelState state, BlockPos player, List<long[]> entries,
                                  int perTick) {
        int half = state.dim >> 1;
        int playerCellX = player.getX() >> (5 + lvl);
        int playerCellY = player.getY() >> (5 + lvl);
        int playerCellZ = player.getZ() >> (5 + lvl);
        // Nodes fully outside the full-resolution streaming radius begin at the
        // first cell after the fixed band boundary; the active vertical window
        // additionally crops the walk (plan §5.2).
        int minDist = AllvrLodBands.bandMin(lvl) / AllvrLodBands.cellBlocks(lvl) + 1;
        int verticalLimit = AllvrLodBands.activeVerticalCells(lvl);

        // Evict before the budget/pending early-outs so stale inner-band nodes
        // cannot survive indefinitely when the request queue is full.
        evictFar(lvl, playerCellX, playerCellY, playerCellZ, half, minDist, verticalLimit);

        int budget = perTick - entries.size();
        if (budget <= 0 || pending[lvl].size() >= MAX_PENDING) {
            return;
        }

        // iterate the bitmap box but measure distance from the PLAYER's cell,
        // not the box center — the box lags the player by up to the resend
        // threshold, and box-relative distance would request nodes inside the
        // full-res zone (duplicate geometry) after the player walks toward them
        for (int cy = state.originY; cy < state.originY + state.dim && budget > 0; cy++) {
            int dy = cy - playerCellY;
            if (Math.abs(dy) > verticalLimit) {
                continue; // outside the active vertical window (§5.2)
            }
            for (int cz = state.originZ; cz < state.originZ + state.dim && budget > 0; cz++) {
                int dz = cz - playerCellZ;
                for (int cx = state.originX; cx < state.originX + state.dim && budget > 0; cx++) {
                    int dx = cx - playerCellX;
                    int dist = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                    if (dist < minDist || dist > half) {
                        continue; // full-res territory, or beyond the band
                    }
                    if (!isSurface(state, cx - state.originX, cy - state.originY, cz - state.originZ)) {
                        continue;
                    }
                    long cellLong = AllvrCubePos.asLong(cx, cy, cz);
                    if (resident[lvl].contains(cellLong) || pending[lvl].contains(cellLong)
                        || empty[lvl].contains(cellLong)) {
                        continue;
                    }
                    pending[lvl].add(cellLong);
                    entries.add(new long[] {lvl, cellLong});
                    budget--;
                    if (pending[lvl].size() >= MAX_PENDING) {
                        return;
                    }
                }
            }
        }
    }

    /** Drops resident, pending, and known-empty nodes outside the box or
     *  inside the full-res zone, and crops vertically to the active window
     *  (plan §5.2). */
    private static void evictFar(int lvl, int pcx, int pcy, int pcz, int half, int minDist,
                                 int verticalLimit) {
        int limit = half + Math.max(1, half >> 2);
        int vertical = Math.min(AllvrLodBands.verticalEvictCells(lvl, viewDistanceBlocks()), verticalLimit);
        evictSet(lvl, resident[lvl], pcx, pcy, pcz, limit, minDist, vertical, true);
        evictSet(lvl, pending[lvl], pcx, pcy, pcz, limit, minDist, vertical, false);
        evictSet(lvl, empty[lvl], pcx, pcy, pcz, limit, minDist, vertical, false);
    }

    private static int viewDistanceBlocks() {
        return com.iridium126.createmanaindustry.config.ServerConfig.allvrLodDistance;
    }

    private static void evictSet(int lvl, LongOpenHashSet set, int pcx, int pcy, int pcz, int limit,
                                 int minDist, int vertical, boolean accepted) {
        if (set.isEmpty()) {
            return;
        }
        var it = set.iterator();
        List<Long> removed = null;
        while (it.hasNext()) {
            long cellLong = it.nextLong();
            AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, cellLong);
            int d = Math.max(Math.abs(pos.cellX() - pcx),
                Math.max(Math.abs(pos.cellY() - pcy), Math.abs(pos.cellZ() - pcz)));
            int dy = Math.abs(pos.cellY() - pcy);
            if (d > limit || d < minDist || dy > vertical) {
                if (removed == null) {
                    removed = new ArrayList<>();
                }
                removed.add(cellLong);
                if (accepted) {
                    AllvrLodBackendManager.forget(lvl, cellLong);
                }
            }
        }
        if (removed != null) {
            set.removeAll(removed);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** Full-resolution streaming extent incl. the forget hysteresis (8 send
     *  cubes + 2 hysteresis) — the fog-end target of the near-only state. */
    private static final float FULL_RES_EXTENT_BLOCKS = 320.0f;
    /** Far-terrain extent assumed before the first L3 bitmap lands (the
     *  default allvrLodDistance; the bitmap box corrects it once streamed). */
    private static final float DEFAULT_LOD_EXTENT_BLOCKS = 2048.0f;

    /**
     * Blocks of visible terrain the allay dimension's fog should cover (the
     * fog-end target consumed by {@code AllvrFogRendererMixin}). The far
     * radius is only reported while the voxy backend is actually active —
     * near-only fog ends at the real near coverage instead of advertising a
     * far horizon nothing renders (sodium-parity plan §6.3).
     */
    public static float viewExtentBlocks() {
        if (!farTerrainEnabled() || !AllvrLodBackendManager.farTerrainActive()) {
            return FULL_RES_EXTENT_BLOCKS;
        }
        LevelState l3 = levels[3];
        if (l3 != null && l3.dim > 0) {
            return (l3.dim >> 1) * (float) AllvrLodBands.cellBlocks(3);
        }
        return DEFAULT_LOD_EXTENT_BLOCKS;
    }

    private static boolean isSurface(LevelState state, int ix, int iy, int iz) {
        int bit = (iy * state.dim + iz) * state.dim + ix;
        return (state.words[bit >> 6] & (1L << (bit & 63))) != 0;
    }

    private static int countBits(LevelState state) {
        int n = 0;
        for (long w : state.words) {
            n += Long.bitCount(w);
        }
        return n;
    }

    private static boolean inDimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == AllvrDimensions.ALLAY_LEVEL;
    }

    /** Clears one level and releases any backend nodes belonging to it. */
    private static void clearLevel(int lvl) {
        for (long cellLong : resident[lvl]) {
            AllvrLodBackendManager.forget(lvl, cellLong);
        }
        levels[lvl] = null;
        pending[lvl].clear();
        resident[lvl].clear();
        empty[lvl].clear();
    }

    private AllvrLodClientState() {}
}
