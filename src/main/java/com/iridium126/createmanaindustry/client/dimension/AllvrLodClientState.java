package com.iridium126.createmanaindustry.client.dimension;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.lod.AllvrLodBackendManager;
import com.iridium126.createmanaindustry.config.ClientConfig;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodBands;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodPos;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionCodec;
import com.iridium126.createmanaindustry.dimension.lod.AllvrLodSectionData;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodBitmapPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodForgetPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodGroupPacket;
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
 * Nodes whose geometry is inside Minecraft's effective render distance are
 * never requested here because Sodium owns that near terrain. Every LOD level
 * outside that seam remains available to Voxy; Voxy owns the screen-space
 * choice of which level to display. Requests are additionally cropped to the
 * fixed active vertical radius. All apply/forget/tick run on the main thread,
 * which is the render thread.
 */
public final class AllvrLodClientState {

    /** New section requests sent per client tick. */
    private static final int REQUESTS_PER_TICK = 64;
    /** Per-level in-flight cap. */
    private static final int MAX_PENDING = 256;
    /** A lost response must not occupy a pending slot forever. */
    private static final long REQUEST_TIMEOUT_TICKS = 100L;
    private static final class LevelState {
        int originX;
        int originY;
        int originZ;
        int dim;
        long[] words;
    }

    private static final LevelState[] levels = new LevelState[4];
    private record PendingTicket(long sessionEpoch, long requestId, long deadlineTick) {}

    private static final Long2ObjectOpenHashMap<PendingTicket>[] pending = new Long2ObjectOpenHashMap[4];
    /**
     * Sections the active backend accepted ("resident" — the voxy backend owns
     * the node; plan §6.3 renames the old "meshed" since no ALLVR mesh is
     * implied). Rejected publishes stay out, so the walk re-issues them.
     */
    private static final LongOpenHashSet[] resident = new LongOpenHashSet[4];
    /** All-air responses have no backend node, but are still completed
     *  requests and must not be re-issued every tick. */
    private static final LongOpenHashSet[] empty = new LongOpenHashSet[4];
    /** Per-node retry backoff after a terminal retryable server failure. */
    private static final Long2LongOpenHashMap[] retryAtTick = new Long2LongOpenHashMap[4];
    private static long sessionEpoch = 1L;
    private static long nextRequestId = 1L;
    private static long clientTick;
    private static Boolean lastSubscription;
    private static int lastRenderDistanceChunks = Integer.MIN_VALUE;
    private static boolean loggedFirstBitmap;

