package com.iridium126.createmanaindustry.dimension.cube;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrBlockUpdatePacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrCubePacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrForgetCubePacket;
import com.iridium126.createmanaindustry.dimension.storage.AllvrCubeIoWorker;
import com.iridium126.createmanaindustry.dimension.storage.AllvrCubeSerializer;
import com.iridium126.createmanaindustry.dimension.storage.AllvrCubeSnapshot;
import com.iridium126.createmanaindustry.dimension.storage.AllvrPersistedOverlay;
import com.iridium126.createmanaindustry.dimension.storage.AllvrRegionCubeStorage;
import com.iridium126.createmanaindustry.dimension.storage.AllvrStorageDiagnostics;

/**
 * Server-side registry of loaded cubes for one allay-dimension
 * {@link ServerLevel} — the cube-world analogue of {@code ChunkMap} +
 * {@code ClientChunkCache}:
 * <ul>
 *   <li>block access: {@code Level}'s get/setBlockState mixins route here
 *       when the level is the allay dimension;</li>
 *   <li>loading: <b>load-before-generate</b> — a cube request first checks
 *       memory, then the persistence layer (pending map + region3d index),
 *       and only generates when no record exists. Corrupt records keep the
 *       cube unloaded and are never regenerated over (plan §6);</li>
 *   <li>persistence: dirty cubes are serialized into the
 *       {@link AllvrCubeIoWorker}'s latest-wins pending map on a per-tick
 *       budget (§11), far-cube unload is save-before-unload, and
 *       {@link #saveAll}/{@link #close} mirror vanilla autosave, {@code
 *       /save-all flush} and shutdown semantics (plan §7.3);</li>
 *   <li>light: nothing here touches the vanilla light engine; rendering light
 *       is client-side (roadmap phase 5), gameplay light queries stay vanilla
 *       defaults until the gameplay stage (phase 7).</li>
 * </ul>
 * All cube/BE/section access happens on the server thread; the worker only
 * ever sees immutable {@link AllvrCubeSnapshot}s.
 */
public final class AllvrCubeMap {

    /** Per-player server-memory load radius, in cubes (mirrors CC3 verticalViewDistance=8). */
    private static final int GEN_RADIUS = 8;
    /** Per-player client subscription radii (xz, y) — smaller vertically, islands span ~7 cubes. */
    private static final int SEND_XZ_RADIUS = 8;
    private static final int SEND_Y_RADIUS = 4;
    /** Forget margin beyond the send radii (hysteresis against jitter at the edge). */
    private static final int FORGET_XZ_RADIUS = SEND_XZ_RADIUS + 2;
    private static final int FORGET_Y_RADIUS = SEND_Y_RADIUS + 2;
    /** Max cubes streamed per player per tick. */
    private static final int SEND_BUDGET_PER_TICK = 24;
    /** Shell-load time budget per tick. */
    private static final long TICK_BUDGET_NANOS = 3_000_000L;
    /** Far-cube unload scan cadence (ticks). */
    private static final int UNLOAD_SCAN_TICKS = 40;
    /**
     * Session cube cap. Uniform island-interior cubes are a few hundred bytes
     * but shell cubes carry real section data; past the cap only cubes within
     * ring 2 of a player generate (protects against runaway memory on very
     * long sessions).
     */
    private static final int MAX_LOADED_CUBES = 250_000;

    /** Snapshot maintenance queue budget per tick (plan §11): 8 cubes or 1 ms, first limit stops. */
    private static final int SNAPSHOT_QUEUE_BUDGET = 8;
    private static final long SNAPSHOT_BUDGET_NANOS = 1_000_000L;
    /** Minimum ticks between two background snapshots of the same cube (plan §11). */
    private static final int SNAPSHOT_COOLDOWN_TICKS = 200;
    /** Cooldown before a failed/corrupt persisted cube load is retried. */
    private static final int LOAD_RETRY_TICKS = 200;

