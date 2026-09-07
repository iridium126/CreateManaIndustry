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
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodMeshPacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrLodSectionPacket;
import com.iridium126.createmanaindustry.dimension.net.ServerboundAllvrLodRequestPacket;

/**
 * Server-side LOD pipeline for one allay-dimension {@link ServerLevel} (doc
 * §13 4c-1, grilling 2026-09-06): per-player surface-node bitmaps (S→C), a
 * C2S mesh-request channel answered from a shared LRU cache or a parallel
 * build pool, and the player-edit invalidation loop.
 * <p>
 * Thread discipline: everything on the server main thread except the build
 * pool, which receives immutable jobs (generator math is pure; world reads
 * happen only in the main-thread overlay capture). The main-thread slice is
 * budgeted ({@link #PREP_BUDGET_NANOS}, grilling Q5: &lt;0.5ms — queue
 * dispatch plus edited-cube overlay capture; a single oversized capture may
 * overrun, the budget only paces how many jobs dequeue per tick).
 * <p>
 * Throughput revision over the 4a text (grilling Q5): build pool (default 2
 * threads, config) instead of a 2ms/tick server-thread budget, and request
 * pacing 64/tick in-flight 256 instead of 8/32 — R=2048 holds ~50k surface
 * nodes, which the original figures would fill in tens of minutes.
 * <p>
 * Invalidation (grilling Q4, in 4c-1): a player block edit bumps the
 * containing node's generation per level (≤4 nodes), drops the cache entry
 * and queues a forget broadcast (flushed deduped per tick — a /fill must not
 * emit 16k packets). Stale build results (generation moved while building)
 * are dropped and answered with a forget so the client re-requests fresh.
 */
public final class AllvrLodMap {

