package com.iridium126.createmanaindustry.dimension.cube;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
import net.minecraft.world.level.LightLayer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits;
import com.iridium126.createmanaindustry.dimension.gen.AllvrIslandFieldGenerator;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrBlockUpdatePacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrCubePacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrForgetCubePacket;
import com.iridium126.createmanaindustry.dimension.storage.AllvrCubeCorruptedException;
import com.iridium126.createmanaindustry.dimension.storage.AllvrCubeIoWorker;
import com.iridium126.createmanaindustry.dimension.storage.AllvrCubeSerializer;
import com.iridium126.createmanaindustry.dimension.storage.AllvrCubeSnapshot;
import com.iridium126.createmanaindustry.dimension.storage.AllvrRegionCubeStorage;
import com.iridium126.createmanaindustry.dimension.storage.AllvrStorageDiagnostics;
import com.iridium126.createmanaindustry.dimension.light.AllvrLightEngine;

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
 *   <li>light: a sparse vanilla-style increase/decrease engine tracks both
 *       block and sky light for loaded cubes; vanilla column lighting remains
 *       untouched outside this dimension.</li>
 * </ul>
 * Loaded cube/BE/section access happens on the server thread. Background
 * workers only build unpublished cubes and hand them back through the
 * completion queue; persistence still receives immutable
 * {@link AllvrCubeSnapshot}s.
 */
public final class AllvrCubeMap {

    /** Per-player server-memory/simulation shell, in cubes. */
    private static final int GEN_RADIUS = 8;
    /**
     * Per-player vertical client subscription radius. Horizontal subscription
     * distance comes from the vanilla view distance reported by
     * {@link ServerPlayer#requestedViewDistance()} and is evaluated with the
     * vanilla circular chunk geometry. Y intentionally remains independent.
     */
    private static final int DEFAULT_SEND_Y_RADIUS = 8;
    /** Max cubes streamed per player per tick. */
    private static final int SEND_BUDGET_PER_TICK = 24;
    /** Bound background work queued while a player moves through an island. */
    private static final int MAX_PENDING_GENERATIONS = 256;
    /** Bound disk reads for persisted cubes while a player moves. */
    private static final int MAX_PENDING_PERSISTED_LOADS = 256;
    /**
     * Chunk loading in vanilla keeps the region-file mailbox independent from
     * the expensive chunk deserializer.  Keep the same boundary here: the
     * region worker only reads/decompresses NBT, while a small bounded pool
     * validates the cube schema before the main thread binds its live state.
     */
    private static final int PERSISTENCE_DECODE_WORKERS =
        Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    /** Shell-load time budget per tick. */
    private static final long TICK_BUDGET_NANOS = 3_000_000L;
    /** Keep async generation/read completions from monopolising the tick. */
    private static final int COMPLETION_INSTALL_BUDGET = 16;
    private static final long COMPLETION_INSTALL_BUDGET_NANOS = 2_000_000L;
    /**
     * Safety cadence for cubes loaded by direct gameplay access. Normal
     * player-tracking changes trigger the unload pass immediately, so stable
     * players do not repeatedly scan the entire residency map.
     */
    private static final int UNLOAD_SCAN_TICKS = 200;
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
    private final AllvrLightEngine lightEngine;
    private final Long2ObjectOpenHashMap<AllvrCube> cubes = new Long2ObjectOpenHashMap<>();
    /**
     * Cross-session persisted/edited index: cube keys with a pending or disk
     * record. Region records existing ⟺ the cube overrides the deterministic
     * generator on load; this set replaces the old session-only
     * {@code editedCubes} (plan §2.2).
     */
    private final LongOpenHashSet persistedIndex = new LongOpenHashSet();
    /**
     * Cubes that hold block entities — the ticking worklist (kept tiny: most
     * cubes are pure terrain). Vanilla's per-chunk {@code TickingTracker} is
     * deliberately not involved; see {@link #tickBlockEntities}.
     */
    private final Long2ObjectOpenHashMap<AllvrCube> beCubes = new Long2ObjectOpenHashMap<>();
    /** Cubes with a simulation ticket for at least one current player. */
    private final LongOpenHashSet tickingBeCubeKeys = new LongOpenHashSet();
    private boolean simulationTicketsDirty = true;
    private final Map<UUID, Subscription> subscriptions = new java.util.HashMap<>();
    private final AllvrCubeIoWorker worker;
    private final ThreadPoolExecutor persistenceDecodeExecutor = new ThreadPoolExecutor(
        PERSISTENCE_DECODE_WORKERS, PERSISTENCE_DECODE_WORKERS,
        0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING_PERSISTED_LOADS * 2),
        r -> {
            Thread thread = new Thread(r, "allvr-persist-decode");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<Long, CompletableFuture<AllvrCube>> pendingGenerations = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<GeneratedCube> completedGenerations = new ConcurrentLinkedQueue<>();
    /** Persisted cube reads use the vanilla ChunkMap-style async handoff. */
    private final ConcurrentHashMap<Long, CompletableFuture<AllvrCube>> pendingPersistedLoads = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PersistedCubeLoad> completedPersistedLoads = new ConcurrentLinkedQueue<>();
    /** Geometry ticket cache; it avoids repeating island hashes for void rings. */
    private final Map<Long, Boolean> islandTicketCache = new LinkedHashMap<>(8192, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) { return size() > 8192; }
    };
    private final AllvrStorageDiagnostics diagnostics = new AllvrStorageDiagnostics();