    private final ServerLevel level;
    private final Registry<net.minecraft.world.level.biome.Biome> biomeRegistry;
    private final AllvrIslandFieldGenerator generator;
    private final Long2ObjectOpenHashMap<AllvrCube> cubes = new Long2ObjectOpenHashMap<>();
    /**
     * Cross-session persisted/edited index: cube keys with a pending or disk
     * record. Region records existing ⟺ the cube overrides the deterministic
     * generator on load; this set replaces the old session-only
     * {@code editedCubes} (plan §2.2).
     */
    private final LongOpenHashSet persistedIndex = new LongOpenHashSet();
    /** Attached by the ServerLevel mixin next to the LOD map; null until then. */
    private com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap lodMap;
    /**
     * Cubes that hold block entities — the ticking worklist (kept tiny: most
     * cubes are pure terrain). Vanilla's per-chunk {@code TickingTracker} is
     * deliberately not involved; see {@link #tickBlockEntities}.
     */
    private final Long2ObjectOpenHashMap<AllvrCube> beCubes = new Long2ObjectOpenHashMap<>();
    private final Map<UUID, Subscription> subscriptions = new java.util.HashMap<>();
    private final AllvrCubeIoWorker worker;
    private final AllvrStorageDiagnostics diagnostics = new AllvrStorageDiagnostics();

    /** Dirty snapshot maintenance queue (FIFO, deduplicated by {@link #snapshotQueued}). */
    private final ArrayDeque<Long> snapshotQueue = new ArrayDeque<>();
    private final LongOpenHashSet snapshotQueued = new LongOpenHashSet();
    /** Cube key → last successful background snapshot (cooldown bookkeeping). */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap lastSnapshotTick = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** Cube key → last failed persisted-load attempt (retry throttle). */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap loadCooldown = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private boolean loggedCapWarning;
    private int unloadScanTicks;
    private boolean closed;

    /** Per-player client subscription state: which cube keys have been streamed. */
    private static final class Subscription {
        final LongOpenHashSet sent = new LongOpenHashSet();
        AllvrCubePos lastCube;
    }

    public AllvrCubeMap(ServerLevel level) {
        this.level = level;
        this.biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.generator = new AllvrIslandFieldGenerator(level.getSeed());
        Path folder = DimensionType.getStorageFolder(level.dimension(),
            level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region3d");
        try {
            this.worker = new AllvrCubeIoWorker(new AllvrRegionCubeStorage(folder), this.diagnostics);
            this.persistedIndex.addAll(this.worker.persistedKeysSnapshot());
        } catch (IOException e) {
            // fail closed: a half-broken region3d must never degrade into
            // "keep running but silently drop saves" (plan §7.3)
            throw new IllegalStateException("[Allvr] failed to initialize region3d persistence for "
                + level.dimension().location() + " — refusing to run (fail closed). Repair or remove "
                + folder, e);
        }
        CreateManaIndustry.LOGGER.info("[Allvr] cube persistence ready ({} persisted record(s), {})",
            this.persistedIndex.size(), folder);
    }

    public ServerLevel getLevel() {
        return level;
    }

    /** Persistence diagnostics snapshot (plan §7.3). */
    public AllvrStorageDiagnostics diagnostics() {
        return this.diagnostics;
    }

    // ------------------------------------------------------------------
    // block access (called from the Level mixins)
    // ------------------------------------------------------------------

    /**
     * Block state of a position within the dimension. Unloaded cubes read as
     * void air — mirroring vanilla's behavior for unloaded chunks and keeping
     * incidental vanilla scans (light engine, collisions over borders) from
     * triggering mass generation.
     */
    public BlockState getBlockState(BlockPos pos) {
        if (!AllvrDimensionLimits.isInBounds(pos)) {
            return net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState();
        }
        AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
        return cube == null ? net.minecraft.world.level.block.Blocks.VOID_AIR.defaultBlockState()
            : cube.getBlockState(pos);
    }

    public boolean setBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft) {
        if (!AllvrDimensionLimits.isInBounds(pos)) {
            return false;
        }
        // vanilla Level#setBlock loads (or restores from region3d) the target
        // cube before writing — mirror that: getOrGenerate may return null for
        // a corrupt persisted cube, in which case the write fails closed.
        AllvrCube cube = getOrGenerate(AllvrCubePos.of(pos));
        if (cube == null) {
            return false;
        }
        pos = pos.immutable();
        BlockState oldState = cube.setBlockState(pos, newState, false);
        if (oldState == null) {
            return false; // same-state write — no new mutation version (§10.2)
        }

        long cubeKey = cube.getPos().asLong();
        this.persistedIndex.add(cubeKey);
        cube.markDirty();
        this.queueSnapshot(cubeKey, false);
        if (this.lodMap != null) {
            this.lodMap.onBlockChanged(pos);
        }

        updateBlockEntity(cube, pos, newState);

        // light emitter tracking (wire "light source events"; consumed by the
        // phase-3 synthetic light sampler)
        int oldEmission = oldState.getLightEmission(level, pos);
        int newEmission = newState.getLightEmission(level, pos);
        if (oldEmission > 0) {
            cube.removeEmitter(pos);
        }
        if (newEmission > 0) {
            cube.putEmitter(pos, newEmission);
        }

        // mirror of Level#markAndNotifyBlock, minus light engine / chunk-status
        // concerns (cubes have no LevelChunk)
        if ((flags & 2) != 0) {
            // vanilla sendBlockUpdated equivalent: authoritative per-block push
            // to subscribed clients (the initiating player's own prediction
            // re-applies the same state idempotently)
            sendBlockUpdate(pos, newState);
        }
        if ((flags & 1) != 0) {
            level.blockUpdated(pos, oldState.getBlock());
            if (newState.hasAnalogOutputSignal()) {
                level.updateNeighbourForOutputSignal(pos, newState.getBlock());
            }
        }
        if ((flags & 16) == 0 && recursionLeft > 0) {
            int i = flags & -34;
            oldState.updateIndirectNeighbourShapes(level, pos, i, recursionLeft - 1);
            newState.updateNeighbourShapes(level, pos, i, recursionLeft - 1);
            newState.updateIndirectNeighbourShapes(level, pos, i, recursionLeft - 1);
        }
        return true;
    }