    /** Shared mesh cache budget (4a grilling decision, kept). */
    private static final long CACHE_BUDGET_BYTES = 256L << 20;
    /** Mesh requests dequeued per tick (grilling Q5 revision). */
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
    }

    private final Map<UUID, Sub> subs = new HashMap<>();

    private static final class Request {
        final long gen;
        final long id = REQUEST_IDS.incrementAndGet();
        /** Wire backend this request was opened for (§6.1 capability). */
        final boolean section;
        final List<UUID> requesters = new ArrayList<>(1);

        Request(long gen, boolean section) {
            this.gen = gen;
            this.section = section;
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong REQUEST_IDS =
        new java.util.concurrent.atomic.AtomicLong();

    private final Long2ObjectOpenHashMap<Request>[] requests = new Long2ObjectOpenHashMap[4];
    /** Per-level mesh cache; access-order LRU under {@link #cacheBytes}. */
    @SuppressWarnings("unchecked")
    private final LinkedHashMap<Long, long[]>[] cache = new LinkedHashMap[4];
    /**
     * Per-level section-payload cache (plan §6.2) — same LRU discipline, one
     * entry per (level, node) per wire backend, sharing {@link #cacheBytes}.
     */
    @SuppressWarnings("unchecked")
    private final LinkedHashMap<Long, byte[]>[] sectionCache = new LinkedHashMap[4];
    /** Per-node generation, bumped on every edit inside the node. */
    private final Long2IntOpenHashMap[] gens = new Long2IntOpenHashMap[4];
    /** Edits awaiting their forget broadcast (flushed deduped per tick). */
    private final LongOpenHashSet[] dirtyNodes = new LongOpenHashSet[4];

    /**
     * Server-meshed quad result for legacy-capable requesters (4c-1 path).
     * {@code quads == null} means the build failed — answered with a forget.
     */
    private record BuiltMesh(int level, long cellLong, long gen, long[] quads, List<UUID> requesters) {}

    /**
     * Voxel section result for section-capable requesters (plan §6.1/§6.2):
     * {@code payload == null} means all-air (a real, cacheable "no faces"
     * answer), not a failure — failures never enqueue a result at all.
     */
    private record BuiltSectionPayload(int level, long cellLong, long gen, long requestId,
                                      byte[] payload, List<UUID> requesters) {}

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
        /** Wire backend this job builds for (§6.1 capability). */
        final boolean section;
        AllvrLodSnapshot.Overlay liveOverlay;
        final List<java.util.concurrent.CompletableFuture<com.iridium126.createmanaindustry.dimension.storage.AllvrPersistedOverlay>>
            asyncOverlays = new ArrayList<>(1);
        boolean captureStarted;

        PrepJob(int level, long cellLong, AllvrLodPos pos, boolean section) {
            this.level = level;
            this.cellLong = cellLong;
            this.pos = pos;
            this.section = section;
        }
    }

    private final ArrayDeque<PrepJob> prepQueue = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<BuiltMesh> results = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BuiltSectionPayload> sectionResults = new ConcurrentLinkedQueue<>();
    private long cacheBytes;
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
            this.cache[i] = new LinkedHashMap<>(16, 0.75f, true);
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
        this.subs.remove(uuid);
    }

    // ------------------------------------------------------------------
    // tick (server thread)
    // ------------------------------------------------------------------

    public void tick() {
        if (this.closed) {
            return;
        }
        List<ServerPlayer> players = this.level.players();
        if (players.isEmpty()) {
            return;
        }
        int viewDistance = ServerConfig.allvrLodDistance;

        this.flushDirtyNodes(players);
        this.refreshBitmaps(players, viewDistance);
        this.dispatchPrep();
        this.drainResults(players);
        this.drainSectionResults(players);
        if (++this.evictScanTicks >= EVICT_SCAN_TICKS) {
            this.evictScanTicks = 0;
            this.evictCache(players, viewDistance);
        }
    }

    /** Bitmap recompute + resend when a player crossed the per-level threshold. */
    private void refreshBitmaps(List<ServerPlayer> players, int viewDistance) {
        for (ServerPlayer player : players) {
            Sub sub = this.subs.computeIfAbsent(player.getUUID(), k -> new Sub());
            boolean distanceChanged = sub.lastViewDistance != viewDistance;
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
                if (!distanceChanged && last != null && sub.lastDim[lvl] == dim
                    && Math.abs(last.getX() - cx) < threshold
                    && Math.abs(last.getY() - cy) < threshold
                    && Math.abs(last.getZ() - cz) < threshold) {
                    continue;
                }
                int ox = cx - (dim >> 1);
                int oy = cy - (dim >> 1);
                int oz = cz - (dim >> 1);
                long[] words = this.field.compute(lvl, ox, oy, oz, dim);
                player.connection.send(new ClientboundAllvrLodBitmapPacket(lvl, ox, oy, oz, dim, words));
                sub.lastCenter[lvl] = AllvrCubePos.of(cx, cy, cz);
                sub.lastDim[lvl] = dim;
            }
            sub.lastViewDistance = viewDistance;
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
        while (scan-- > 0 && !this.prepQueue.isEmpty()) {
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
                ClientboundAllvrLodForgetPacket forget = new ClientboundAllvrLodForgetPacket(job.level, job.cellLong);
                for (UUID uuid : req.requesters) {
                    ServerPlayer player = findPlayer(this.level.players(), uuid);
                    if (player != null) {
                        player.connection.send(forget);
                    }
                }
                continue;
            }
            if (!allDone) {
                this.prepQueue.add(job); // rotate — keep the queue fair
                continue;
            }
            this.submitBuild(job, req);
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
        List<UUID> requesters = List.copyOf(req.requesters);
        long gen = req.gen;
        long requestId = req.id;
        AllvrLodSnapshot.Overlay finalOverlay = overlay;
        if (job.section) {
            this.pool.execute(() -> this.runSectionJob(job.level, job.cellLong, gen, requestId, finalOverlay, requesters));
        } else {
            this.pool.execute(() -> this.runJob(job.level, job.cellLong, gen, finalOverlay, requesters));
        }
    }

    /** Pool thread: pure density math + mesher — no world access here. */
    private void runJob(int level, long cellLong, long gen,
                        AllvrLodSnapshot.Overlay overlay, List<UUID> requesters) {
        long[] quads = null;
        try {
            AllvrLodPos pos = AllvrLodPos.fromCellLong(level, cellLong);
            BlockState[] states = new BlockState[AllvrMesher.PADDED * AllvrMesher.PADDED * AllvrMesher.PADDED];
            java.util.Arrays.fill(states, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            byte[] occludes = new byte[states.length];
            AllvrLodSnapshot snapshot = AllvrLodSnapshot.create(this.generator, pos, overlay);
            snapshot.fill(states, occludes);
            quads = AllvrMesher.build(states, occludes, snapshot.light(), AllvrLodSnapshot.SERVER_CODEC);
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.error("[Allvr] LOD build failed on {} — client re-requests", pos(level, cellLong), t);
        }
        this.results.add(new BuiltMesh(level, cellLong, gen, quads, requesters));
    }

    /**
     * Pool thread: voxel section build (plan §6.2) — the same snapshot feeds
     * {@link AllvrLodSectionData#build} so both wire paths see one truth;
     * failures throw inside the pool and enqueue nothing (client re-requests).
     */
    private void runSectionJob(int level, long cellLong, long gen, long requestId,
                               AllvrLodSnapshot.Overlay overlay, List<UUID> requesters) {
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
            return;
        }
        this.sectionResults.add(new BuiltSectionPayload(level, cellLong, gen, requestId, payload, requesters));
    }

    /** Wire marker for an all-air section node (codec format 0). */
    private static final byte[] ALL_AIR_PAYLOAD = {(byte) 0};

    private static String pos(int level, long cellLong) {
        return AllvrLodPos.fromCellLong(level, cellLong).toString();
    }

    private void drainResults(List<ServerPlayer> players) {
        BuiltMesh mesh;
        while ((mesh = this.results.poll()) != null) {
            Request live = this.requests[mesh.level()].get(mesh.cellLong());
            boolean sameRequest = live != null && live.gen == mesh.gen();
            boolean stale = !sameRequest || mesh.quads() == null;
            List<UUID> requesters;
            if (sameRequest) {
                // Keep the registry entry through queueing and the worker run;
                // remove only the exact generation that produced this result.
                this.requests[mesh.level()].remove(mesh.cellLong());
                requesters = List.copyOf(live.requesters);
            } else {
                requesters = mesh.requesters();
            }
            if (!stale) {
                this.cachePut(mesh.level(), mesh.cellLong(), mesh.quads());
            }
            ClientboundAllvrLodMeshPacket packet = stale
                ? null
                : new ClientboundAllvrLodMeshPacket(mesh.level(), mesh.cellLong(), mesh.quads());
            ClientboundAllvrLodForgetPacket forget = stale
                ? new ClientboundAllvrLodForgetPacket(mesh.level(), mesh.cellLong())
                : null;
            for (UUID uuid : requesters) {
                ServerPlayer player = findPlayer(players, uuid);
                if (player == null) {
                    continue;
                }
                player.connection.send(stale ? forget : packet);
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

    /** Mirrors {@link #drainResults} for section payloads (plan §6.2): the
     *  result queue stays coherent under generation races the same way — a
     *  stale generation answers a forget, not a payload. */
    private void drainSectionResults(List<ServerPlayer> players) {
        BuiltSectionPayload result;
        while ((result = this.sectionResults.poll()) != null) {
            Request live = this.requests[result.level()].get(result.cellLong());
            boolean sameRequest = live != null && live.gen == result.gen();
            boolean stale = !sameRequest;
            List<UUID> requesters;
            long requestId = result.requestId();
            if (sameRequest) {
                this.requests[result.level()].remove(result.cellLong());
                requesters = List.copyOf(live.requesters);
            } else {
                requesters = result.requesters();
            }
            if (!stale) {
                this.sectionCachePut(result.level(), result.cellLong(), result.payload());
            }
            ClientboundAllvrLodSectionPacket packet = stale
                ? null
                : new ClientboundAllvrLodSectionPacket(ClientboundAllvrLodSectionPacket.PROTOCOL_VERSION,
                    requestId, result.level(), result.cellLong(), (int) result.gen(), result.payload());
            ClientboundAllvrLodForgetPacket forget = stale
                ? new ClientboundAllvrLodForgetPacket(result.level(), result.cellLong())
                : null;
            for (UUID uuid : requesters) {
                ServerPlayer player = findPlayer(players, uuid);
                if (player == null) {
                    continue;
                }
                player.connection.send(stale ? forget : packet);
            }
        }
    }

    // ------------------------------------------------------------------
    // requests (server thread, from the C2S packet)
    // ------------------------------------------------------------------

    public void onRequest(ServerPlayer player, int capability, List<long[]> entries) {
        if (this.closed) {
            return;
        }
        boolean sectionCapable = capability == ServerboundAllvrLodRequestPacket.CAPABILITY_VOXEL_SECTION;
        int viewDistance = ServerConfig.allvrLodDistance;
        for (long[] entry : entries) {
            int lvl = (int) entry[0];
            long cellLong = entry[1];
            if (lvl < 0 || lvl > AllvrLodBands.MAX_LEVEL) {
                continue;
            }
            AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, cellLong);
            int distance = chebyshevToNode(player.getPosition(1.0f), pos);
            if (!AllvrLodBands.inBand(lvl, distance, viewDistance)) {
                player.connection.send(new ClientboundAllvrLodForgetPacket(lvl, cellLong));
                continue;
            }
            Request existing = this.requests[lvl].get(cellLong);
            if (existing != null) {
                if (existing.section != sectionCapable) {
                    // a request opened for the other wire backend — reject this
                    // join so its requester retries fresh rather than waiting on
                    // a result shaped for someone else (plan §6.1 capability)
                    player.connection.send(new ClientboundAllvrLodForgetPacket(lvl, cellLong));
                    continue;
                }
                if (!existing.requesters.contains(player.getUUID())) {
                    existing.requesters.add(player.getUUID());
                }
                continue;
            }
            if (sectionCapable) {
                byte[] cached = this.sectionCache[lvl].get(cellLong);
                if (cached != null) {
                    player.connection.send(new ClientboundAllvrLodSectionPacket(
                        ClientboundAllvrLodSectionPacket.PROTOCOL_VERSION, -1L, lvl, cellLong,
                        (int) this.gens[lvl].get(cellLong), cached));
                    continue;
                }
            } else {
                long[] cached = this.cache[lvl].get(cellLong);
                if (cached != null) {
                    player.connection.send(new ClientboundAllvrLodMeshPacket(lvl, cellLong, cached));
                    continue;
                }
            }
            if (this.requests[lvl].size() >= MAX_INFLIGHT) {
                player.connection.send(new ClientboundAllvrLodForgetPacket(lvl, cellLong));
                continue;
            }
            Request req = new Request(this.gens[lvl].get(cellLong), sectionCapable);
            req.requesters.add(player.getUUID());
            this.requests[lvl].put(cellLong, req);
            this.prepQueue.add(new PrepJob(lvl, cellLong, pos, sectionCapable));
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
            long cellLong = AllvrCubePos.asLong(pos.getX() >> (5 + lvl), pos.getY() >> (5 + lvl), pos.getZ() >> (5 + lvl));
            this.gens[lvl].put(cellLong, this.gens[lvl].get(cellLong) + 1);
            this.requests[lvl].remove(cellLong);
            this.cacheRemove(lvl, cellLong);
            this.dirtyNodes[lvl].add(cellLong);
        }
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
        this.results.clear();
        this.prepQueue.clear();
    }

    // ------------------------------------------------------------------
    // cache
    // ------------------------------------------------------------------

    private void cachePut(int lvl, long cellLong, long[] quads) {
        long[] old = this.cache[lvl].put(cellLong, quads);
        if (old != null) {
            this.cacheBytes -= old.length << 3;
        }
        this.cacheBytes += quads.length << 3;
        while (this.cacheBytes > CACHE_BUDGET_BYTES) {
            var it = this.cache[lvl].entrySet().iterator();
            if (!it.hasNext()) {
                CreateManaIndustry.LOGGER.warn("[Allvr] LOD cache over budget with empty level map");
                break;
            }
            var eldest = it.next();
            it.remove();
            this.cacheBytes -= eldest.getValue().length << 3;
        }
    }

    /** Same LRU discipline as {@link #cachePut} for section payloads. */
    private void sectionCachePut(int lvl, long cellLong, byte[] payload) {
        byte[] old = this.sectionCache[lvl].put(cellLong, payload);
        if (old != null) {
            this.cacheBytes -= old.length;
        }
        this.cacheBytes += payload.length;
        while (this.cacheBytes > CACHE_BUDGET_BYTES) {
            var it = this.sectionCache[lvl].entrySet().iterator();
            if (!it.hasNext()) {
                break; // the quad cache holds the remaining budget pressure
            }
            var eldest = it.next();
            it.remove();
            this.cacheBytes -= eldest.getValue().length;
        }
    }

    private long cacheRemove(int lvl, long cellLong) {
        long[] old = this.cache[lvl].remove(cellLong);
        long freed = old == null ? 0L : old.length << 3;
        byte[] oldSection = this.sectionCache[lvl].remove(cellLong);
        if (oldSection != null) {
            freed += oldSection.length;
        }
        this.cacheBytes -= freed;
        return freed;
    }

    /** Drops cache entries outside every player's R×1.25 hysteresis (4a decision). */
    private void evictCache(List<ServerPlayer> players, int viewDistance) {
        long limit = (long) viewDistance * 5 / 4;
        for (int lvl = 0; lvl <= AllvrLodBands.MAX_LEVEL; lvl++) {
            var it = this.cache[lvl].entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                AllvrLodPos pos = AllvrLodPos.fromCellLong(lvl, e.getKey());
                if (!anyPlayerNear(players, pos, limit)) {
                    it.remove();
                    this.cacheBytes -= e.getValue().length << 3;
                }
            }
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