    /** Dirty snapshot maintenance queue (FIFO, deduplicated by {@link #snapshotQueued}). */
    private final ArrayDeque<Long> snapshotQueue = new ArrayDeque<>();
    private final LongOpenHashSet snapshotQueued = new LongOpenHashSet();
    /** Cube key → last successful background snapshot (cooldown bookkeeping). */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap lastSnapshotTick = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** Cube key → last failed persisted-load attempt (retry throttle). */
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap loadCooldown = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    /** Permanently failed records are not retried every cooldown interval. */
    private final LongOpenHashSet failedPersistedLoads = new LongOpenHashSet();
    /**
     * Vanilla sends an already-built chunk packet to every eligible player.
     * Keep a small access-ordered cache because Allay cube payloads are
     * immutable for a cube mutation version and are often sent to several
     * players during the same exploration burst.
     */
    private static final int CUBE_PACKET_CACHE_LIMIT = 512;
    private final Map<Long, CachedCubePacket> cubePacketCache =
        new LinkedHashMap<>(CUBE_PACKET_CACHE_LIMIT, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, CachedCubePacket> eldest) {
                return size() > CUBE_PACKET_CACHE_LIMIT;
            }
        };
    private boolean loggedCapWarning;
    private int unloadScanTicks;
    private boolean closed;

    /** Per-player client subscription state: which cube keys have been streamed. */
    private static final class Subscription {
        final LongOpenHashSet sent = new LongOpenHashSet();
        AllvrCubePos lastCube;
        /** Vanilla's tracking center is a chunk, even though storage is a cube. */
        int lastPlayerChunkX = Integer.MIN_VALUE;
        int lastPlayerChunkZ = Integer.MIN_VALUE;
        /** Resume the shell scan where the per-tick budget stopped. */
        int scanRadius;
        int scanIndex;
        int renderDistanceChunks = AllvrVanillaRenderDistance.MIN_CHUNKS;
        int sendYRadius = DEFAULT_SEND_Y_RADIUS;
        /** A stable player must not rescan the full shell every tick. */
        boolean scanDirty = true;
        boolean forgetDirty = true;
    }

    /**
     * A completed request retains its request future.  This is the cube
     * equivalent of ChunkMap checking that a ChunkHolder future is still the
     * current one before publishing it; a completion that lost its ticket is
     * never allowed to resurrect a cube.
     */
    private record GeneratedCube(long key, CompletableFuture<AllvrCube> request,
                                 AllvrCube cube, Throwable failure) {}
    private record PersistedCubeLoad(long key, CompletableFuture<AllvrCube> future,
        AllvrCube cube, Throwable failure) {}
    private record CachedCubePacket(AllvrCube cube, long mutationVersion,
                                    ClientboundAllvrCubePacket packet) {}

    public AllvrCubeMap(ServerLevel level) {
        this.level = level;
        this.biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        this.generator = new AllvrIslandFieldGenerator(level);
        this.lightEngine = new AllvrLightEngine(level, new AllvrLightEngine.Access() {
            @Override
            public BlockState getBlockState(BlockPos pos) {
                return AllvrCubeMap.this.getBlockState(pos);
            }

            @Override
            public boolean isLoaded(BlockPos pos) {
                return cubes.containsKey(AllvrCubePos.asLong(pos));
            }

            @Override
            public BlockEntity getBlockEntity(BlockPos pos) {
                return AllvrCubeMap.this.getBlockEntity(pos);
            }
        });
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
        BlockState oldState = cube.getBlockState(pos);
        if (oldState == newState || oldState.equals(newState)) {
            return false; // same-state write — no new mutation version (§10.2)
        }
        cube.setBlockState(pos, newState, false);
        oldState.onRemove(level, pos, newState, false);

        long cubeKey = cube.getPos().asLong();
        // setBlockState already marks the cube dirty.  The persistence index
        // is updated after the snapshot enters the IO worker, so a failed
        // first snapshot cannot make a missing record look loadable later.
        this.queueSnapshot(cubeKey, false);

        updateBlockEntity(cube, pos, newState);
        newState.onPlace(level, pos, oldState, false);

        this.lightEngine.onBlockChanged(pos);

        // Mirror Level#markAndNotifyBlock's neighbour/update semantics;
        // light propagation was queued above and chunk-status work is omitted
        // because cubes have no LevelChunk.
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
        this.queueSnapshot(cube.getPos().asLong(), false);
    }

    /**
     * Cube-aware equivalent of {@link Level#isLoaded(BlockPos)}.  This is a
     * residency query only: unlike a block read or write it must never create
     * a cube as a side effect.
     */
    public boolean isLoaded(BlockPos pos) {
        if (!AllvrDimensionLimits.isInBounds(pos)) {
            return false;
        }
        AllvrCube cube = this.cubes.get(AllvrCubePos.asLong(pos));
        return cube != null && cube.isLoaded();
    }

    /**
     * Cube-aware equivalent of {@link Level#setBlockEntity(BlockEntity)}.
     * Native LevelChunk registration is deliberately bypassed for cube
     * positions, while retaining vanilla's state/type validation and BE
     * lifecycle bookkeeping.
     */
    public void setBlockEntity(BlockEntity blockEntity) {
        BlockPos pos = blockEntity.getBlockPos().immutable();
        if (!AllvrDimensionLimits.isInBounds(pos)) {
            return;
        }
        AllvrCube cube = this.getOrGenerate(AllvrCubePos.of(pos));
        if (cube == null) {
            return;
        }
        BlockState state = cube.getBlockState(pos);
        if (!state.hasBlockEntity()) {
            CreateManaIndustry.LOGGER.warn(
                "Trying to set block entity {} at position {}, but state {} does not allow it",
                blockEntity, pos, state);
            return;
        }

        BlockState entityState = blockEntity.getBlockState();
        if (state != entityState) {
            if (!blockEntity.getType().isValid(state)) {
                CreateManaIndustry.LOGGER.warn(
                    "Trying to set block entity {} at position {}, but state {} is not valid for its type",
                    blockEntity, pos, state);
                return;
            }
            if (state.getBlock() != entityState.getBlock()) {
                CreateManaIndustry.LOGGER.warn(
                    "Block state mismatch on block entity {} in position {}, {} != {}, updating",
                    blockEntity, pos, entityState, state);
            }
            blockEntity.setBlockState(state);
        }

        blockEntity.setLevel(this.level);
        blockEntity.clearRemoved();
        BlockEntity previous = cube.getBlockEntity(pos);
        if (previous != null && previous != blockEntity) {
            previous.setRemoved();
        }
        cube.putBlockEntity(pos, blockEntity);
        cube.rebuildDerivedState(this.level);
        cube.markDirty();
        refreshBeCube(cube);
        queueSnapshot(cube.getPos().asLong(), false);
        this.level.addFreshBlockEntities(java.util.List.of(blockEntity));
    }

    /** Cube-aware equivalent of {@link Level#removeBlockEntity(BlockPos)}. */
    public BlockEntity removeBlockEntity(BlockPos pos) {
        if (!AllvrDimensionLimits.isInBounds(pos)) {
            return null;
        }
        AllvrCube cube = this.cubes.get(AllvrCubePos.asLong(pos));
        if (cube == null) {
            return null;
        }
        BlockEntity removed = cube.removeBlockEntity(pos);
        if (removed != null) {
            cube.markDirty();
            refreshBeCube(cube);
            queueSnapshot(cube.getPos().asLong(), false);
        }
        return removed;
    }

    /** Cube-aware equivalent of Level#loadedAndEntityCanStandOnFace. */
    public boolean loadedAndEntityCanStandOnFace(BlockPos pos, Entity entity, Direction direction) {
        if (!this.isLoaded(pos)) {
            return false;
        }
        return this.getBlockState(pos).entityCanStandOnFace(this.level, pos, entity, direction);
    }

    /** Authoritative per-block push to every client subscribed to this cube
     *  (one packet build, N sends; level.players() is the allay dimension). */
    private void sendBlockUpdate(BlockPos pos, BlockState state) {
        long cubeKey = AllvrCubePos.asLong(pos);
        BlockEntity blockEntity = this.cubes.get(cubeKey).getBlockEntity(pos);
        net.minecraft.nbt.CompoundTag tag = blockEntity == null ? null
            : blockEntity.getUpdateTag(this.level.registryAccess());
        ClientboundAllvrBlockUpdatePacket packet = new ClientboundAllvrBlockUpdatePacket(
            cubeKey, AllvrCube.localIndex(pos), net.minecraft.world.level.block.Block.getId(state), tag);
        for (ServerPlayer player : level.players()) {
            Subscription sub = subscriptions.get(player.getUUID());
            if (sub != null && sub.sent.contains(cubeKey)) {
                player.connection.send(packet);
            }
        }
    }

    private ClientboundAllvrCubePacket packetFor(AllvrCube cube) {
        long key = cube.getPos().asLong();
        long mutationVersion = cube.mutationVersion();
        CachedCubePacket cached = this.cubePacketCache.get(key);
        if (cached != null && cached.cube() == cube && cached.mutationVersion() == mutationVersion) {
            return cached.packet();
        }
        ClientboundAllvrCubePacket packet = ClientboundAllvrCubePacket.of(cube, level.registryAccess());
        this.cubePacketCache.put(key, new CachedCubePacket(cube, mutationVersion, packet));
        return packet;
    }

    /**
     * Incremental equivalent of ChunkMap's holder update when a cube becomes
     * resident. A residency event no longer wakes every subscription's full
     * shell scan; the scan remains responsible only for discovering missing
     * requests after a player ticket/view change.
     */
    private void announceCubeLoaded(AllvrCube cube) {
        long key = cube.getPos().asLong();
        ClientboundAllvrCubePacket packet = null;
        for (ServerPlayer player : level.players()) {
            Subscription sub = subscriptions.get(player.getUUID());
            if (sub == null) continue;
            AllvrCubePos center = AllvrCubePos.of(player.blockPosition());
            if (AllvrVanillaRenderDistance.isCubeWithinCylinder(
                cube.getPos().getX(), cube.getPos().getY(), cube.getPos().getZ(),
                AllvrVanillaRenderDistance.blockToChunk(player.getX()),
                AllvrVanillaRenderDistance.blockToChunk(player.getZ()),
                center.getY(), sub.renderDistanceChunks, sub.sendYRadius)
                && sub.sent.add(key)) {
                if (packet == null) packet = packetFor(cube);
                player.connection.send(packet);
            }
        }
    }

    /** Sends the forget edge immediately when an unneeded resident cube drops. */
    private void forgetCubeForPlayers(long key, List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            Subscription sub = subscriptions.get(player.getUUID());
            if (sub != null && sub.sent.remove(key)) {
                player.connection.send(new ClientboundAllvrForgetCubePacket(key));
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
            if (this.beCubes.put(key, cube) != cube) {
                this.simulationTicketsDirty = true;
            }
        } else {
            if (this.beCubes.remove(key) != null) {
                this.tickingBeCubeKeys.remove(key);
                this.simulationTicketsDirty = true;
            }
        }
    }

    public BlockEntity getBlockEntity(BlockPos pos) {
        AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
        return cube == null ? null : cube.getBlockEntity(pos);
    }

    /** Wired by the ServerLevel mixin after creating both maps. */
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
        if (AllvrDimensionLimits.isVanillaCube(cubeY)) throw new IllegalArgumentException("Vanilla chunk band");
        long key = AllvrCubePos.asLong(cubeX, cubeY, cubeZ);
        AllvrCube cube = cubes.get(key);
        if (cube != null) {
            return cube;
        }
        CompletableFuture<AllvrCube> pending = pendingGenerations.get(key);
        if (pending != null) {
            try {
                return installGenerated(key, pending, pending.join());
            } catch (java.util.concurrent.CancellationException e) {
                // A player can teleport back into a cube in the same tick that
                // its shell ticket was dropped. Reclaim the direct path by
                // generating the required cube synchronously below.
                pendingGenerations.remove(key, pending);
            } catch (java.util.concurrent.CompletionException e) {
                pendingGenerations.remove(key, pending);
                throw e;
            }
        }
        CompletableFuture<AllvrCube> pendingLoad = pendingPersistedLoads.get(key);
        if (pendingLoad != null) {
            try {
                // Direct gameplay writes still require a complete cube.  The
                // streaming path never takes this branch; it consumes the
                // async completion queue instead.
                return installPersistedCube(key, pendingLoad.join());
            } catch (java.util.concurrent.CancellationException e) {
                pendingPersistedLoads.remove(key, pendingLoad);
            } catch (java.util.concurrent.CompletionException e) {
                pendingPersistedLoads.remove(key, pendingLoad);
                Throwable cause = e.getCause();
                if (cause instanceof java.util.concurrent.CancellationException) {
                    return null;
                }
                quarantinePersistedLoad(key, cause != null ? cause : e);
                throw e;
            }
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
        refreshBeCube(cube);
        cube.onLoad(level);
        this.lightEngine.onCubeLoaded(cube);
        announceCubeLoaded(cube);
        // Generated terrain is itself the authoritative result of worldgen.
        // Persist it through the bounded snapshot queue so the next session
        // restores the complete cube without repeating noise/features.
        this.markGeneratedForPersistence(cube);
        this.diagnostics.cubesGenerated.incrementAndGet();
        return cube;
    }

    /** Main-thread completion handoff for background terrain builds. */
    private void drainCompletedGenerations() {
        long start = System.nanoTime();
        int installed = 0;
        GeneratedCube completed;
        while (installed < COMPLETION_INSTALL_BUDGET
            && System.nanoTime() - start < COMPLETION_INSTALL_BUDGET_NANOS
            && (completed = completedGenerations.poll()) != null) {
            // A completion can already be in the queue when the player moves
            // away.  ChunkMap drops that result when its holder/ticket is no
            // longer current; do the same before touching the live map.
            if (pendingGenerations.get(completed.key()) != completed.request()
                || !isNeededByAnyPlayer(AllvrCubePos.fromLong(completed.key()), level.players())) {
                pendingGenerations.remove(completed.key(), completed.request());
                continue;
            }
            if (completed.failure() != null) {
                pendingGenerations.remove(completed.key(), completed.request());
                if (completed.failure() instanceof java.util.concurrent.CancellationException) {
                    continue;
                }
                CreateManaIndustry.LOGGER.error("[Allvr] async cube generation failed for {}",
                    AllvrCubePos.fromLong(completed.key()), completed.failure());
                continue;
            }
            installGenerated(completed.key(), completed.request(), completed.cube());
            installed++;
        }
    }

    private AllvrCube installGenerated(long key, CompletableFuture<AllvrCube> request,
                                        AllvrCube cube) {
        if (request != null && !pendingGenerations.remove(key, request)) {
            return cubes.get(key);
        }
        AllvrCube existing = cubes.get(key);
        if (existing != null) return existing;
        if (closed || persistedIndex.contains(key)) return null;
        cubes.put(key, cube);
        refreshBeCube(cube);
        cube.onLoad(level);
        this.lightEngine.onCubeLoaded(cube);
        announceCubeLoaded(cube);
        this.markGeneratedForPersistence(cube);
        diagnostics.cubesGenerated.incrementAndGet();
        return cube;
    }

    /**
     * Queues one deterministic build; results are installed by the tick
     * thread.  The expensive stages deliberately run on Minecraft's normal
     * world-generation executor, just like ChunkMap/ChunkStatus.  Allay no
     * longer creates a second terrain worker pool that competes with the
     * vanilla chunk builder.
     */
    private void requestAsyncGeneration(int cubeX, int cubeY, int cubeZ) {
        if (closed || pendingGenerations.size() >= MAX_PENDING_GENERATIONS) return;
        long key = AllvrCubePos.asLong(cubeX, cubeY, cubeZ);
        if (cubes.containsKey(key) || persistedIndex.contains(key)) return;
        // Reserve the coordinate before submitting work. This closes the
        // small race where two callers could both submit the same expensive
        // noise build before either future became visible in the map.
        CompletableFuture<AllvrCube> future = new CompletableFuture<>();
        CompletableFuture<AllvrCube> previous = pendingGenerations.putIfAbsent(key, future);
        if (previous != null) return;
        try {
            AllvrCube cube = new AllvrCube(AllvrCubePos.of(cubeX, cubeY, cubeZ), biomeRegistry);
            // generateAsync() is backed by the same vanilla noise/worldgen
            // executor used by NoiseBasedChunkGenerator.  Only the future
            // completion is handed back to this main-thread-owned map.
            generator.generateAsync(cube).whenComplete((ignored, failure) -> {
                if (future.isCancelled()) {
                    return;
                }
                if (failure != null) {
                    future.completeExceptionally(failure);
                } else {
                    future.complete(cube);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            pendingGenerations.remove(key, future);
            future.completeExceptionally(failure);
            return;
        } catch (Throwable failure) {
            pendingGenerations.remove(key, future);
            future.completeExceptionally(failure);
            return;
        }
        future.whenComplete((cube, failure) -> {
            if (!closed) {
                completedGenerations.add(new GeneratedCube(key, future, cube, failure));
            }
        });
    }

    /** Returns a loaded cube or schedules generation without blocking the tick. */
    private AllvrCube getOrRequest(int cubeX, int cubeY, int cubeZ) {
        if (AllvrDimensionLimits.isVanillaCube(cubeY)) return null;
        long key = AllvrCubePos.asLong(cubeX, cubeY, cubeZ);
        AllvrCube cube = cubes.get(key);
        if (cube != null) return cube;
        if (persistedIndex.contains(key)) {
            if (failedPersistedLoads.contains(key)) {
                return null;
            }
            // ChunkMap never blocks the server tick on a disk read. Keep the
            // shell scan moving and install decoded data from the main-thread
            // completion queue on a later tick.
            requestAsyncPersistedLoad(cubeX, cubeY, cubeZ);
            return null;
        }
        // A queued build already passed the geometry ticket test. Avoid
        // re-running that test when another player or a later scan reaches it.
        if (pendingGenerations.containsKey(key)) return null;
        // Match CubicChunks' geometry/ticket filter: a void cube has no
        // server-side object or generation task. Clients already interpret a
        // missing cube as air, so sparse island space never fills the queue.
        Boolean intersects = islandTicketCache.get(key);
        if (intersects == null) {
            intersects = generator.intersectsIsland(cubeX, cubeY, cubeZ);
            islandTicketCache.put(key, intersects);
        }
        if (!intersects) return null;
        requestAsyncGeneration(cubeX, cubeY, cubeZ);
        return null;
    }

    /** Mirrors CubicChunks' dropQueuedCubeLoad: abandoned shells must not burn CPU. */
    private void cancelGenerationsOutside(List<ServerPlayer> players) {
        for (Map.Entry<Long, CompletableFuture<AllvrCube>> entry : pendingGenerations.entrySet()) {
            AllvrCubePos pending = AllvrCubePos.fromLong(entry.getKey());
            boolean needed = isNeededByAnyPlayer(pending, players);
            if (!needed && pendingGenerations.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().cancel(true);
            }
        }
        for (Map.Entry<Long, CompletableFuture<AllvrCube>> entry : pendingPersistedLoads.entrySet()) {
            AllvrCubePos pending = AllvrCubePos.fromLong(entry.getKey());
            boolean needed = isNeededByAnyPlayer(pending, players);
            if (!needed && pendingPersistedLoads.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().cancel(false);
                loadCooldown.remove(entry.getKey());
            }
        }
    }

    public int light(LightLayer type, BlockPos pos) {
        return type == LightLayer.BLOCK
            ? this.lightEngine.blockLight(pos) : this.lightEngine.skyLight(pos);
    }

    public int rawLight(BlockPos pos, int amount) {
        return Math.max(this.lightEngine.blockLight(pos),
            this.lightEngine.skyLight(pos) - amount);
    }

    public AllvrIslandFieldGenerator generator() { return this.generator; }

    /**
     * Restores one persisted cube for a direct gameplay access.  Streaming
     * requests use {@link #requestAsyncPersistedLoad} and never block here.
     */
    private AllvrCube loadPersisted(long key, AllvrCubePos pos) {
        if (this.closed) {
            return null;
        }
        if (this.failedPersistedLoads.contains(key)) {
            return null;
        }
        long now = this.level.getGameTime();
        if (this.loadCooldown.containsKey(key) && now - this.loadCooldown.get(key) < LOAD_RETRY_TICKS) {
            return null;
        }
        this.loadCooldown.put(key, now);
        long start = System.nanoTime();
        this.diagnostics.persistedLoadRequests.incrementAndGet();
        try {
            // Keep the I/O worker available for the next foreground read while
            // the bounded decode pool performs codec validation and emitter
            // rebuilding.  This is the same storage/deserializer split used
            // by vanilla's ChunkMap pipeline.
            AllvrCube cube = this.worker.load(pos)
                .thenApplyAsync(nbt -> decodePersistedCube(pos, nbt), this.persistenceDecodeExecutor)
                .join();
            AllvrCube installed = this.installPersistedCube(key, cube);
            this.diagnostics.persistedLoadNanos.addAndGet(System.nanoTime() - start);
            return installed;
        } catch (Exception e) {
            this.diagnostics.persistedLoadNanos.addAndGet(System.nanoTime() - start);
            this.diagnostics.persistedLoadFailures.incrementAndGet();
            quarantinePersistedLoad(key, e);
            this.diagnostics.noteIoError(e.toString());
            CreateManaIndustry.LOGGER.error(
                "[Allvr] failed to load persisted cube {} — stays unloaded; regeneration suppressed", pos, e);
            return null;
        }
    }

    /** Queues a persisted cube read without blocking the server thread. */
    private void requestAsyncPersistedLoad(int cubeX, int cubeY, int cubeZ) {
        if (this.closed || this.pendingPersistedLoads.size() >= MAX_PENDING_PERSISTED_LOADS) {
            return;
        }
        long key = AllvrCubePos.asLong(cubeX, cubeY, cubeZ);
        if (this.cubes.containsKey(key) || this.pendingPersistedLoads.containsKey(key)
            || this.failedPersistedLoads.contains(key)) {
            return;
        }
        long now = this.level.getGameTime();
        if (this.loadCooldown.containsKey(key) && now - this.loadCooldown.get(key) < LOAD_RETRY_TICKS) {
            return;
        }
        this.loadCooldown.put(key, now);
        AllvrCubePos pos = AllvrCubePos.of(cubeX, cubeY, cubeZ);
        long start = System.nanoTime();
        this.diagnostics.persistedLoadRequests.incrementAndGet();
        CompletableFuture<AllvrCube> future;
        try {
            future = this.worker.load(pos)
                .thenApplyAsync(nbt -> decodePersistedCube(pos, nbt), this.persistenceDecodeExecutor);
        } catch (Throwable failure) {
            this.diagnostics.persistedLoadFailures.incrementAndGet();
            quarantinePersistedLoad(key, failure);
            this.diagnostics.persistedLoadNanos.addAndGet(System.nanoTime() - start);
            this.diagnostics.noteIoError(failure.toString());
            return;
        }
        CompletableFuture<AllvrCube> previous = this.pendingPersistedLoads.putIfAbsent(key, future);
        if (previous != null) {
            return;
        }
        future.whenComplete((cube, failure) -> {
            this.diagnostics.persistedLoadNanos.addAndGet(System.nanoTime() - start);
            if (!this.closed) {
                this.completedPersistedLoads.add(new PersistedCubeLoad(key, future, cube, failure));
            }
        });
    }

    /** Installs decoded data on the server thread and binds its live state. */
    private AllvrCube installPersistedCube(long key, AllvrCube cube) {
        if (cube == null || this.closed) {
            return null;
        }
        // A direct gameplay access may consume a pending request before its
        // completion reaches the main-thread queue. The later completion is
        // then stale, just like a replaced vanilla ChunkHolder future.
        this.pendingPersistedLoads.remove(key);
        AllvrCube existing = this.cubes.get(key);
        if (existing != null) {
            return existing;
        }
        cube.markPersistedOverride();
        cube.markQueued(cube.mutationVersion(), cube.lightVersion());
        this.cubes.put(key, cube);
        refreshBeCube(cube);
        cube.onLoad(this.level);
        this.lightEngine.onCubeLoaded(cube);
        announceCubeLoaded(cube);
        this.loadCooldown.remove(key);
        this.diagnostics.cubesLoadedFromDisk.incrementAndGet();
        return cube;
    }

    /**
     * Deserializes a disk record off the region-file worker.  A missing record
     * is treated as a structural persistence failure so the caller can keep
     * the persisted index authoritative and avoid silently regenerating over
     * it.
     */
    private AllvrCube decodePersistedCube(AllvrCubePos pos, java.util.Optional<CompoundTag> nbt) {
        long start = System.nanoTime();
        try {
            if (nbt.isEmpty()) {
                throw new java.util.concurrent.CompletionException(new java.io.IOException(
                    "persisted index contains " + pos + " but no record was found"));
            }
            AllvrCube restored = AllvrCubeSerializer.load(pos, nbt.get(), this.level);
            return restored;
        } catch (AllvrCubeCorruptedException e) {
            throw new java.util.concurrent.CompletionException(e);
        } finally {
            this.diagnostics.persistedDecodeNanos.addAndGet(System.nanoTime() - start);
        }
    }

    /**
     * Per-tick driver: drains the snapshot maintenance queue (budgeted), then
     * on join/teleport synchronously generates and streams the player's
     * current cube, then queues the surrounding neighborhood for
     * the background terrain worker while streaming completed cube data to each player's client
     * within the per-tick send budget. Cubes leaving the vanilla horizontal
     * circle or the independent vertical range are forgotten client-side.
     */
    public void tick() {
        this.lightEngine.tick(16_384);
        // Light changes are persisted through the same coalescing queue as
        // block mutations. The engine only reports cubes whose published
        // section data changed, so unchanged generated cubes do not churn IO.
        for (long key : this.lightEngine.drainDirtyCubes()) {
            if (this.cubes.containsKey(key)) {
                this.queueSnapshot(key, false);
            }
        }
        // Drain before the players.isEmpty() early-return: an empty server
        // still owes its queued snapshots (vanilla autosave parity).
        this.drainSnapshotQueue();
        this.drainCompletedGenerations();
        this.drainCompletedPersistedLoads();
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            if (!subscriptions.isEmpty() || !pendingGenerations.isEmpty()
                || !pendingPersistedLoads.isEmpty()) {
                subscriptions.clear();
                cancelGenerationsOutside(players);
            }
            tickingBeCubeKeys.clear();
            simulationTicketsDirty = true;
            return;
        }

        // A pending generation/read only needs revalidation when the player
        // ticket set changes. Vanilla's DistanceManager updates ticket
        // holders on movement/distance changes; scanning every pending future
        // against every player on every stable tick was the opposite of that
        // behavior.
        Set<UUID> activePlayers = new HashSet<>(players.size());
        for (ServerPlayer player : players) activePlayers.add(player.getUUID());
        boolean subscriptionsChanged = subscriptions.size() != activePlayers.size();
        if (subscriptions.keySet().removeIf(uuid -> !activePlayers.contains(uuid))) {
            subscriptionsChanged = true;
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
            boolean cubeCenterChanged = sub.lastCube == null || !pc.equals(sub.lastCube);
            if (prepareSubscription(player, pc, sub)) subscriptionsChanged = true;
            if (cubeCenterChanged) {
                // ChunkMap does not block the server tick for a missing
                // holder. Queue even the center cube and let the completion
                // handoff below publish it on a later tick; direct gameplay
                // writes retain the separate synchronous compatibility path.
                AllvrCube cube = getOrRequest(pc.getX(), pc.getY(), pc.getZ());
                if (cube != null) {
                    long key = cube.getPos().asLong();
                    if (sub.sent.add(key)) {
                        player.connection.send(packetFor(cube));
                    }
                }
                sub.lastCube = pc;
                sub.scanRadius = 0;
                sub.scanIndex = 0;
            }
        }
        if (subscriptionsChanged) {
            simulationTicketsDirty = true;
            cancelGenerationsOutside(players);
        }

        for (ServerPlayer player : players) {
            AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
            Subscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new Subscription());
            prepareSubscription(player, pc, sub);
            if (capReached && chebyshev(pc, playerCubeCenter(sub)) > 2) {
                continue;
            }
            if (!sub.scanDirty && sub.scanRadius == 0 && sub.scanIndex == 0) {
                if (sub.forgetDirty) {
                    forgetOutOfRange(player, pc, sub);
                    sub.forgetDirty = false;
                }
                continue;
            }
            int sentCount = 0;
            int horizontalScanRadius = AllvrVanillaRenderDistance
                .cubeScanRadiusForChunks(sub.renderDistanceChunks);
            int horizontalShellRadius = Math.max(GEN_RADIUS, horizontalScanRadius);
            int verticalShellRadius = Math.max(GEN_RADIUS, sub.sendYRadius);
            int streamRadius = Math.max(horizontalShellRadius, verticalShellRadius);
            int scanRadius = sub.scanRadius;
            int scanIndex = sub.scanIndex;
            if (scanRadius > streamRadius) {
                scanRadius = 0;
                scanIndex = 0;
            }
            boolean budgetStopped = false;
            boolean sendPending = false;
            int playerChunkX = AllvrVanillaRenderDistance.blockToChunk(player.getX());
            int playerChunkZ = AllvrVanillaRenderDistance.blockToChunk(player.getZ());
            scanLoop:
            while (scanRadius <= streamRadius) {
                // The old cursor walked a (2r+1)^3 cube at every radius and
                // filtered the independent Y range afterwards. This keeps the
                // same near-to-far shell order while enumerating only the
                // anisotropic box that can actually be needed.
                int xRadius = Math.min(scanRadius, horizontalShellRadius);
                int yRadius = Math.min(scanRadius, verticalShellRadius);
                int sideX = xRadius * 2 + 1;
                int sideY = yRadius * 2 + 1;
                int sideZ = sideX;
                int sideXY = sideX * sideY;
                int total = sideXY * sideZ;
                while (scanIndex < total) {
                    int index = scanIndex;
                    int dx = index % sideX - xRadius;
                    int dy = index / sideX % sideY - yRadius;
                    int dz = index / sideXY - xRadius;
                    if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != scanRadius) {
                        scanIndex++;
                        continue;
                    }
                    if (System.nanoTime() > deadline) {
                        budgetStopped = true;
                        break scanLoop;
                    }
                    scanIndex++;
                    int cx = pc.getX() + dx;
                    int cy = pc.getY() + dy;
                    int cz = pc.getZ() + dz;
                    if (AllvrDimensionLimits.isVanillaCube(cy)) continue;
                    long key = AllvrCubePos.asLong(cx, cy, cz);
                    boolean inSendRange = AllvrVanillaRenderDistance.isCubeWithinCylinder(
                        cx, cy, cz, playerChunkX, playerChunkZ,
                        pc.getY(), sub.renderDistanceChunks, sub.sendYRadius);
                    if (sub.sent.contains(key) || (capReached && scanRadius > 2 && !inSendRange)) {
                        continue;
                    }
                    AllvrCube cube = getOrRequest(cx, cy, cz);
                    if (cube == null) {
                        continue;
                    }
                    if (inSendRange && !sub.sent.contains(key)) {
                        if (sentCount < SEND_BUDGET_PER_TICK && sub.sent.add(key)) {
                            player.connection.send(packetFor(cube));
                            sentCount++;
                        } else {
                            // Keep the subscription dirty after a complete
                            // pass. Vanilla's tracking difference is retried
                            // until every eligible holder has been sent.
                            sendPending = true;
                        }
                    }
                }
                scanRadius++;
                scanIndex = 0;
            }
            if (budgetStopped) {
                sub.scanRadius = scanRadius;
                sub.scanIndex = scanIndex;
                sub.scanDirty = true;
            } else if (sendPending) {
                sub.scanRadius = 0;
                sub.scanIndex = 0;
                sub.scanDirty = true;
            } else {
                sub.scanRadius = 0;
                sub.scanIndex = 0;
                sub.scanDirty = false;
            }

            if (sub.forgetDirty) {
                forgetOutOfRange(player, pc, sub);
                sub.forgetDirty = false;
            }
        }

        rebuildSimulationTickets(players);
        this.tickBlockEntities();
        if (subscriptionsChanged || ++this.unloadScanTicks >= UNLOAD_SCAN_TICKS) {
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
    private void rebuildSimulationTickets(List<ServerPlayer> players) {
        if (!simulationTicketsDirty) {
            return;
        }
        tickingBeCubeKeys.clear();
        for (long key : beCubes.keySet()) {
            AllvrCube cube = beCubes.get(key);
            if (cube == null) continue;
            AllvrCubePos cpos = cube.getPos();
            for (ServerPlayer player : players) {
                if (chebyshev(AllvrCubePos.of(player.blockPosition()), cpos) <= GEN_RADIUS) {
                    tickingBeCubeKeys.add(key);
                    break;
                }
            }
        }
        simulationTicketsDirty = false;
    }

    /** Ticks only cubes with a current simulation ticket. */
    private void tickBlockEntities() {
        if (tickingBeCubeKeys.isEmpty()) {
            return;
        }
        // snapshot: a ticker can setBlock (adding/removing BEs → registry writes)
        for (long key : tickingBeCubeKeys.toLongArray()) {
            AllvrCube cube = beCubes.get(key);
            if (cube != null && cube.hasBlockEntities()) {
                cube.tickBlockEntities(level);
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
        long start = System.nanoTime();
        try {
            AllvrCubeSnapshot snapshot = AllvrCubeSerializer.snapshot(cube, this.level, this.lightEngine);
            this.worker.enqueue(snapshot);
            // A queued snapshot is already the newest authoritative state:
            // subsequent loads must prefer it over deterministic generation,
            // even before the asynchronous region commit finishes.
            this.persistedIndex.add(cube.getPos().asLong());
            cube.markPersistedOverride();
            cube.markQueued(snapshot.version(), snapshot.lightVersion());
            this.lastSnapshotTick.put(cube.getPos().asLong(), this.level.getGameTime());
            this.diagnostics.snapshotsBuilt.incrementAndGet();
            this.diagnostics.snapshotNanos.addAndGet(System.nanoTime() - start);
            return true;
        } catch (Exception e) {
            this.diagnostics.snapshotNanos.addAndGet(System.nanoTime() - start);
            this.diagnostics.snapshotsFailed.incrementAndGet();
            this.diagnostics.noteIoError(e.toString());
            CreateManaIndustry.LOGGER.error("[Allvr] snapshot failed for cube {} — cube kept in memory",
                cube.getPos(), e);
            return false;
        }
    }

    /** Applies completed disk reads on the server thread, like ChunkMap. */
    private void drainCompletedPersistedLoads() {
        long start = System.nanoTime();
        int installed = 0;
        PersistedCubeLoad completed;
        while (installed < COMPLETION_INSTALL_BUDGET
            && System.nanoTime() - start < COMPLETION_INSTALL_BUDGET_NANOS
            && (completed = completedPersistedLoads.poll()) != null) {
            if (this.pendingPersistedLoads.get(completed.key()) != completed.future()
                || !isNeededByAnyPlayer(AllvrCubePos.fromLong(completed.key()), level.players())) {
                this.pendingPersistedLoads.remove(completed.key(), completed.future());
                continue;
            }
            this.pendingPersistedLoads.remove(completed.key(), completed.future());
            if (closed) {
                continue;
            }
            if (completed.failure() != null) {
                if (completed.failure() instanceof java.util.concurrent.CancellationException) {
                    continue;
                }
                diagnostics.persistedLoadFailures.incrementAndGet();
                boolean quarantined = quarantinePersistedLoad(completed.key(), completed.failure());
                diagnostics.noteIoError(completed.failure().toString());
                CreateManaIndustry.LOGGER.error("[Allvr] async load failed for persisted cube {} — {}",
                    AllvrCubePos.fromLong(completed.key()),
                    quarantined ? "record quarantined; regeneration suppressed"
                        : "transient failure; retry remains enabled",
                    completed.failure());
                continue;
            }
            try {
                this.installPersistedCube(completed.key(), completed.cube());
                installed++;
            } catch (Throwable failure) {
                diagnostics.persistedLoadFailures.incrementAndGet();
                quarantinePersistedLoad(completed.key(), failure);
                diagnostics.noteIoError(failure.toString());
                CreateManaIndustry.LOGGER.error("[Allvr] async cube install failed for {} — "
                    + "regeneration suppressed", AllvrCubePos.fromLong(completed.key()), failure);
            }
        }
    }

    /**
     * Classifies failures that cannot be repaired by retrying the same record.
     * A failed record remains in {@link #persistedIndex} so deterministic
     * generation can never overwrite it, but it is removed from the hot load
     * path for the rest of this server session.
     */
    private boolean quarantinePersistedLoad(long key, Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof java.util.concurrent.CompletionException
            || cause instanceof java.util.concurrent.ExecutionException) {
            if (cause.getCause() == null) {
                break;
            }
            cause = cause.getCause();
        }
        boolean permanent = cause instanceof AllvrCubeCorruptedException
            || cause instanceof net.minecraft.nbt.NbtAccounterException
            || (cause instanceof java.io.IOException io
                && io.getMessage() != null
                && io.getMessage().startsWith("persisted index contains"));
        if (permanent) {
            boolean added = this.failedPersistedLoads.add(key);
            if (added) {
                this.diagnostics.persistedLoadQuarantined.incrementAndGet();
            }
            this.loadCooldown.remove(key);
            return true;
        }
        return false;
    }

    /**
     * Makes a freshly generated cube storage-backed.  Worldgen is
     * deterministic, but persisting its result avoids paying the complete
     * noise/feature cost again after a restart and gives generated cubes the
     * same unload/reload path as edited cubes.
     */
    private void markGeneratedForPersistence(AllvrCube cube) {
        cube.markDirty();
        this.queueSnapshot(cube.getPos().asLong(), true);
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
        persistenceDecodeExecutor.shutdownNow();
        try {
            persistenceDecodeExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        pendingGenerations.clear();
        completedGenerations.clear();
        for (CompletableFuture<AllvrCube> load : pendingPersistedLoads.values()) {
            load.cancel(false);
        }
        pendingPersistedLoads.clear();
        completedPersistedLoads.clear();
        this.cubePacketCache.clear();
        this.lightEngine.clear();
        this.worker.close();
        CreateManaIndustry.LOGGER.info("[Allvr] cube persistence closed ({}): {}",
            ok ? "clean" : "with errors", this.diagnostics);
        return ok;
    }

    // ------------------------------------------------------------------
    // unloading
    // ------------------------------------------------------------------

    /**
     * Cubes beyond every player's simulation shell and render cylinder leave
     * memory — save-before-unload (plan §7.3): a dirty cube is snapshotted and
     * enqueued first; on failure (or while {@code level.noSave} is set) it
     * stays pinned. Once a generated cube has entered the persistence queue,
     * clean and edited cubes share the same disk/pending reload path.
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
                if (isNeededByPlayer(cpos, player)) {
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
                forgetCubeForPlayers(key, players);
                cube.onUnload();
                this.cubes.remove(key);
                this.beCubes.remove(key);
                this.tickingBeCubeKeys.remove(key);
                // Cooldown bookkeeping is only meaningful while a cube is
                // resident.  Discard it with the cube so exploration cannot
                // grow a map entry for every unloaded coordinate.
                this.lastSnapshotTick.remove(key);
                this.loadCooldown.remove(key);
                this.lightEngine.onCubeUnloaded(cube);
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
        int playerChunkX = AllvrVanillaRenderDistance.blockToChunk(player.getX());
        int playerChunkZ = AllvrVanillaRenderDistance.blockToChunk(player.getZ());
        while (it.hasNext()) {
            long key = it.nextLong();
            if (!AllvrVanillaRenderDistance.isCubeWithinCylinder(
                AllvrCubePos.extractX(key), AllvrCubePos.extractY(key), AllvrCubePos.extractZ(key),
                playerChunkX, playerChunkZ, pc.getY(),
                sub.renderDistanceChunks, sub.sendYRadius)) {
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
        if (subscriptions.remove(uuid) != null) {
            simulationTicketsDirty = true;
        }
    }

    private static int chebyshev(AllvrCubePos a, AllvrCubePos b) {
        return Math.max(Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY())),
            Math.abs(a.getZ() - b.getZ()));
    }

    private boolean isNeededByAnyPlayer(AllvrCubePos cube, List<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            if (isNeededByPlayer(cube, player)) {
                return true;
            }
        }
        return false;
    }

    /** Keeps both the fixed simulation shell and the dynamic render cylinder alive. */
    private boolean isNeededByPlayer(AllvrCubePos cube, ServerPlayer player) {
        AllvrCubePos center = AllvrCubePos.of(player.blockPosition());
        if (chebyshev(cube, center) <= GEN_RADIUS) {
            return true;
        }
        Subscription sub = subscriptions.computeIfAbsent(player.getUUID(), k -> new Subscription());
        updateRenderDistance(player, sub);
        return AllvrVanillaRenderDistance.isCubeWithinCylinder(
            cube.getX(), cube.getY(), cube.getZ(),
            AllvrVanillaRenderDistance.blockToChunk(player.getX()),
            AllvrVanillaRenderDistance.blockToChunk(player.getZ()),
            center.getY(), sub.renderDistanceChunks, sub.sendYRadius);
    }

    /**
     * Uses the same values vanilla uses for its real chunk tracking view:
     * the client's requested distance, capped by the server's global view
     * distance. No Allay-specific client packet is necessary because the
     * vanilla client-information packet already updates requestedViewDistance.
     */
    private boolean prepareSubscription(ServerPlayer player, AllvrCubePos center, Subscription sub) {
        boolean changed = updateRenderDistance(player, sub);
        int playerChunkX = AllvrVanillaRenderDistance.blockToChunk(player.getX());
        int playerChunkZ = AllvrVanillaRenderDistance.blockToChunk(player.getZ());
        boolean cubeCenterChanged = sub.lastCube == null || !center.equals(sub.lastCube);
        boolean vanillaCenterChanged = sub.lastPlayerChunkX != playerChunkX
            || sub.lastPlayerChunkZ != playerChunkZ;
        if (cubeCenterChanged || vanillaCenterChanged) {
            sub.lastCube = center;
            sub.lastPlayerChunkX = playerChunkX;
            sub.lastPlayerChunkZ = playerChunkZ;
            sub.scanRadius = 0;
            sub.scanIndex = 0;
            sub.scanDirty = true;
            sub.forgetDirty = true;
            changed = true;
        }
        return changed;
    }

    private boolean updateRenderDistance(ServerPlayer player, Subscription sub) {
        int serverViewDistance = this.level.getServer().getPlayerList().getViewDistance();
        int effective = Math.min(player.requestedViewDistance(), serverViewDistance);
        int next = AllvrVanillaRenderDistance.clampChunks(effective);
        if (sub.renderDistanceChunks != next) {
            sub.renderDistanceChunks = next;
            sub.scanRadius = 0;
            sub.scanIndex = 0;
            sub.scanDirty = true;
            sub.forgetDirty = true;
            return true;
        }
        return false;
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