    /**
     * Route for {@code Level#blockEntityChanged} (BE {@code setChanged()}) —
     * the hard requirement for BE content persistence (plan §7.4): vanilla's
     * path would dirty the empty column chunk instead of the ALLVR cube.
     * Only already-loaded cubes with a real BE at the position are marked;
     * anything else was not a cube BE change.
     */
    public void markBlockEntityDirty(BlockPos pos) {
        AllvrCube cube = this.cubes.get(AllvrCubePos.asLong(pos));
        if (cube == null || !cube.isLoaded() || cube.getBlockEntity(pos) == null) {
            return;
        }
        cube.markDirty();
        this.persistedIndex.add(cube.getPos().asLong());
        this.queueSnapshot(cube.getPos().asLong(), false);
    }

    /** Authoritative per-block push to every client subscribed to this cube
     *  (one packet build, N sends; level.players() is the allay dimension). */
    private void sendBlockUpdate(BlockPos pos, BlockState state) {
        long cubeKey = AllvrCubePos.asLong(pos);
        ClientboundAllvrBlockUpdatePacket packet = new ClientboundAllvrBlockUpdatePacket(
            cubeKey, AllvrCube.localIndex(pos), net.minecraft.world.level.block.Block.getId(state));
        for (ServerPlayer player : level.players()) {
            Subscription sub = subscriptions.get(player.getUUID());
            if (sub != null && sub.sent.contains(cubeKey)) {
                player.connection.send(packet);
            }
        }
    }

    private void updateBlockEntity(AllvrCube cube, BlockPos pos, BlockState newState) {
        cube.updateBlockEntity(level, pos, newState);
        refreshBeCube(cube);
    }

    /** Keeps the block-entity worklist in step with a cube's BE set. */
    private void refreshBeCube(AllvrCube cube) {
        long key = cube.getPos().asLong();
        if (cube.hasBlockEntities()) {
            this.beCubes.put(key, cube);
        } else {
            this.beCubes.remove(key);
        }
    }

    public BlockEntity getBlockEntity(BlockPos pos) {
        AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
        return cube == null ? null : cube.getBlockEntity(pos);
    }

    /** Whether the cube has a pending or disk record (LOD overlay hint). */
    public boolean isPersisted(long cubeKey) {
        return this.persistedIndex.contains(cubeKey);
    }

    /** Wired by the ServerLevel mixin after creating both maps. */
    public void setLodMap(com.iridium126.createmanaindustry.dimension.lod.AllvrLodMap lodMap) {
        this.lodMap = lodMap;
    }

    public int getLoadedCubeCount() {
        return cubes.size();
    }

    /** Number of cube keys with a persistence record (pending ∪ disk). */
    public int getPersistedCubeCount() {
        return this.persistedIndex.size();
    }