    static {
        for (int i = 0; i < 4; i++) {
            pending[i] = new Long2ObjectOpenHashMap<>();
            resident[i] = new LongOpenHashSet();
            empty[i] = new LongOpenHashSet();
            retryAtTick[i] = new Long2LongOpenHashMap();
            retryAtTick[i].defaultReturnValue(0L);
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
        // Structural checks (sodium-parity plan §7.1): the box must stay in
        // the bounded server-generated range and the word array must cover
        // exactly dim³ bits — a short word array would crash the walk's
        // surface read, an oversized one is garbage.
        int dim = packet.dimCells();
        long cells = (long) dim * dim * dim;
        if (dim > AllvrLodBands.MAX_BITMAP_DIM
            || packet.words().length != (int) ((cells + 63) >>> 6)) {
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
        // level; otherwise a late payload would resurrect nodes now owned by
        // Sodium's near terrain pass.
        if (levels[lvl] == null) {
            AllvrLodBackendManager.forget(lvl, packet.cellLong());
            return;
        }
        PendingTicket ticket = pending[lvl].get(packet.cellLong());
        if (ticket == null || ticket.sessionEpoch() != packet.sessionEpoch()
            || ticket.requestId() != packet.requestId()) {
            return; // duplicate, stale, or an old-epoch re-send — dropped
        }
        pending[lvl].remove(packet.cellLong());
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

    /** Applies a VoxyMP-style grouped response through the existing ticketed
     * section boundary.  Group transport is deliberately decoded before the
     * backend sees anything, so stale cells retain the same request/epoch
     * checks as single-section responses. */
    public static void applyGroup(ClientboundAllvrLodGroupPacket packet) {
        if (!inDimension() || packet.protocolVersion() != ClientboundAllvrLodGroupPacket.PROTOCOL_VERSION) {
            return;
        }
        for (ClientboundAllvrLodGroupPacket.Group group : packet.groups()) {
            if (group.level() < 0 || group.level() > AllvrLodPos.MAX_LEVEL
                || group.includedMask() == 0) {
                continue;
            }
            int expected = Integer.bitCount(group.includedMask());
            if (expected != group.entries().size()) {
                CreateManaIndustry.LOGGER.warn("[Allvr] malformed LOD group mask at ({},{},{})",
                    group.originX(), group.originY(), group.originZ());
                continue;
            }
            for (ClientboundAllvrLodGroupPacket.Entry entry : group.entries()) {
                int local = entry.localIndex();
                int x = group.originX() + (local & 1);
                int z = group.originZ() + ((local >>> 1) & 1);
                int y = group.originY() + ((local >>> 2) & 1);
                long cellLong = AllvrCubePos.asLong(x, y, z);
                applySection(new ClientboundAllvrLodSectionPacket(
                    ClientboundAllvrLodSectionPacket.PROTOCOL_VERSION,
                    packet.sessionEpoch(), entry.requestId(), group.level(), cellLong,
                    entry.generation(), entry.payload()));
            }
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
        if (packet.requestId() != ClientboundAllvrLodForgetPacket.BROADCAST) {
            PendingTicket ticket = pending[lvl].get(packet.cellLong());
            if (ticket == null || ticket.sessionEpoch() != packet.sessionEpoch()
                || ticket.requestId() != packet.requestId()) {
                return; // a late forget must not settle a newer request
            }
            pending[lvl].remove(packet.cellLong());
            if (packet.retryable()) {
                retryAtTick[lvl].put(packet.cellLong(), clientTick + 10L);
            }
            return;
        }
        pending[lvl].remove(packet.cellLong());
        resident[lvl].remove(packet.cellLong());
        empty[lvl].remove(packet.cellLong());
        retryAtTick[lvl].remove(packet.cellLong());
        AllvrLodBackendManager.forget(lvl, packet.cellLong());
    }

    /** Drops all LOD state (level unload / dimension switch / logout). */
    public static void clear() {
        if (inDimension()) {
            sendSubscription(false, AllvrClientRenderDistance.chunks());
        }
        sessionEpoch++;
        lastSubscription = null;
        lastRenderDistanceChunks = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            clearLevel(i);
        }
        AllvrLodBackendManager.leave();
        loggedFirstBitmap = false;
    }

    /** Level join: binds the backend manager to the new client level. */
    public static void onLevelChanged(net.minecraft.client.multiplayer.ClientLevel level) {
        sessionEpoch++;
        lastSubscription = null;
        lastRenderDistanceChunks = Integer.MIN_VALUE;
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
            retryAtTick[i].clear();
        }
        syncSubscription();
    }

    // ------------------------------------------------------------------
    // per-tick request walk (main thread)
    // ------------------------------------------------------------------

    public static void tick() {
        clientTick++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.level.dimension() != AllvrDimensions.ALLAY_LEVEL || mc.player == null) {
            return;
        }
        AllvrLodBackendManager.tick(
            mc.gameRenderer.getMainCamera().getPosition().x,
            mc.gameRenderer.getMainCamera().getPosition().y,
            mc.gameRenderer.getMainCamera().getPosition().z);
        for (var failure : AllvrLodBackendManager.drainFailures()) {
            int lvl = failure.level();
            if (lvl < 0 || lvl > AllvrLodPos.MAX_LEVEL) {
                continue;
            }
            resident[lvl].remove(failure.cellLong());
            empty[lvl].remove(failure.cellLong());
            retryAtTick[lvl].put(failure.cellLong(), clientTick + 10L);
            AllvrLodBackendManager.forget(lvl, failure.cellLong());
        }
        syncSubscription();
        if (!farTerrainEnabled() || !AllvrLodBackendManager.requestsOpen()) {
            // near-only (voxy missing, disabled, or failed): no new requests,
            // pending drained; near-only mode has no alternate retry path
            for (int lvl = 0; lvl <= AllvrLodPos.MAX_LEVEL; lvl++) {
                if (!pending[lvl].isEmpty() || !resident[lvl].isEmpty() || !empty[lvl].isEmpty()) {
                    clearLevel(lvl);
                }
            }
            return;
        }
        BlockPos player = mc.player.blockPosition();
        expirePending();
        int perTick = REQUESTS_PER_TICK;
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
                    sessionEpoch,
                    entries.subList(i, end)));
        }
    }

    /**
     * Master gate for the far-terrain half: Voxy is the only far-terrain
     * backend, so this single client option controls whether its request walk
     * is active.
     */
    private static boolean farTerrainEnabled() {
        return ClientConfig.allvrLod;
    }

    private static void walkLevel(int lvl, LevelState state, BlockPos player, List<long[]> entries,
                                  int perTick) {
        int half = state.dim >> 1;
        int playerCellX = player.getX() >> (5 + lvl);
        int playerCellY = player.getY() >> (5 + lvl);
        int playerCellZ = player.getZ() >> (5 + lvl);
        // The seam is expressed in blocks, not a level-specific cell band. A
        // 12-chunk Minecraft render distance therefore gives Sodium 192 blocks
        // of near terrain and starts the Voxy hierarchy immediately beyond it.
        int nearDistanceBlocks = AllvrClientRenderDistance.blocks();
        int viewDistanceBlocks = viewDistanceBlocks();
        int verticalLimit = AllvrLodBands.activeVerticalCells(lvl);

        // Evict before the budget/pending early-outs so stale near nodes
        // cannot survive indefinitely when the request queue is full.
        evictFar(lvl, playerCellX, playerCellY, playerCellZ, half, player,
            nearDistanceBlocks, verticalLimit);

        int budget = perTick - entries.size();
        if (budget <= 0 || pending[lvl].size() >= MAX_PENDING) {
            return;
        }

        // Walk only set bits. The hierarchy now uses the complete coverage box
        // at every level, so scanning every air bit would make a large render
        // distance needlessly expensive on the render thread.
        int cellCount = state.dim * state.dim * state.dim;
        for (int wordIndex = 0; wordIndex < state.words.length && budget > 0; wordIndex++) {
            long word = state.words[wordIndex];
            while (word != 0L && budget > 0) {
                long bitMask = word & -word;
                int bit = Long.numberOfTrailingZeros(bitMask);
                word ^= bitMask;
                int flat = (wordIndex << 6) + bit;
                if (flat >= cellCount) {
                    break; // ignore padding bits in the final word
                }
                int ix = flat % state.dim;
                int yz = flat / state.dim;
                int iz = yz % state.dim;
                int iy = yz / state.dim;
                int cx = state.originX + ix;
                int cy = state.originY + iy;
                int cz = state.originZ + iz;
                if (Math.abs(cy - playerCellY) > verticalLimit) {
                    continue; // outside the active vertical window (§5.2)
                }
                int distance = AllvrLodBands.nearestDistanceBlocks(lvl, cx, cy, cz,
                    player.getX(), player.getY(), player.getZ());
                if (distance <= nearDistanceBlocks
                    || !AllvrLodBands.inCoverage(lvl, distance, viewDistanceBlocks)) {
                    continue; // Sodium owns near terrain; outside server coverage
                }
                long cellLong = AllvrCubePos.asLong(cx, cy, cz);
                if (resident[lvl].contains(cellLong) || pending[lvl].containsKey(cellLong)
                    || empty[lvl].contains(cellLong)
                    || retryAtTick[lvl].get(cellLong) > clientTick) {
                    continue;
                }
                long requestId = nextRequestId++;
                if (requestId == ClientboundAllvrLodForgetPacket.BROADCAST) {
                    requestId = nextRequestId++;
                }
                pending[lvl].put(cellLong, new PendingTicket(sessionEpoch, requestId,
                    clientTick + REQUEST_TIMEOUT_TICKS));
                entries.add(new long[] {lvl, cellLong, requestId});
                budget--;
                if (pending[lvl].size() >= MAX_PENDING) {
                    return;
                }
            }
        }
    }

    /** Drops resident, pending, and known-empty nodes outside the coverage box
     *  or inside Minecraft's near-render seam, and crops vertically to the
     *  active window (plan §5.2). */
    private static void evictFar(int lvl, int pcx, int pcy, int pcz, int half,
                                 BlockPos player, int nearDistanceBlocks, int verticalLimit) {
        int limit = half + Math.max(1, half >> 2);
        int vertical = Math.min(AllvrLodBands.verticalEvictCells(lvl, viewDistanceBlocks()), verticalLimit);
        evictSet(lvl, resident[lvl], pcx, pcy, pcz, limit, player, nearDistanceBlocks, vertical, true);
        evictPending(lvl, pcx, pcy, pcz, limit, player, nearDistanceBlocks, vertical);
        evictSet(lvl, empty[lvl], pcx, pcy, pcz, limit, player, nearDistanceBlocks, vertical, false);
    }

    private static int viewDistanceBlocks() {
        return com.iridium126.createmanaindustry.config.ServerConfig.allvrLodDistance;
    }

    private static void evictSet(int lvl, LongOpenHashSet set, int pcx, int pcy, int pcz, int limit,
                                 BlockPos player, int nearDistanceBlocks, int vertical,
                                 boolean accepted) {
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
            boolean insideNear = AllvrLodBands.nearestDistanceBlocks(lvl, pos.cellX(), pos.cellY(), pos.cellZ(),
                player.getX(), player.getY(), player.getZ()) <= nearDistanceBlocks;
            if (d > limit || insideNear || dy > vertical) {
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

    private static void evictPending(int lvl, int pcx, int pcy, int pcz, int limit,
                                     BlockPos player, int nearDistanceBlocks, int vertical) {
        var it = pending[lvl].keySet().iterator();
        while (it.hasNext()) {
            long cellLong = it.nextLong();
            AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, cellLong);
            int d = Math.max(Math.abs(pos.cellX() - pcx),
                Math.max(Math.abs(pos.cellY() - pcy), Math.abs(pos.cellZ() - pcz)));
            int dy = Math.abs(pos.cellY() - pcy);
            boolean insideNear = AllvrLodBands.nearestDistanceBlocks(lvl, pos.cellX(), pos.cellY(), pos.cellZ(),
                player.getX(), player.getY(), player.getZ()) <= nearDistanceBlocks;
            if (d > limit || insideNear || dy > vertical) {
                it.remove();
            }
        }
    }

    /** Settles lost/failed transport responses so one dead connection cannot
     * permanently consume the per-level request window. */
    private static void expirePending() {
        for (int lvl = 0; lvl <= AllvrLodPos.MAX_LEVEL; lvl++) {
            var it = pending[lvl].long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                long cellLong = entry.getLongKey();
                if (entry.getValue().deadlineTick() <= clientTick) {
                    it.remove();
                    retryAtTick[lvl].put(cellLong,
                        Math.max(retryAtTick[lvl].get(cellLong), clientTick + 10L));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /**
     * Blocks of visible terrain the allay dimension's fog should cover (the
     * fog-end target consumed by {@code AllvrFogRendererMixin}). The far
     * radius is only reported while the voxy backend is actually active —
     * near-only fog follows Minecraft's own effective render distance instead
     * of advertising a far horizon nothing renders.
     */
    public static float viewExtentBlocks() {
        if (!farTerrainEnabled() || !AllvrLodBackendManager.farTerrainActive()) {
            return AllvrClientRenderDistance.blocks();
        }
        LevelState l3 = levels[3];
        if (l3 != null && l3.dim > 0) {
            return (l3.dim >> 1) * (float) AllvrLodBands.cellBlocks(3);
        }
        return viewDistanceBlocks();
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
        retryAtTick[lvl].clear();
    }

    private static void syncSubscription() {
        boolean desired = farTerrainEnabled() && AllvrLodBackendManager.requestsOpen();
        int renderDistanceChunks = AllvrClientRenderDistance.chunks();
        if (lastSubscription != null && lastSubscription == desired
            && (!desired || lastRenderDistanceChunks == renderDistanceChunks)) {
            return;
        }
        sendSubscription(desired, renderDistanceChunks);
        lastSubscription = desired;
        lastRenderDistanceChunks = renderDistanceChunks;
    }

    private static void sendSubscription(boolean subscribed, int renderDistanceChunks) {
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
            new com.iridium126.createmanaindustry.dimension.net.ServerboundAllvrLodSubscriptionPacket(
                sessionEpoch, subscribed, renderDistanceChunks));
    }

    private AllvrLodClientState() {}
}
