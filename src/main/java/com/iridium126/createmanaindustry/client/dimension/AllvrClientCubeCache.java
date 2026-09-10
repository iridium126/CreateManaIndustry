package com.iridium126.createmanaindustry.client.dimension;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
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
    /** Incremented on level teardown so a late decode cannot publish into a
     * newly created Allay level with the same dimension id. */
    private static final AtomicLong CLIENT_SESSION_EPOCH = new AtomicLong();
    /** Latest packet sequence per cube; prevents an older async decode from
     * replacing a newer packet that finished first. */
    private static final ConcurrentHashMap<Long, Long> LATEST_PACKET_SEQUENCE =
        new ConcurrentHashMap<>();
    private static final ConcurrentLinkedQueue<DecodedCube> COMPLETED_CUBES =
        new ConcurrentLinkedQueue<>();
    /** Monotonic ALLVR content revision used by immutable Sodium snapshots. */
    private static volatile long contentRevision;
    private static final Long2ObjectOpenHashMap<AllvrCube> cubes = new Long2ObjectOpenHashMap<>();
    /** Cubes that hold block entities — the client ticking worklist (mirrors
     *  the server cube map's registry; most cubes are pure terrain). */
    private static final Long2ObjectOpenHashMap<AllvrCube> beCubes = new Long2ObjectOpenHashMap<>();

    private record DecodedCube(ClientLevel targetLevel, long sessionEpoch, long cubePos,
                               int payloadBytes, AllvrCube cube, long sequence,
                               Throwable failure) {}

    /** Binds the current client level (called on LevelEvent.Load). */
    public static void onLevelChanged(ClientLevel clientLevel) {
        level = clientLevel;
        com.iridium126.createmanaindustry.dimension.AllvrClientBlockHook.setBiomeResolver(AllvrClientCubeCache::getNoiseBiome);
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

    public static long contentRevision() {
        synchronized (LOCK) {
            return contentRevision;
        }
    }

    /** Lock-free render-thread generation check for immutable Sodium caches. */
    public static long contentRevisionVolatile() {
        return contentRevision;
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
            for (AllvrCube cube : cubes.values()) {
                cube.onUnload();
            }
            cubes.clear();
            beCubes.clear();
            contentRevision++;
            LATEST_PACKET_SEQUENCE.clear();
            COMPLETED_CUBES.clear();
            level = null;
        }
    }

    /**
     * Queues one streamed cube for off-thread packet deserialization.  Only
     * the publication and Sodium invalidation are performed on the client
     * game thread, mirroring vanilla's packet-to-ChunkMap handoff.
     */
    public static void queueCube(ClientboundAllvrCubePacket packet) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        RegistryAccess registryAccess = clientLevel.registryAccess();
        long sequence = NEXT_PACKET_SEQUENCE.incrementAndGet();
        long sessionEpoch = CLIENT_SESSION_EPOCH.get();
        LATEST_PACKET_SEQUENCE.put(packet.cubePos(), sequence);
        CUBE_DECODE_EXECUTOR.execute(() -> decodeAndQueueApply(
            packet, clientLevel, registryAccess, sequence, sessionEpoch));
    }

    private static void decodeAndQueueApply(ClientboundAllvrCubePacket packet,
                                             ClientLevel targetLevel,
                                             RegistryAccess registryAccess,
                                             long sequence,
                                             long sessionEpoch) {
        AllvrCube cube = null;
        Throwable failure = null;
        try {
            cube = packet.decodeCube(registryAccess);
            // Prepare the renderer's compact opacity columns while the cube
            // is still unpublished.  Sodium's render thread can then build
            // light snapshots without scanning 32^3 block states on demand.
            cube.opacityColumns();
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

    /** Applies all completed packet decodes on the client game thread. Packet
     * workers only append to {@link #COMPLETED_CUBES}; the handoff itself is
     * not artificially delayed, matching the vanilla packet lifecycle. */
    public static void tick() {
        DecodedCube decoded;
        while ((decoded = COMPLETED_CUBES.poll()) != null) {
            applyDecodedCube(decoded.targetLevel(), decoded.sessionEpoch(), decoded.cubePos(),
                decoded.payloadBytes(), decoded.cube(), decoded.sequence(), decoded.failure());
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
        AllvrSodiumBridge.onCubeApplied(cubePos);
        if (CreateManaIndustry.LOGGER.isDebugEnabled()) {
            CreateManaIndustry.LOGGER.debug("[Allvr] cube {} streamed ({} bytes, {} cubes cached)",
                cube.getPos(), payloadBytes, cubes.size());
        }
    }

    /**
     * Synchronous compatibility entry point for code that explicitly wants
     * to apply a packet on the client thread. Network handlers must use
     * {@link #queueCube} so section decoding never runs on that thread.
     */
    public static void applyCube(ClientboundAllvrCubePacket packet) {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null || clientLevel.dimension() != AllvrDimensions.ALLAY_LEVEL) {
            return;
        }
        try {
            long sequence = NEXT_PACKET_SEQUENCE.incrementAndGet();
            LATEST_PACKET_SEQUENCE.put(packet.cubePos(), sequence);
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
        LATEST_PACKET_SEQUENCE.put(cubePos, NEXT_PACKET_SEQUENCE.incrementAndGet());
        synchronized (LOCK) {
            AllvrCube old = cubes.remove(cubePos);
            if (old != null) {
                old.onUnload();
            }
            beCubes.remove(cubePos);
            contentRevision++;
        }
        AllvrSodiumBridge.onCubeForgotten(cubePos);
    }

    /** Keeps the block-entity worklist in step with a cube's BE set. */
    private static void refreshBeCube(long key, AllvrCube cube) {
        if (cube.hasBlockEntities()) {
            beCubes.put(key, cube);
        } else {
            beCubes.remove(key);
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
        // snapshot: a ticker can write blocks (adding/removing BEs → registry writes)
        for (AllvrCube cube : beCubes.values().toArray(new AllvrCube[0])) {
            if (!cube.hasBlockEntities()) {
                continue;
            }
            AllvrCubePos cpos = cube.getPos();
            if (Math.abs(cpos.getX() - pc.getX()) <= 8 && Math.abs(cpos.getZ() - pc.getZ()) <= 8
                && Math.abs(cpos.getY() - pc.getY()) <= 8) {
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
     * inside a streamed cube. Light-emitter bookkeeping mirrors the server so
     * the emitter table stays consistent for the phase-3 synthetic light.
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

            int oldEmission = oldState.getLightEmission(clientLevel, pos);
            int newEmission = newState.getLightEmission(clientLevel, pos);
            if (oldEmission > 0) {
                cube.removeEmitter(pos);
            }
            if (newEmission > 0) {
                cube.putEmitter(pos, newEmission);
            }
        }
        // The cache is updated before the Sodium notification so its build
        // snapshot always observes the new state.
        AllvrSodiumBridge.onBlockChanged(pos, oldState, newState);

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

    public static int size() {
        return cubes.size();
    }

    private AllvrClientCubeCache() {}
}