    /** Diagnostics (plan §7.3): loaded cubes that still owe a snapshot. */
    public int getDirtyCubeCount() {
        int dirty = 0;
        for (AllvrCube cube : this.cubes.values()) {
            if (cube.needsSnapshot()) {
                dirty++;
            }
        }
        return dirty;
    }

    // ------------------------------------------------------------------
    // loading
    // ------------------------------------------------------------------

    /** Synchronous generation/lookup used by the tick driver and direct paths. */
    public AllvrCube getOrGenerate(AllvrCubePos cpos) {
        return getOrGenerate(cpos.getX(), cpos.getY(), cpos.getZ());
    }

    /**
     * Load-before-generate (plan §7.3): memory → persistence (blocking one
     * I/O wait, first version) → deterministic generation. Returns
     * {@code null} only when a persisted record exists but cannot be restored
     * (corrupt/failed) — the cube stays unloaded and the record is never
     * regenerated over.
     */
    public AllvrCube getOrGenerate(int cubeX, int cubeY, int cubeZ) {
        long key = AllvrCubePos.asLong(cubeX, cubeY, cubeZ);
        AllvrCube cube = cubes.get(key);
        if (cube != null) {
            return cube;
        }
        if (this.persistedIndex.contains(key)) {
            return this.loadPersisted(key, AllvrCubePos.of(cubeX, cubeY, cubeZ));
        }
        if (this.closed) {
            return null;
        }
        cube = new AllvrCube(AllvrCubePos.of(cubeX, cubeY, cubeZ), biomeRegistry);
        generator.generate(cube);
        cubes.put(key, cube);
        cube.onLoad(level);
        this.diagnostics.cubesGenerated.incrementAndGet();
        return cube;
    }

    /** Restores one persisted cube (service thread, one blocking I/O wait). */
    private AllvrCube loadPersisted(long key, AllvrCubePos pos) {
        if (this.closed) {
            return null;
        }
        long now = this.level.getGameTime();
        if (this.loadCooldown.containsKey(key) && now - this.loadCooldown.get(key) < LOAD_RETRY_TICKS) {
            return null;
        }
        this.loadCooldown.put(key, now);
        try {
            Optional<CompoundTag> nbt = this.worker.loadBlocking(pos);
            if (nbt.isEmpty()) {
                CreateManaIndustry.LOGGER.error(
                    "[Allvr] persisted index contains {} but no record was found — cube stays unloaded (fail closed)", pos);
                return null;
            }
            AllvrCube cube = AllvrCubeSerializer.load(pos, nbt.get(), this.level);
            cube.markPersistedOverride();
            cube.markQueued(cube.mutationVersion()); // clean: pending/disk IS this state
            this.cubes.put(key, cube);
            if (cube.hasBlockEntities()) {
                this.beCubes.put(key, cube);
            }
            cube.onLoad(this.level);
            cube.rebuildDerivedState(this.level);
            this.diagnostics.cubesLoadedFromDisk.incrementAndGet();
            CreateManaIndustry.LOGGER.info("[Allvr] restored cube {} from region3d", pos);
            return cube;
        } catch (Exception e) {
            this.diagnostics.noteIoError(e.toString());
            CreateManaIndustry.LOGGER.error(
                "[Allvr] failed to load persisted cube {} — stays unloaded; regeneration suppressed", pos, e);
            return null;
        }
    }

