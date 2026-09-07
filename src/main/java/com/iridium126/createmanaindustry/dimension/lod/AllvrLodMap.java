package com.iridium126.createmanaindustry.dimension.lod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.config.ServerConfig;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubeMap;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.cube.AllvrOverlaySource;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodBitmapPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodForgetPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodSectionPacket;
import com.iridium126.createmanaindustry.dimension.net.ServerboundAllvrLodRequestPacket;

/**
 * Server-side LOD pipeline for one allay-dimension {@link ServerLevel}
 * (sodium-parity plan §6.2): per-player surface-node bitmaps (S→C), a C2S
 * section-request channel answered from a shared LRU cache or a parallel
 * build pool, and the player-edit invalidation loop. The legacy server-meshed
 * quad path is deleted — the only wire payload is the voxel section.
 * <p>
 * Thread discipline: everything on the server main thread except the build
 * pool, which receives immutable jobs (generator math is pure; world reads
 * happen only in the main-thread overlay capture). The main-thread slice is
 * budgeted ({@link #PREP_BUDGET_NANOS}, grilling Q5: &lt;0.5ms — queue
 * dispatch plus edited-cube overlay capture; a single oversized capture may
 * overrun, the budget only paces how many jobs dequeue per tick).
 * <p>
 * Invalidation (grilling Q4): a player block edit bumps the containing
 * node's generation per level (≤4 nodes), drops the cache entry and queues a
 * forget broadcast (flushed deduped per tick — a /fill must not emit 16k
 * packets). Stale build results (generation moved while building) are
 * dropped and answered with a forget so the client re-requests fresh.
 */
public final class AllvrLodMap {

    /** Shared section cache budget (4a grilling decision, kept). */
    private static final long CACHE_BUDGET_BYTES = 256L << 20;
    /** Section requests dequeued per tick (grilling Q5 revision). */
    private static final int REQUESTS_PER_TICK = 64;
    /** Per-level in-flight request cap. */
    private static final int MAX_INFLIGHT = 256;
    /** Main-thread prep slice budget (grilling Q5). */
    private static final long PREP_BUDGET_NANOS = 500_000L;
    /** Cache distance-eviction cadence (ticks). */
    private static final int EVICT_SCAN_TICKS = 40;

    private final ServerLevel level;
    private final AllvrCubeMap cubeMap;
    private final AllvrIslandFieldGenerator generator;
    private final AllvrLodField field;
    private final ExecutorService pool;

    private static final class Sub {
        final AllvrCubePos[] lastCenter = new AllvrCubePos[4];
        final int[] lastDim = new int[4];
        int lastViewDistance = Integer.MIN_VALUE;
        long lastCoverageRevision;
        long sessionEpoch;
        boolean subscribed;
    }

    private final Map<UUID, Sub> subs = new HashMap<>();

    private record ClientTicket(long sessionEpoch, long requestId) {}

    private static final class Request {
        final long gen;
        final long buildId = REQUEST_IDS.incrementAndGet();
        final Map<UUID, ClientTicket> requesters = new HashMap<>(1);

