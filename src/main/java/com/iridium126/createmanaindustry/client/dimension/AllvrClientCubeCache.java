package com.iridium126.createmanaindustry.client.dimension;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCube;
import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrBlockUpdatePacket;
import com.iridium126.createmanaindustry.dimension.net.ClientboundAllvrCubePacket;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;
import com.iridium126.createmanaindustry.dimension.light.AllvrLightEngine;

/**
 * Client-side registry of streamed cubes for the allay dimension — the cube
 * analogue of {@code ClientChunkCache}, filled by
 * {@code ClientboundAllvrCubePacket}/{@code ...ForgetCubePacket} and consumed
 * by {@code AllvrClientLevelMixin} (ClientLevel block reads inside the
 * dimension) and the Sodium section source. The cache also powers client-side
 * collision, entity physics, block outlines and ray tracing, so a player
 * teleported onto an island stands on it.
 * <p>
 * Cache misses read as void air — the client never generates. Packet section
 * decoding runs on a bounded worker pool; publication, forget and mutation
 * calls run on the client game thread. Cleared on level unload / world switch
 * (see {@code CreateManaIndustryClient}).
 */
public final class AllvrClientCubeCache {

    /**
     * Guards the cube map and cube contents. Main-thread writers (apply /
     * forget / setBlock) and Sodium's build snapshots both hold it — the cube
     * section objects are not thread-safe against concurrent writes. Held
     * briefly; no GL or world access inside.
     */
    public static final Object LOCK = new Object();

    private static ClientLevel level;
    /**
     * Packet section/BE decoding is deliberately outside the client game
     * thread.  The vanilla chunk path decodes packet data before publishing a
     * LevelChunk; doing the equivalent here prevents a burst of streamed cubes
     * from blocking frame and input processing on the render/game thread.
     */
    private static final int CUBE_DECODE_WORKERS =
        Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
    private static final ThreadPoolExecutor CUBE_DECODE_EXECUTOR = new ThreadPoolExecutor(
        CUBE_DECODE_WORKERS, CUBE_DECODE_WORKERS,
        0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
        runnable -> {
            Thread thread = new Thread(runnable, "allvr-client-cube-decode");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.CallerRunsPolicy());
    private static final AtomicLong NEXT_PACKET_SEQUENCE = new AtomicLong();
    /** Keep packet publication within the normal client tick budget. A burst
     * of streamed air cubes must not make the render thread apply thousands
     * of cube replacements in one frame. */
    private static final int CUBE_APPLY_BUDGET = 64;
    private static final long CUBE_APPLY_BUDGET_NANOS = 2_000_000L;
    /** Keep the engine lock visible to Sodium for at most a small bounded
     * batch; the remaining propagation carries over to the next client tick. */
    private static final int CLIENT_LIGHT_BUDGET = 8_192;
    /** Incremented on level teardown so a late decode cannot publish into a
     * newly created Allay level with the same dimension id. */
    private static final AtomicLong CLIENT_SESSION_EPOCH = new AtomicLong();
    /** Latest packet sequence per cube; prevents an older async decode from
     * replacing a newer packet that finished first. */
    private static final ConcurrentHashMap<Long, Long> LATEST_PACKET_SEQUENCE =
        new ConcurrentHashMap<>();
    /** Packet tombstones are only needed while a decode can still be queued.
     * Bound this map so exploring indefinitely cannot retain every cube key
     * ever visited in the client session. */
    private static final int PACKET_SEQUENCE_CACHE_LIMIT = 32_768;
    private static final ConcurrentLinkedQueue<DecodedCube> COMPLETED_CUBES =
        new ConcurrentLinkedQueue<>();
    /** Monotonic ALLVR content revision used by immutable Sodium snapshots. */
    private static volatile long contentRevision;
    private static final Long2ObjectOpenHashMap<AllvrCube> cubes = new Long2ObjectOpenHashMap<>();
    private static AllvrLightEngine lightEngine;
    /** Cubes that hold block entities — the client ticking worklist (mirrors
     *  the server cube map's registry; most cubes are pure terrain). */
    private static final Long2ObjectOpenHashMap<AllvrCube> beCubes = new Long2ObjectOpenHashMap<>();
    private static final LongOpenHashSet tickingBeCubeKeys = new LongOpenHashSet();
    private static boolean simulationTicketsDirty = true;
    private static long simulationCenter = Long.MIN_VALUE;

    private record DecodedCube(ClientLevel targetLevel, long sessionEpoch, long cubePos,
                               int payloadBytes, AllvrCube cube, long sequence,
                               Throwable failure) {}

    /** Binds the current client level (called on LevelEvent.Load). */
    public static void onLevelChanged(ClientLevel clientLevel) {
        if (lightEngine != null) {
            lightEngine.clear();
        }
        level = clientLevel;
        lightEngine = clientLevel.dimension() == AllvrDimensions.ALLAY_LEVEL
            ? new AllvrLightEngine(clientLevel, new AllvrLightEngine.Access() {
                @Override
                public BlockState getBlockState(BlockPos pos) {
                    return AllvrClientCubeCache.getBlockState(pos);
                }

                @Override
                public boolean isLoaded(BlockPos pos) {
                    synchronized (LOCK) {
                        return cubes.containsKey(AllvrCubePos.asLong(pos));
                    }
                }

                @Override
                public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
                    return AllvrClientCubeCache.getBlockEntity(pos);
                }
            }) : null;
        com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.setLightResolver(
            (type, pos) -> type == net.minecraft.world.level.LightLayer.BLOCK
                ? sampleBlockLight(pos) : sampleSkyLight(pos),
            (pos, amount) -> Math.max(sampleBlockLight(pos), sampleSkyLight(pos) - amount));
        com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.setBiomeResolver(AllvrClientCubeCache::getNoiseBiome);
        com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.setLoadedResolver(
            AllvrClientCubeCache::isLoaded);
    }