    /** Async decode of a persisted cube into the LOD-only overlay (plan §7.6). */
    public CompletableFuture<AllvrPersistedOverlay> loadOverlayAsync(AllvrCubePos pos) {
        Registry<net.minecraft.world.level.biome.Biome> biomes = this.biomeRegistry;
        return this.worker.load(pos).thenApply(nbt -> {
            if (nbt.isEmpty()) {
                throw new java.util.concurrent.CompletionException(new java.io.IOException(
                    "[Allvr] persisted index contains " + pos + " but no record was found"));
            }
            try {
                return AllvrCubeSerializer.decodeOverlay(pos, nbt.get(), biomes);
            } catch (java.io.IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    /**
     * Per-tick driver: drains the snapshot maintenance queue (budgeted), then
     * on join/teleport synchronously generates + streams the player's
     * 3×3×3 cube neighborhood, then generates/shells outward within the
     * per-tick time budget while streaming cube data to each player's client
     * within the per-tick send budget. Cubes leaving the subscription range
     * (with hysteresis) are forgotten client-side.
     */
    public void tick() {
        // drain before the players.isEmpty() early-return: an empty server
        // still owes its queued snapshots (vanilla autosave parity)
        this.drainSnapshotQueue();
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }

        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        boolean capReached = cubes.size() >= MAX_LOADED_CUBES;
        if (capReached && !loggedCapWarning) {
            loggedCapWarning = true;
            CreateManaIndustry.LOGGER.warn("[Allvr] loaded cube cap {} reached, limiting load radius", MAX_LOADED_CUBES);
        }

        for (ServerPlayer player : players) {
            AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
            Subscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new Subscription());
            if (sub.lastCube == null || chebyshev(pc, sub.lastCube) > 2) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            AllvrCube cube = getOrGenerate(pc.getX() + dx, pc.getY() + dy, pc.getZ() + dz);
                            if (cube == null) {
                                continue; // corrupt persisted cube — fail closed
                            }
                            long key = cube.getPos().asLong();
                            if (sub.sent.add(key)) {
                                player.connection.send(ClientboundAllvrCubePacket.of(cube, level.registryAccess()));
                            }
                        }
                    }
                }
                sub.lastCube = pc;
            }
        }

        for (ServerPlayer player : players) {
            AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
            Subscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new Subscription());
            if (capReached && chebyshev(pc, playerCubeCenter(sub)) > 2) {
                continue;
            }
            int sentCount = 0;
            genLoop:
            for (int r = 0; r <= GEN_RADIUS; r++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != r) {
                                continue;
                            }
                            int cx = pc.getX() + dx;
                            int cy = pc.getY() + dy;
                            int cz = pc.getZ() + dz;
                            long key = AllvrCubePos.asLong(cx, cy, cz);
                            boolean inSendRange = Math.abs(dy) <= SEND_Y_RADIUS; // xz always <= GEN_RADIUS == SEND_XZ_RADIUS
                            if (sub.sent.contains(key) || (capReached && r > 2 && !inSendRange)) {
                                continue;
                            }
                            if (System.nanoTime() > deadline) {
                                break genLoop;
                            }
                            AllvrCube cube = getOrGenerate(cx, cy, cz);
                            if (cube == null) {
                                continue;
                            }
                            if (inSendRange && sentCount < SEND_BUDGET_PER_TICK && sub.sent.add(key)) {
                                player.connection.send(ClientboundAllvrCubePacket.of(cube, level.registryAccess()));
                                sentCount++;
                            }
                        }
                    }
                }
            }

            forgetOutOfRange(player, pc, sub);
        }

        this.tickBlockEntities(players);
        if (++this.unloadScanTicks >= UNLOAD_SCAN_TICKS) {
            this.unloadScanTicks = 0;
            this.unloadFarCubes(players);
        }
    }

    /**
     * Server block-entity ticking — the cube analogue of
     * {@code Level#tickBlockEntities} (cube BEs are invisible to vanilla's
     * per-chunk TickingTracker). Vanilla parity: every game tick, no budget
     * (a skipped tick slows machines and breaks their determinism). A cube
     * ticks only while within the shell radius of some player — the
     * unload-distance equivalent of vanilla's simulation-distance gating.
     */
    private void tickBlockEntities(List<ServerPlayer> players) {
        if (this.beCubes.isEmpty()) {
            return;
        }
        // snapshot: a ticker can setBlock (adding/removing BEs → registry writes)
        for (AllvrCube cube : this.beCubes.values().toArray(new AllvrCube[0])) {
            if (!cube.hasBlockEntities()) {
                continue;
            }
            AllvrCubePos cpos = cube.getPos();
            for (ServerPlayer player : players) {
                if (chebyshev(AllvrCubePos.of(player.blockPosition()), cpos) <= GEN_RADIUS) {
                    cube.tickBlockEntities(level);
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // snapshot maintenance (plan §11)
    // ------------------------------------------------------------------

    /**
     * Queues a background snapshot for the cube. Player-edit calls respect
     * the §11 cooldown; {@code saveAll(false)} (vanilla autosave parity) and
     * unload/flush paths bypass it.
     */
    private void queueSnapshot(long key, boolean bypassCooldown) {
        if (this.snapshotQueued.contains(key)) {
            return;
        }
        if (!bypassCooldown) {
            long now = this.level.getGameTime();
            if (this.lastSnapshotTick.containsKey(key)
                && now - this.lastSnapshotTick.get(key) < SNAPSHOT_COOLDOWN_TICKS) {
                return;
            }
        }
        this.snapshotQueued.add(key);
        this.snapshotQueue.add(key);
    }

    private void drainSnapshotQueue() {
        if (this.snapshotQueue.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + SNAPSHOT_BUDGET_NANOS;
        int processed = 0;
        while (!this.snapshotQueue.isEmpty()) {
            if (processed >= SNAPSHOT_QUEUE_BUDGET || (processed > 0 && System.nanoTime() >= deadline)) {
                break;
            }
            long key = this.snapshotQueue.poll();
            this.snapshotQueued.remove(key);
            AllvrCube cube = this.cubes.get(key);
            if (cube == null || !cube.needsSnapshot()) {
                continue;
            }
            if (this.trySnapshot(cube)) {
                processed++;
            }
            // failure → dropped from the queue; the next edit, unload scan or
            // saveAll re-queues it
        }
    }

    /**
     * Serializes + enqueues one cube snapshot. Any failure leaves the cube in
     * memory (pinned) and returns {@code false} — never a partial record, and
     * never a silently dropped cube (plan §7.3).
     */
    private boolean trySnapshot(AllvrCube cube) {
        try {
            AllvrCubeSnapshot snapshot = AllvrCubeSerializer.snapshot(cube, this.level);
            this.worker.enqueue(snapshot);
            cube.markQueued(snapshot.version());
            this.lastSnapshotTick.put(cube.getPos().asLong(), this.level.getGameTime());
            this.diagnostics.snapshotsBuilt.incrementAndGet();
            return true;
        } catch (Exception e) {
            this.diagnostics.snapshotsFailed.incrementAndGet();
            this.diagnostics.noteIoError(e.toString());
            CreateManaIndustry.LOGGER.error("[Allvr] snapshot failed for cube {} — cube kept in memory",
                cube.getPos(), e);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // save / close (plan §7.3)
    // ------------------------------------------------------------------

    /**
     * {@code flush=false}: vanilla autosave semantics — every loaded dirty
     * cube joins the maintenance queue and the call returns immediately.
     * {@code flush=true}: {@code /save-all flush} semantics — every dirty
     * cube is snapshotted right now (no budget/cooldown) and the call blocks
     * until the worker is drained and all region files are forced. Returns
     * whether everything reached the pending map (and, when flushing, disk).
     */
    public boolean saveAll(boolean flush) {
        long start = System.nanoTime();
        boolean ok = true;
        if (flush) {
            for (AllvrCube cube : this.cubes.values()) {
                if (cube.needsSnapshot() && !this.trySnapshot(cube)) {
                    ok = false;
                }
            }
            try {
                this.worker.flush();
            } catch (java.io.IOException e) {
                // never pretend success (§8): /save-all flush must see the failure
                CreateManaIndustry.LOGGER.error("[Allvr] allvr cube flush failed — pending writes kept for retry", e);
                throw new RuntimeException("[Allvr] allvr cube flush failed: " + e.getMessage(), e);
            }
        } else {
            for (AllvrCube cube : this.cubes.values()) {
                if (cube.needsSnapshot()) {
                    this.queueSnapshot(cube.getPos().asLong(), true);
                }
            }
        }
        this.diagnostics.lastSaveDurationMs = (System.nanoTime() - start) / 1_000_000L;
        return ok;
    }

    /**
     * Idempotent shutdown: one last noSave-respecting save/flush, then the
     * worker (pending drain + region handles). Never throws — the
     * {@code ServerLevel#close} mixin relies on that (plan §7.5).
     */
    public boolean close() {
        if (this.closed) {
            return true;
        }
        this.closed = true;
        boolean ok = true;
        if (this.level.noSave) {
            CreateManaIndustry.LOGGER.info("[Allvr] level has noSave set — skipping final allvr save");
        } else {
            try {
                ok = this.saveAll(true);
            } catch (RuntimeException e) {
                ok = false; // saveAll logged the cause; close must not throw (§7.5)
            }
        }
        this.worker.close();
        CreateManaIndustry.LOGGER.info("[Allvr] cube persistence closed ({}): {}",
            ok ? "clean" : "with errors", this.diagnostics);
        return ok;
    }

    // ------------------------------------------------------------------
    // unloading
    // ------------------------------------------------------------------

    /**
     * Cubes beyond every player's forget margins leave memory —
     * save-before-unload (plan §7.3): a dirty cube is snapshotted and
     * enqueued first; on failure (or while {@code level.noSave} is set) it
     * stays pinned. Clean cubes unload freely — generated ones have no record
     * (nothing to save), persisted ones have their disk/pending copy.
     */
    private void unloadFarCubes(List<ServerPlayer> players) {
        if (this.cubes.isEmpty()) {
            return;
        }
        boolean noSave = this.level.noSave;
        LongList unload = null;
        LongIterator it = this.cubes.keySet().iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            AllvrCubePos cpos = AllvrCubePos.fromLong(key);
            boolean nearAnyPlayer = false;
            for (ServerPlayer player : players) {
                AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
                if (Math.abs(cpos.getX() - pc.getX()) <= FORGET_XZ_RADIUS
                    && Math.abs(cpos.getZ() - pc.getZ()) <= FORGET_XZ_RADIUS
                    && Math.abs(cpos.getY() - pc.getY()) <= FORGET_Y_RADIUS) {
                    nearAnyPlayer = true;
                    break;
                }
            }
            if (!nearAnyPlayer) {
                if (unload == null) {
                    unload = new LongArrayList();
                }
                unload.add(key);
            }
        }
        if (unload != null) {
            int dropped = 0;
            for (long key : unload) {
                AllvrCube cube = this.cubes.get(key);
                if (cube == null) {
                    continue;
                }
                if (cube.needsSnapshot()) {
                    if (noSave) {
                        continue; // dirty cubes are pinned while saving is off (§10.2)
                    }
                    if (!this.trySnapshot(cube)) {
                        continue; // snapshot failure → keep in memory, retry next scan
                    }
                }
                cube.onUnload();
                this.cubes.remove(key);
                this.beCubes.remove(key);
                dropped++;
                // persistedIndex keeps the key: record/pending exist ⟹ override
            }
            if (dropped > 0) {
                CreateManaIndustry.LOGGER.debug("[Allvr] unloaded {} far cubes ({} remain)",
                    dropped, this.cubes.size());
            }
        }
    }

    private static AllvrCubePos playerCubeCenter(Subscription sub) {
        return sub.lastCube != null ? sub.lastCube : AllvrCubePos.of(0, 0, 0);
    }

    private void forgetOutOfRange(ServerPlayer player, AllvrCubePos pc, Subscription sub) {
        if (sub.sent.isEmpty()) {
            return;
        }
        LongIterator it = sub.sent.iterator();
        LongList forget = null;
        while (it.hasNext()) {
            long key = it.nextLong();
            AllvrCubePos cpos = AllvrCubePos.fromLong(key);
            int dxCube = cpos.getX() - pc.getX();
            int dyCube = cpos.getY() - pc.getY();
            int dzCube = cpos.getZ() - pc.getZ();
            if (Math.max(Math.abs(dxCube), Math.abs(dzCube)) > FORGET_XZ_RADIUS
                || Math.abs(dyCube) > FORGET_Y_RADIUS) {
                if (forget == null) {
                    forget = new LongArrayList();
                }
                forget.add(key);
            }
        }
        if (forget != null) {
            for (long key : forget) {
                sub.sent.remove(key);
                player.connection.send(new ClientboundAllvrForgetCubePacket(key));
            }
        }
    }

    /** Drops one player's subscription so cubes are re-streamed from scratch. */
    public void resetPlayer(UUID uuid) {
        subscriptions.remove(uuid);
    }

    private static int chebyshev(AllvrCubePos a, AllvrCubePos b) {
        return Math.max(Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())),
            Math.abs(a.getZ() - b.getZ()));
    }

    // ------------------------------------------------------------------
    // debug helpers (used by /data-style server-side queries)
    // ------------------------------------------------------------------

    public AllvrCube getLoadedCube(long key) {
        return cubes.get(key);
    }

    /** Null when the cube is not loaded; never generates. */
    public AllvrCube peek(BlockPos pos) {
        return cubes.get(AllvrCubePos.asLong(pos));
    }

    /** Worker access for tests/diagnostics; production code should not need it. */
    AllvrCubeIoWorker worker() {
        return this.worker;
    }
}