        Request(long gen) {
            this.gen = gen;
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS =
        new java.util.concurrent.atomic.AtomicLong();

    private final Long2ObjectOpenHashMap<Request>[] requests = new Long2ObjectOpenHashMap[4];
    /**
     * Per-level section-payload LRU cache, sharing {@link #cacheBytes}.
     */
    @SuppressWarnings("unchecked")
    private final LinkedHashMap<Long, byte[]>[] sectionCache = new LinkedHashMap[4];
    /** Per-node generation, bumped on every edit inside the node. */
    private final Long2IntOpenHashMap[] gens = new Long2IntOpenHashMap[4];
    /** Edits awaiting their forget broadcast (flushed deduped per tick). */
    private final LongOpenHashSet[] dirtyNodes = new LongOpenHashSet[4];

    /**
     * Voxel section result (plan §6.2): {@code payload == null} means all-air
     * (a real, cacheable "no faces" answer), not a failure — failures never
     * enqueue a result at all.
     */
    private record BuiltSectionPayload(int level, long cellLong, long gen, long buildId,
                                      boolean success, byte[] payload,
                                      Map<UUID, ClientTicket> requesters) {}

    /**
     * Main-thread prep state of one queued node: the live overlay captured
     * from loaded edited cubes plus the async decodes of persisted-but-
     * unloaded cubes (plan §7.6). The job is only handed to the build pool
     * once every async overlay is ready — the pool then runs without world
     * access.
     */
    private static final class PrepJob {
        final int level;
        final long cellLong;
        final AllvrLodPos pos;
        AllvrLodSnapshot.Overlay liveOverlay;
        final List<java.util.concurrent.CompletableFuture<com.iridium126.createmanaindustry.dimension.storage.AllvrPersistedOverlay>>
            asyncOverlays = new ArrayList<>(1);
        boolean captureStarted;

        PrepJob(int level, long cellLong, AllvrLodPos pos) {
            this.level = level;
            this.cellLong = cellLong;
            this.pos = pos;
        }
    }

    private final ArrayDeque<PrepJob> prepQueue = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<BuiltSectionPayload> sectionResults = new ConcurrentLinkedQueue<>();
    private long cacheBytes;
    private long coverageRevision = 1L;
    private int evictScanTicks;
    private boolean closed;

    @SuppressWarnings("unchecked")
    public AllvrLodMap(ServerLevel level, AllvrCubeMap cubeMap) {
        this.level = level;
        this.cubeMap = cubeMap;
        this.generator = new AllvrIslandFieldGenerator(level.getSeed());
        this.field = new AllvrLodField(this.generator);
        for (int i = 0; i < 4; i++) {
            this.requests[i] = new Long2ObjectOpenHashMap<>();
            this.gens[i] = new Long2IntOpenHashMap();
            this.gens[i].defaultReturnValue(0);
            this.dirtyNodes[i] = new LongOpenHashSet();
            // access-order: cache hits refresh recency, so the budget eviction
            // drops genuinely cold nodes, not the most recently served ones
            this.sectionCache[i] = new LinkedHashMap<>(16, 0.75f, true);
        }
        int threads = Math.max(1, ServerConfig.allvrLodBuildThreads);
        AtomicInteger index = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "CMI-AllvrLodBuild-" + index.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        CreateManaIndustry.LOGGER.info("[Allvr] LOD pipeline up ({} build thread(s), view distance {})",
            threads, ServerConfig.allvrLodDistance);
    }

    public void resetPlayer(UUID uuid) {
        this.removeRequester(uuid);
        this.subs.remove(uuid);
    }

    /** Updates the client's far-terrain capability and session ticket. */
    public void setSubscribed(UUID uuid, long sessionEpoch, boolean subscribed) {
        Sub previous = this.subs.get(uuid);
        if (!subscribed) {
            this.removeRequester(uuid);
            this.subs.remove(uuid);
            return;
        }
        if (previous == null || !previous.subscribed || previous.sessionEpoch != sessionEpoch) {
            this.removeRequester(uuid);
            Sub sub = previous == null ? new Sub() : previous;
            sub.sessionEpoch = sessionEpoch;
            sub.subscribed = true;
            for (int i = 0; i < sub.lastCenter.length; i++) {
                sub.lastCenter[i] = null;
                sub.lastDim[i] = 0;
            }
            sub.lastViewDistance = Integer.MIN_VALUE;
            this.subs.put(uuid, sub);
        }
    }

    private void removeRequester(UUID uuid) {
        for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
            var it = this.requests[lvl].long2ObjectEntrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                entry.getValue().requesters.remove(uuid);
                if (entry.getValue().requesters.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    private boolean subscribed(UUID uuid, long sessionEpoch) {
        Sub sub = this.subs.get(uuid);
        return sub != null && sub.subscribed && sub.sessionEpoch == sessionEpoch;
    }

    // ------------------------------------------------------------------
    // tick (server thread)
    // ------------------------------------------------------------------

    public void tick() {
        if (this.closed) {
            return;
        }
        List<ServerPlayer> players = this.level.players().stream()
            .filter(player -> {
                Sub sub = this.subs.get(player.getUUID());
                return sub != null && sub.subscribed
                    && this.subscribed(player.getUUID(), sub.sessionEpoch);
            })
            .toList();
        if (players.isEmpty()) {
            // Do not let a vanished player leave prep/request state or build
            // results accumulating until the next subscriber arrives.  Jobs
            // already executing are immutable and their late results are
            // discarded by clearing the result queue on the server thread.
            for (var map : this.requests) {
                map.clear();
            }
            this.prepQueue.clear();
            this.sectionResults.clear();
            return;
        }
        int viewDistance = ServerConfig.allvrLodDistance;

        this.flushDirtyNodes(players);
        this.refreshBitmaps(players, viewDistance);
        this.dispatchPrep();
        this.drainSectionResults(players);
        if (++this.evictScanTicks >= EVICT_SCAN_TICKS) {
            this.evictScanTicks = 0;
            this.evictCache(players, viewDistance);
        }
    }

    /** Bitmap recompute + resend when a player crossed the per-level threshold. */
    private void refreshBitmaps(List<ServerPlayer> players, int viewDistance) {
        for (ServerPlayer player : players) {
            Sub sub = this.subs.get(player.getUUID());
            if (sub == null || !sub.subscribed) {
                continue;
            }
        boolean distanceChanged = sub.lastViewDistance != viewDistance;
            boolean coverageChanged = sub.lastCoverageRevision != this.coverageRevision;
            for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
                if (!AllvrLodBands.enabled(lvl, viewDistance)) {
                    if (sub.lastCenter[lvl] != null || sub.lastDim[lvl] != 0) {
                        player.connection.send(new ClientboundAllvrLodBitmapPacket(
                            lvl, 0, 0, 0, 0, new long[0]));
                        sub.lastCenter[lvl] = null;
                        sub.lastDim[lvl] = 0;
                    }
                    continue;
                }
                int cellShift = 5 + lvl;
                int cx = player.getBlockX() >> cellShift;
                int cy = player.getBlockY() >> cellShift;
                int cz = player.getBlockZ() >> cellShift;
                int dim = AllvrLodBands.bitmapBoxCells(lvl, viewDistance);
                AllvrCubePos last = sub.lastCenter[lvl];
                int threshold = AllvrLodBands.resendThresholdCells(lvl, viewDistance);
                if (!distanceChanged && !coverageChanged && last != null && sub.lastDim[lvl] == dim
                    && Math.abs(last.getX() - cx) < threshold
                    && Math.abs(last.getY() - cy) < threshold
                    && Math.abs(last.getZ() - cz) < threshold) {
                    continue;
                }
                int ox = cx - (dim >> 1);
                int oy = cy - (dim >> 1);
                int oz = cz - (dim >> 1);
                long[] words = this.field.compute(lvl, ox, oy, oz, dim);
                this.mergeEditedCoverage(words, lvl, ox, oy, oz, dim);
                player.connection.send(new ClientboundAllvrLodBitmapPacket(lvl, ox, oy, oz, dim, words));
                sub.lastCenter[lvl] = AllvrCubePos.of(cx, cy, cz);
                sub.lastDim[lvl] = dim;
            }
            sub.lastViewDistance = viewDistance;
            sub.lastCoverageRevision = this.coverageRevision;
        }
    }

    /** Adds persisted/edited cubes to the natural surface candidate bitmap. */
    private void mergeEditedCoverage(long[] words, int level, int ox, int oy, int oz, int dim) {
        for (long cubeKey : this.cubeMap.persistedKeysSnapshot()) {
            AllvrCubePos cube = AllvrCubePos.fromLong(cubeKey);
            int x = cube.getX() >> level;
            int y = cube.getY() >> level;
            int z = cube.getZ() >> level;
            int ix = x - ox;
            int iy = y - oy;
            int iz = z - oz;
            if (ix < 0 || ix >= dim || iy < 0 || iy >= dim || iz < 0 || iz >= dim) {
                continue;
            }
            int bit = (iy * dim + iz) * dim + ix;
            words[bit >> 6] |= 1L << (bit & 63);
        }
    }

    /**
     * Main-thread prep slice: live overlay capture (world reads) + async
     * persisted-overlay loads (plan §7.6). A job waits in the queue until
     * every persisted overlay decode finished — only then is the immutable
     * combined overlay handed to the build pool. The budget paces how many
     * jobs dequeue per tick; waiting jobs rotate to the tail.
     */
    private void dispatchPrep() {
        long deadline = System.nanoTime() + PREP_BUDGET_NANOS;
        int scan = this.prepQueue.size();
        int dispatched = 0;
        while (scan-- > 0 && dispatched < REQUESTS_PER_TICK && !this.prepQueue.isEmpty()) {
            PrepJob job = this.prepQueue.poll();
            Request req = this.requests[job.level].get(job.cellLong);
            if (req == null) {
                continue; // invalidated while queued — its forget already went out
            }
            if (!job.captureStarted) {
                this.startCapture(job);
            }
            if (job.asyncOverlays.isEmpty()) {
                this.submitBuild(job, req);
                dispatched++;
                if (System.nanoTime() >= deadline) {
                    break;
                }
                continue;
            }
            boolean failed = false;
            boolean allDone = true;
            for (java.util.concurrent.CompletableFuture<com.iridium126.createmanaindustry.dimension.storage.AllvrPersistedOverlay> f
                : job.asyncOverlays) {
                if (f.isCompletedExceptionally()) {
                    failed = true;
                    break;
                }
                if (!f.isDone()) {
                    allDone = false;
                }
            }
            if (failed) {
                // I/O or corrupt-record failure: send forget so the client
                // re-requests later — never cache "generated far view" over a
                // real edit (plan §7.6.6). Drop the request entry too, or the
                // re-request would just re-join the dead one.
                this.requests[job.level].remove(job.cellLong);
                CreateManaIndustry.LOGGER.error("[Allvr] persisted overlay read failed for node {} — forget sent, retry possible",
                    job.pos);
                for (var requester : req.requesters.entrySet()) {
                    UUID uuid = requester.getKey();
                    ServerPlayer player = findPlayer(this.level.players(), uuid);
                    if (player != null && this.subscribed(uuid, requester.getValue().sessionEpoch())) {
                        ClientTicket ticket = requester.getValue();
                        player.connection.send(ClientboundAllvrLodForgetPacket.ticketed(job.level,
                            job.cellLong, ticket.sessionEpoch(), ticket.requestId(), true));
                    }
                }
                continue;
            }
            if (!allDone) {
                this.prepQueue.add(job); // rotate — keep the queue fair
                continue;
            }
            this.submitBuild(job, req);
            dispatched++;
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
    }

    /** Server-thread part of the overlay capture (loaded cubes only). */
    private void startCapture(PrepJob job) {
        job.captureStarted = true;
        AllvrLodPos pos = job.pos;
        int stride = pos.stride();
        int minBx = pos.minBlockX();
        int minBy = pos.minBlockY();
        int minBz = pos.minBlockZ();
        int span = pos.sizeBlocks();
        int pad = stride + 16; // cells need the pad ring; emitters reach 15 beyond it
        List<AllvrOverlaySource> sources = new ArrayList<>(1);
        int cx0 = (minBx - pad) >> 5;
        int cx1 = (minBx + span + pad - 1) >> 5;
        int cy0 = (minBy - pad) >> 5;
        int cy1 = (minBy + span + pad - 1) >> 5;
        int cz0 = (minBz - pad) >> 5;
        int cz1 = (minBz + span + pad - 1) >> 5;
        for (int cy = cy0; cy <= cy1; cy++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                for (int cx = cx0; cx <= cx1; cx++) {
                    long key = AllvrCubePos.asLong(cx, cy, cz);
                    if (!this.cubeMap.isPersisted(key)) {
                        continue;
                    }
                    AllvrCube cube = this.cubeMap.getLoadedCube(key);
                    if (cube != null) {
                        sources.add(cube);
                        continue;
                    }
                    // persisted but unloaded — decode async, merge when ready
                    job.asyncOverlays.add(this.cubeMap.loadOverlayAsync(AllvrCubePos.of(cx, cy, cz)));
                }
            }
        }
        job.liveOverlay = AllvrLodSnapshot.capture(pos, sources);
    }

    /** Combines the live + persisted overlays and hands the immutable job to the pool. */
    private void submitBuild(PrepJob job, Request req) {
        AllvrLodSnapshot.Overlay overlay = job.liveOverlay;
        for (java.util.concurrent.CompletableFuture<com.iridium126.createmanaindustry.dimension.storage.AllvrPersistedOverlay> f
            : job.asyncOverlays) {
            try {
                com.iridium126.createmanaindustry.dimension.storage.AllvrPersistedOverlay persisted = f.join();
                if (persisted != null) {
                    AllvrLodSnapshot.Overlay extra = AllvrLodSnapshot.capture(job.pos, List.of(persisted));
                    overlay = AllvrLodSnapshot.mergeInto(overlay, extra);
                }
            } catch (Exception e) {
                // completedExceptionally jobs are filtered before this point
                CreateManaIndustry.LOGGER.error("[Allvr] persisted overlay decode failed for node {}", job.pos, e);
            }
        }
        Map<UUID, ClientTicket> requesters = Map.copyOf(req.requesters);
        long gen = req.gen;
        long buildId = req.buildId;
        AllvrLodSnapshot.Overlay finalOverlay = overlay;
        this.pool.execute(() -> this.runSectionJob(job.level, job.cellLong, gen, buildId, finalOverlay, requesters));
    }

    /**
     * Pool thread: voxel section build (plan §6.2) — failures throw inside
     * the pool and enqueue nothing (client re-requests).
     */
    private void runSectionJob(int level, long cellLong, long gen, long buildId,
                               AllvrLodSnapshot.Overlay overlay, Map<UUID, ClientTicket> requesters) {
        byte[] payload;
        try {
            AllvrLodPos pos = AllvrLodPos.fromCellLong(level, cellLong);
            BlockState[] states = new BlockState[AllvrMesher.PADDED * AllvrMesher.PADDED * AllvrMesher.PADDED];
            java.util.Arrays.fill(states, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            byte[] occludes = new byte[states.length];
            AllvrLodSnapshot snapshot = AllvrLodSnapshot.create(this.generator, pos, overlay);
            snapshot.fill(states, occludes);
            AllvrLodSectionData data = AllvrLodSectionData.build(
                level, cellLong, gen, states, occludes, snapshot.light());
            payload = data == null ? ALL_AIR_PAYLOAD : AllvrLodSectionCodec.encode(data);
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.error("[Allvr] LOD section build failed on {} — client re-requests",
                pos(level, cellLong), t);
            this.sectionResults.add(new BuiltSectionPayload(level, cellLong, gen, buildId,
                false, null, requesters));
            return;
        }
        this.sectionResults.add(new BuiltSectionPayload(level, cellLong, gen, buildId,
            true, payload, requesters));
    }

    /** Wire marker for an all-air section node (codec format 0). */
    private static final byte[] ALL_AIR_PAYLOAD = {(byte) 0};

    private static String pos(int level, long cellLong) {
        return AllvrLodPos.fromCellLong(level, cellLong).toString();
    }

    /**
     * Mirrors the generation-race discipline: a stale generation answers a
     * forget, not a payload.
     */
    private void drainSectionResults(List<ServerPlayer> players) {
        BuiltSectionPayload result;
        while ((result = this.sectionResults.poll()) != null) {
            Request live = this.requests[result.level()].get(result.cellLong());
            boolean sameRequest = live != null && live.gen == result.gen()
                && live.buildId == result.buildId();
            boolean stale = !sameRequest;
            Map<UUID, ClientTicket> requesters;
            if (sameRequest) {
                // Keep the registry entry through queueing and the worker run;
                // remove only the exact generation that produced this result.
                this.requests[result.level()].remove(result.cellLong());
                requesters = Map.copyOf(live.requesters);
            } else {
                requesters = result.requesters();
            }
            if (!stale && result.success()) {
                this.sectionCachePut(result.level(), result.cellLong(), result.payload());
            }
            for (var requester : requesters.entrySet()) {
                UUID uuid = requester.getKey();
                ClientTicket ticket = requester.getValue();
                ServerPlayer player = findPlayer(players, uuid);
                if (player == null) {
                    continue;
                }
                if (stale || !result.success()) {
                    player.connection.send(ClientboundAllvrLodForgetPacket.ticketed(result.level(),
                        result.cellLong(), ticket.sessionEpoch(), ticket.requestId(), !stale));
                } else {
                    player.connection.send(new ClientboundAllvrLodSectionPacket(
                        ClientboundAllvrLodSectionPacket.PROTOCOL_VERSION, ticket.sessionEpoch(),
                        ticket.requestId(), result.level(), result.cellLong(), (int) result.gen(),
                        result.payload()));
                }
            }
        }
    }

    private static ServerPlayer findPlayer(List<ServerPlayer> players, UUID uuid) {
        for (ServerPlayer player : players) {
            if (player.getUUID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // requests (server thread, from the C2S packet)
    // ------------------------------------------------------------------

    public void onRequest(ServerPlayer player, long sessionEpoch, List<long[]> entries) {
        if (this.closed) {
            return;
        }
        if (!this.subscribed(player.getUUID(), sessionEpoch)) {
            return;
        }
        int viewDistance = ServerConfig.allvrLodDistance;
        for (long[] entry : entries) {
            int lvl = (int) entry[0];
            long cellLong = entry[1];
            long requestId = entry[2];
            if (lvl < 0 || lvl > AllvrLodBands.MAX_LEVEL) {
                player.connection.send(ClientboundAllvrLodForgetPacket.ticketed(lvl, cellLong,
                    sessionEpoch, requestId, false));
                continue;
            }
            AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, cellLong);
            int distance = chebyshevToNode(player.getPosition(1.0f), pos);
            if (!AllvrLodBands.inBand(lvl, distance, viewDistance)) {
                player.connection.send(ClientboundAllvrLodForgetPacket.ticketed(lvl, cellLong,
                    sessionEpoch, requestId, false));
                continue;
            }
            Request existing = this.requests[lvl].get(cellLong);
            if (existing != null) {
                existing.requesters.put(player.getUUID(), new ClientTicket(sessionEpoch, requestId));
                continue;
            }
            byte[] cached = this.sectionCache[lvl].get(cellLong);
            if (cached != null) {
                player.connection.send(new ClientboundAllvrLodSectionPacket(
                    ClientboundAllvrLodSectionPacket.PROTOCOL_VERSION, sessionEpoch, requestId,
                    lvl, cellLong, (int) this.gens[lvl].get(cellLong), cached));
                continue;
            }
            if (this.requests[lvl].size() >= MAX_INFLIGHT) {
                player.connection.send(ClientboundAllvrLodForgetPacket.ticketed(lvl, cellLong,
                    sessionEpoch, requestId, true));
                continue;
            }
            Request req = new Request(this.gens[lvl].get(cellLong));
            req.requesters.put(player.getUUID(), new ClientTicket(sessionEpoch, requestId));
            this.requests[lvl].put(cellLong, req);
            this.prepQueue.add(new PrepJob(lvl, cellLong, pos));
        }
    }

    private static int chebyshevToNode(net.minecraft.world.phys.Vec3 playerPos, AllvrLodPos pos) {
        double dx = Math.max(pos.minBlockX() - playerPos.x, playerPos.x - (pos.minBlockX() + (double) pos.sizeBlocks()));
        double dy = Math.max(pos.minBlockY() - playerPos.y, playerPos.y - (pos.minBlockY() + (double) pos.sizeBlocks()));
        double dz = Math.max(pos.minBlockZ() - playerPos.z, playerPos.z - (pos.minBlockZ() + (double) pos.sizeBlocks()));
        return (int) Math.max(0, Math.max(Math.max(dx, dy), dz));
    }

    // ------------------------------------------------------------------
    // invalidation (server thread, from AllvrCubeMap#setBlock)
    // ------------------------------------------------------------------

    public void onBlockChanged(net.minecraft.core.BlockPos pos) {
        for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
            int shift = 5 + lvl;
            int cx = pos.getX() >> shift;
            int cy = pos.getY() >> shift;
            int cz = pos.getZ() >> shift;
            // The snapshot has a stride-dependent pad ring for occlusion and
            // light sampling.  An edit near a node boundary can therefore
            // change a neighbour's payload even when the edited block is not
            // in that neighbour's 32³ core.  Invalidate the complete local
            // dependency stencil; the dirty set deduplicates edit storms.
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        long cellLong = AllvrCubePos.asLong(cx + dx, cy + dy, cz + dz);
                        this.gens[lvl].put(cellLong, this.gens[lvl].get(cellLong) + 1);
                        this.requests[lvl].remove(cellLong);
                        this.cacheRemove(lvl, cellLong);
                        this.dirtyNodes[lvl].add(cellLong);
                    }
                }
            }
        }
        this.coverageRevision++;
    }

    /** Flushes forget broadcasts, deduped — a /fill edit storm collapses to
     *  one packet per touched node per level. */
    private void flushDirtyNodes(List<ServerPlayer> players) {
        for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
            LongOpenHashSet dirty = this.dirtyNodes[lvl];
            if (dirty.isEmpty()) {
                continue;
            }
            for (long cellLong : dirty) {
                ClientboundAllvrLodForgetPacket p = new ClientboundAllvrLodForgetPacket(lvl, cellLong);
                for (ServerPlayer player : players) {
                    player.connection.send(p);
                }
            }
            dirty.clear();
        }
    }