    public static AllvrLightEngine lightEngine() {
        return lightEngine;
    }

    public static net.minecraft.world.level.chunk.DataLayer[] lightData(net.minecraft.core.SectionPos section) {
        return lightEngine == null ? null : lightEngine.sectionData(section);
    }

    public static int sampleBlockLight(BlockPos pos) {
        return lightEngine == null ? 0 : lightEngine.blockLight(pos);
    }

    public static int sampleSkyLight(BlockPos pos) {
        return lightEngine == null ? 0 : lightEngine.skyLight(pos);
    }

    public static net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int x, int y, int z) {
        synchronized (LOCK) {
            AllvrCube cube = cubes.get(AllvrCubePos.asLong(x >> 3, y >> 3, z >> 3));
            return cube == null ? null : cube.getNoiseBiome(x, y, z);
        }
    }

    public static ClientLevel currentLevel() {
        return level;
    }

    public static boolean isAllay(ClientLevel candidate) {
        return candidate != null && candidate.dimension() == AllvrDimensions.ALLAY_LEVEL;
    }

    public static long contentRevision() {
        synchronized (LOCK) {
            return contentRevision;
        }
    }

    /** Internal read for a caller already holding {@link #LOCK}. */
    public static long contentRevisionUnsafe() {
        return contentRevision;
    }

    /** Internal read for a caller already holding {@link #LOCK}. */
    public static AllvrCube peekCubeUnsafe(long key) {
        return cubes.get(key);
    }

    /** Drops every streamed cube (level unload / dimension switch / logout). */
    public static void clear() {
        CLIENT_SESSION_EPOCH.incrementAndGet();
        AllvrSodiumBridge.clear();
        synchronized (LOCK) {
            if (lightEngine != null) {
                lightEngine.clear();
            }
            for (AllvrCube cube : cubes.values()) {
                cube.onUnload();
            }
            cubes.clear();
            beCubes.clear();
            tickingBeCubeKeys.clear();
            simulationTicketsDirty = true;
            simulationCenter = Long.MIN_VALUE;
            contentRevision++;
            LATEST_PACKET_SEQUENCE.clear();
            COMPLETED_CUBES.clear();
            level = null;
            lightEngine = null;
            com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.setLightResolver(null, null);
            com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.setLoadedResolver(null);
        }
    }

    /**
     * Queues one streamed cube for off-thread packet deserialization.  Only
     * the publication and Sodium invalidation are performed on the client
     * game thread, mirroring vanilla's packet-to-ChunkMap handoff.
     */
    public static void queueCube(ClientboundAllvrCubePacket packet) {
        if (com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaCube(
            AllvrCubePos.fromLong(packet.cubePos()).getY())) return;
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        RegistryAccess registryAccess = clientLevel.registryAccess();
        long sequence = NEXT_PACKET_SEQUENCE.incrementAndGet();
        long sessionEpoch = CLIENT_SESSION_EPOCH.get();
        rememberPacketSequence(packet.cubePos(), sequence);
        CUBE_DECODE_EXECUTOR.execute(() -> decodeAndQueueApply(
            packet, clientLevel, registryAccess, sequence, sessionEpoch));
    }

    private static void decodeAndQueueApply(ClientboundAllvrCubePacket packet,
                                              ClientLevel targetLevel,
                                              RegistryAccess registryAccess,
                                              long sequence,
                                              long sessionEpoch) {
        // A newer packet may have superseded this one while it was waiting
        // for a decode worker. Vanilla's packet pipeline drops stale holder
        // results before publication; do the same before paying the section
        // palette/BE decode cost.
        if (CLIENT_SESSION_EPOCH.get() != sessionEpoch
            || !isLatestPacket(packet.cubePos(), sequence)) {
            return;
        }
        AllvrCube cube = null;
        Throwable failure = null;
        try {
            cube = packet.decodeCube(registryAccess);
        } catch (Throwable error) {
            failure = error;
        }
        // A level switch or a queued forget may finish while this worker was
        // decoding.  Drop the result before it reaches the publication queue;
        // the sequence check below remains the final stale-packet guard.
        if (CLIENT_SESSION_EPOCH.get() != sessionEpoch) {
            return;
        }
        COMPLETED_CUBES.add(new DecodedCube(targetLevel, sessionEpoch, packet.cubePos(),
            packet.payload().length, cube, sequence, failure));
    }

    /** Applies a bounded batch of completed packet decodes on the client game
     * thread. Packet workers only append to {@link #COMPLETED_CUBES}; the
     * remaining queue carries over to the next tick like vanilla chunk
     * publication under a packet burst. */
    public static void tick() {
        long deadline = System.nanoTime() + CUBE_APPLY_BUDGET_NANOS;
        DecodedCube decoded;
        int applied = 0;
        while (applied < CUBE_APPLY_BUDGET
            && (applied == 0 || System.nanoTime() < deadline)
            && (decoded = COMPLETED_CUBES.poll()) != null) {
            applyDecodedCube(decoded.targetLevel(), decoded.sessionEpoch(), decoded.cubePos(),
                decoded.payloadBytes(), decoded.cube(), decoded.sequence(), decoded.failure());
            applied++;
        }
        if (lightEngine != null) {
            lightEngine.tick(CLIENT_LIGHT_BUDGET);
            for (long cubeKey : lightEngine.drainDirtyCubes()) {
                if (lightEngine.hasLoadedCube(cubeKey)) {
                    notifyCubePublished(cubeKey);
                }
            }
        }
    }

    /**
     * Main-thread apply of one already decoded streamed cube. A structurally
     * malformed payload (element cap breach, trailing bytes) drops the whole
     * packet — a half-applied cube would mix old sections with a failed tail.
     */
    private static void applyDecodedCube(ClientLevel targetLevel, long sessionEpoch,
                                         long cubePos, int payloadBytes, AllvrCube cube,
                                         long sequence, Throwable failure) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null || clientLevel != targetLevel
            || CLIENT_SESSION_EPOCH.get() != sessionEpoch
            || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        Long latest = LATEST_PACKET_SEQUENCE.get(cubePos);
        if (latest != null && latest.longValue() != sequence) {
            return;
        }
        if (failure != null) {
            CreateManaIndustry.LOGGER.warn("[Allvr] malformed cube packet for {} — dropped",
                com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.fromLong(cubePos), failure);
            return;
        }
        level = clientLevel;
        synchronized (LOCK) {
            AllvrCube old = cubes.put(cubePos, cube);
            if (old != null) {
                old.onUnload();
            }
            refreshBeCube(cubePos, cube);
            contentRevision++;
        }
        cube.onLoad(clientLevel);
        if (lightEngine != null) {
            lightEngine.onCubeLoaded(cube);
        } else {
            notifyCubePublished(cubePos);
        }
    }

    private static void notifyCubePublished(long cubePos) {
        AllvrSodiumBridge.onCubeApplied(cubePos);
        com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyClientIngest
            .onCubeApplied(cubePos);
    }

    /**
     * Synchronous compatibility entry point for code that explicitly wants
     * to apply a packet on the client thread. Network handlers must use
     * {@link #queueCube} so section decoding never runs on that thread.
     */
    public static void applyCube(ClientboundAllvrCubePacket packet) {
        if (com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isVanillaCube(
            AllvrCubePos.fromLong(packet.cubePos()).getY())) return;
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        try {
            long sequence = NEXT_PACKET_SEQUENCE.incrementAndGet();
            rememberPacketSequence(packet.cubePos(), sequence);
            applyDecodedCube(clientLevel, CLIENT_SESSION_EPOCH.get(), packet.cubePos(), packet.payload().length,
                packet.decodeCube(clientLevel.registryAccess()), sequence, null);
        } catch (Throwable error) {
            applyDecodedCube(clientLevel, CLIENT_SESSION_EPOCH.get(), packet.cubePos(), packet.payload().length, null,
                LATEST_PACKET_SEQUENCE.getOrDefault(packet.cubePos(), Long.MIN_VALUE), error);
        }
    }

    public static void forgetCube(long cubePos) {
        // Keep a tombstone sequence so a decode that was already queued cannot
        // resurrect a cube after the vanilla-style forget packet is applied.
        rememberPacketSequence(cubePos, NEXT_PACKET_SEQUENCE.incrementAndGet());
        AllvrCube unloaded;
        synchronized (LOCK) {
            unloaded = cubes.remove(cubePos);
            if (unloaded != null) {
                unloaded.onUnload();
            }
            beCubes.remove(cubePos);
            tickingBeCubeKeys.remove(cubePos);
            contentRevision++;
        }
        // The light engine calls back into the cache while re-seeding its
        // loaded neighbours.  Keep this outside LOCK; taking LOCK here would
        // deadlock with Access#isLoaded during boundary propagation.
        if (unloaded != null && lightEngine != null) {
            lightEngine.onCubeUnloaded(unloaded);
        }
        AllvrSodiumBridge.onCubeForgotten(cubePos);
        // Voxy deliberately retains its LOD when a near cube is forgotten;
        // the next cube publication will overwrite the affected sections.
    }

    private static void rememberPacketSequence(long cubePos, long sequence) {
        LATEST_PACKET_SEQUENCE.put(cubePos, sequence);
        int excess = LATEST_PACKET_SEQUENCE.size() - PACKET_SEQUENCE_CACHE_LIMIT;
        if (excess <= 0) {
            return;
        }
        // This is an opportunistic bound, not part of packet ordering. The
        // bounded decode queue and session epoch still reject late results;
        // the map no longer retains every cube visited during a long session.
        for (var entry : LATEST_PACKET_SEQUENCE.entrySet()) {
            if (excess <= 0) {
                break;
            }
            if (LATEST_PACKET_SEQUENCE.remove(entry.getKey(), entry.getValue())) {
                excess--;
            }
        }
    }

    private static boolean isLatestPacket(long cubePos, long sequence) {
        Long latest = LATEST_PACKET_SEQUENCE.get(cubePos);
        // An opportunistically evicted entry is not evidence that this task
        // is stale; applyDecodedCube remains the authoritative final guard.
        return latest == null || latest.longValue() == sequence;
    }

    /** Keeps the block-entity worklist in step with a cube's BE set. */
    private static void refreshBeCube(long key, AllvrCube cube) {
        if (cube.hasBlockEntities()) {
            if (beCubes.put(key, cube) != cube) {
                simulationTicketsDirty = true;
            }
        } else {
            if (beCubes.remove(key) != null) {
                tickingBeCubeKeys.remove(key);
                simulationTicketsDirty = true;
            }
        }
    }

    /**
     * Client block-entity ticking — the client half of the cube BE tick loop
     * (vanilla also ticks BEs client-side; Create's rotation/mixer animations
     * are driven from here). Called from {@code ClientTickEvent.Post}; gated to
     * the streamed radii around the local player (a cube further out exists
     * only inside the forget hysteresis and must not tick — the client-side
     * stand-in for vanilla's simulation-distance gating).
     */
    public static void tickBlockEntities() {
        ClientLevel clientLevel = level;
        if (clientLevel == null || beCubes.isEmpty()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        AllvrCubePos pc = AllvrCubePos.of(player.blockPosition());
        long center = pc.asLong();
        if (center != simulationCenter) {
            simulationCenter = center;
            simulationTicketsDirty = true;
        }
        if (simulationTicketsDirty) {
            tickingBeCubeKeys.clear();
            for (long key : beCubes.keySet()) {
                AllvrCube cube = beCubes.get(key);
                if (cube == null) continue;
                AllvrCubePos cpos = cube.getPos();
                if (Math.abs(cpos.getX() - pc.getX()) <= 8 && Math.abs(cpos.getZ() - pc.getZ()) <= 8
                    && Math.abs(cpos.getY() - pc.getY()) <= 8) {
                    tickingBeCubeKeys.add(key);
                }
            }
            simulationTicketsDirty = false;
        }
        // snapshot: a ticker can write blocks (adding/removing BEs → registry writes)
        for (long key : tickingBeCubeKeys.toLongArray()) {
            AllvrCube cube = beCubes.get(key);
            if (cube != null && cube.hasBlockEntities()) {
                cube.tickBlockEntities(clientLevel);
            }
        }
    }

    /**
     * Applies one authoritative server-side block change. Routes through the
     * VANILLA confirmation path ({@code ClientPacketListener#handleBlockUpdate}
     * → {@code ClientLevel#setServerVerifiedBlockState}) rather than writing
     * the cache directly: with a pending client prediction for that position,
     * the handler absorbs the write — writing the cache directly leaves the
     * prediction handler's entry holding the pre-break state, so the later
     * prediction ACK's {@code syncBlockState} restores the broken block
     * (remeshing it back in) and rubber-bands the player standing in the hole
     * via {@code absMoveTo}. With no pending prediction it falls through to
     * {@code Level#setBlock} (flags 19, recursion 512) and the mixin writes the
     * cache normally — the vanilla semantics for a far-away authoritative
     * change (another player, /setblock).
     */
    public static void applyBlockUpdate(ClientboundAllvrBlockUpdatePacket packet) {
        ClientLevel clientLevel = level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return; // level switched — the streamed cube died with it
        }
        com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos pos =
            com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.fromLong(packet.cubePos());
        int cell = packet.cellIndex();
        // the 15-bit cube cell layout — anything else cannot address this cube
        if (cell > 32767) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState state =
            net.minecraft.world.level.block.Block.stateById(packet.stateId());
        if (state == null) {
            return; // unknown state id — never guess a substitute
        }
        BlockPos blockPos = new BlockPos(pos.minBlockX() + (cell & 31),
            pos.minBlockY() + (cell >> 10), pos.minBlockZ() + ((cell >> 5) & 31));
        clientLevel.setServerVerifiedBlockState(blockPos, state, 19);
        BlockEntity blockEntity = getBlockEntity(blockPos);
        if (blockEntity != null && packet.blockEntityTag() != null) {
            blockEntity.loadWithComponents(packet.blockEntityTag(), clientLevel.registryAccess());
            blockEntity.setChanged();
        }
    }

    /** The cached cube at a position's cube, or null (never generates). */
    public static AllvrCube peekCube(BlockPos pos) {
        synchronized (LOCK) {
            return cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
        }
    }

    /** Long-key variant for code that already works in cube keys (mesher
     *  light bake); same lock discipline as {@link #peekCube(BlockPos)}. */
    public static AllvrCube peekCube(long cubePos) {
        synchronized (LOCK) {
            return cubes.get(cubePos);
        }
    }

    /** Snapshot of every cached cube key (tier-latch rebuild path; main thread). */
    public static long[] cubeKeys() {
        synchronized (LOCK) {
            return cubes.keySet().toLongArray();
        }
    }

    /**
     * Client-side mirror of {@code AllvrCubeMap#setBlock} for the write paths
     * vanilla routes through {@code Level#setBlock} on the client (destroy /
     * place prediction, server confirmation packets). Keeps prediction writes
     * off the empty-shell column chunks, whose section arrays cannot address
     * cube-only Y positions ({@code LevelChunk#setBlockState} has no section
     * bounds check — an unchecked write crashes with AIOOBE).
     * <p>
     * Unloaded cubes reject the write ({@code false}), mirroring vanilla's
     * "write to unloaded chunk fails": a block the player can target is always
     * inside a streamed cube. The shared vanilla light engine observes the
     * resulting block-state change directly.
     */
    public static boolean setBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft) {
        ClientLevel clientLevel = level;
        if (clientLevel == null || !com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isInBounds(pos)) {
            return false;
        }
        AllvrCube cube;
        BlockState oldState;
        synchronized (LOCK) {
            cube = cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
            if (cube == null) {
                return false;
            }
            pos = pos.immutable();
            oldState = cube.getBlockState(pos);
            if (oldState == newState || oldState.equals(newState)) {
                return false;
            }
            cube.setBlockState(pos, newState, false);
            oldState.onRemove(clientLevel, pos, newState, false);
            updateBlockEntity(clientLevel, cube, pos, newState);
            newState.onPlace(clientLevel, pos, oldState, false);
            contentRevision++;

        }
        // The cache is updated before the Sodium notification so its build
        // snapshot always observes the new state.
        AllvrSodiumBridge.onBlockChanged(pos, oldState, newState);
        if (lightEngine != null) {
            lightEngine.onBlockChanged(pos);
        }
        com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyClientIngest.onBlockChanged(pos);

        // Mirror of Level#markAndNotifyBlock, minus vanilla section
        // notifications: Sodium receives the cube-backed update above.
        if ((flags & 1) != 0) {
            clientLevel.blockUpdated(pos, oldState.getBlock());
            if (newState.hasAnalogOutputSignal()) {
                clientLevel.updateNeighbourForOutputSignal(pos, newState.getBlock());
            }
        }
        if ((flags & 16) == 0 && recursionLeft > 0) {
            int i = flags & -34;
            oldState.updateIndirectNeighbourShapes(clientLevel, pos, i, recursionLeft - 1);
            newState.updateNeighbourShapes(clientLevel, pos, i, recursionLeft - 1);
            newState.updateIndirectNeighbourShapes(clientLevel, pos, i, recursionLeft - 1);
        }
        return true;
    }

    private static void updateBlockEntity(ClientLevel clientLevel, AllvrCube cube, BlockPos pos, BlockState newState) {
        cube.updateBlockEntity(clientLevel, pos, newState);
        refreshBeCube(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos), cube);
    }

    public static BlockState getBlockState(BlockPos pos) {
        AllvrCube cube = cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
        return cube == null ? Blocks.VOID_AIR.defaultBlockState() : cube.getBlockState(pos);
    }

    public static FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    public static BlockEntity getBlockEntity(BlockPos pos) {
        AllvrCube cube = cubes.get(com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos.asLong(pos));
        return cube == null ? null : cube.getBlockEntity(pos);
    }

    /** Client-side residency query for common LevelReader routing. */
    public static boolean isLoaded(BlockPos pos) {
        if (!com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isInBounds(pos)) {
            return false;
        }
        synchronized (LOCK) {
            AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
            return level != null && level.dimension() == AllvrDimensions.ALLAY_LEVEL
                && cube != null && cube.isLoaded();
        }
    }

    /** Client-side equivalent of Level#setBlockEntity for cube positions. */
    public static void setBlockEntity(BlockEntity blockEntity) {
        ClientLevel clientLevel = level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos().immutable();
        if (!com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isInBounds(pos)) {
            return;
        }
        boolean installed = false;
        synchronized (LOCK) {
            AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
            if (cube == null || !cube.isLoaded()) {
                return;
            }
            BlockState state = cube.getBlockState(pos);
            if (!state.hasBlockEntity()) {
                return;
            }
            BlockState entityState = blockEntity.getBlockState();
            if (state != entityState) {
                if (!blockEntity.getType().isValid(state)) {
                    return;
                }
                blockEntity.setBlockState(state);
            }
            blockEntity.setLevel(clientLevel);
            blockEntity.clearRemoved();
            BlockEntity previous = cube.getBlockEntity(pos);
            if (previous != null && previous != blockEntity) {
                previous.setRemoved();
            }
            cube.putBlockEntity(pos, blockEntity);
            cube.rebuildDerivedState(clientLevel);
            cube.markDirty();
            refreshBeCube(cube.getPos().asLong(), cube);
            contentRevision++;
            installed = true;
        }
        if (installed) {
            clientLevel.addFreshBlockEntities(java.util.List.of(blockEntity));
        }
    }

    /** Client-side equivalent of Level#removeBlockEntity for cube positions. */
    public static BlockEntity removeBlockEntity(BlockPos pos) {
        if (!com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isInBounds(pos)) {
            return null;
        }
        synchronized (LOCK) {
            AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
            if (cube == null) {
                return null;
            }
            BlockEntity removed = cube.removeBlockEntity(pos);
            if (removed != null) {
                cube.markDirty();
                refreshBeCube(cube.getPos().asLong(), cube);
                contentRevision++;
            }
            return removed;
        }
    }

    /** Client-side equivalent of Level#loadedAndEntityCanStandOnFace. */
    public static boolean loadedAndEntityCanStandOnFace(BlockPos pos, Entity entity, Direction direction) {
        if (!com.iridium126.createmanaindustry.dimension.AllvrDimensionLimits.isInBounds(pos)) {
            return false;
        }
        synchronized (LOCK) {
            AllvrCube cube = cubes.get(AllvrCubePos.asLong(pos));
            return cube != null && cube.isLoaded()
                && cube.getBlockState(pos).entityCanStandOnFace(level, pos, entity, direction);
        }
    }

    public static int size() {
        return cubes.size();
    }

    private AllvrClientCubeCache() {}
}