    /**
     * Idempotent close (plan §7.6.7): rejects new work, waits for (or cancels)
     * in-flight builds, and clears the result queue. Called from the
     * {@code ServerLevel#close} mixin.
     */
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.pool.shutdown();
        boolean terminated = false;
        try {
            terminated = this.pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!terminated) {
            this.pool.shutdownNow();
        }
        for (var map : this.requests) {
            map.clear();
        }
        this.prepQueue.clear();
        this.subs.clear();
        this.sectionResults.clear();
    }

    // ------------------------------------------------------------------
    // cache
    // ------------------------------------------------------------------

    private void sectionCachePut(int lvl, long cellLong, byte[] payload) {
        byte[] old = this.sectionCache[lvl].put(cellLong, payload);
        if (old != null) {
            this.cacheBytes -= old.length;
        }
        this.cacheBytes += payload.length;
        while (this.cacheBytes > CACHE_BUDGET_BYTES) {
            var it = this.sectionCache[lvl].entrySet().iterator();
            if (!it.hasNext()) {
                CreateManaIndustry.LOGGER.warn("[Allvr] LOD section cache over budget with empty level map");
                break;
            }
            var eldest = it.next();
            it.remove();
            this.cacheBytes -= eldest.getValue().length;
        }
    }

    private void cacheRemove(int lvl, long cellLong) {
        byte[] oldSection = this.sectionCache[lvl].remove(cellLong);
        if (oldSection != null) {
            this.cacheBytes -= oldSection.length;
        }
    }

    /** Drops cache entries outside every player's R×1.25 hysteresis (4a decision). */
    private void evictCache(List<ServerPlayer> players, int viewDistance) {
        long limit = (long) viewDistance * 5 / 4;
        for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
            var sectionIt = this.sectionCache[lvl].entrySet().iterator();
            while (sectionIt.hasNext()) {
                var e = sectionIt.next();
                AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, e.getKey());
                if (!anyPlayerNear(players, pos, limit)) {
                    sectionIt.remove();
                    this.cacheBytes -= e.getValue().length;
                }
            }
        }
    }

    private static boolean anyPlayerNear(List<ServerPlayer> players, AllvrLodPos pos, long limit) {
        for (ServerPlayer player : players) {
            if (chebyshevToNode(player.getPosition(1.0f), pos) <= limit) {
                return true;
            }
        }
        return false;
    }
}
