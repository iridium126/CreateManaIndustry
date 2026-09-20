package com.iridium126.createmanaindustry.client.particles;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.iridium126.createmanaindustry.CMIBlocks;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.iridium126.createmanaindustry.client.particles.engine.GlowingVineSpecs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Client bridge that emits glowing-vine particles only through the GPU engine. */
public final class GlowingVineParticleClient {
    /** Per attached face, matching the requested 100 particles/second rate. */
    private static final double STREAM_RATE = 100.0;
    /** Spread chunk discovery over ticks instead of scanning the full range at once. */
    private static final int SCAN_CHUNKS_PER_TICK = 4;
    /** Retry unloaded chunks and recheck the block-to-range boundary at 2 Hz. */
    private static final long SCAN_RECHECK_TICKS = 10L;

    private static final Direction[] FACES = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP
    };

    private record StreamKey(Level level, long blockPos, Direction face) {
    }

    private static final Map<StreamKey, CMIParticleEngine.StreamHandle> ACTIVE_STREAMS = new HashMap<>();
    private static final Map<Long, Set<Long>> VINES_BY_CHUNK = new HashMap<>();
    private static final Set<Long> SCANNED_CHUNKS = new HashSet<>();
    private static final Set<Long> QUEUED_CHUNKS = new HashSet<>();
    private static final java.util.ArrayDeque<Long> CHUNK_SCAN_QUEUE = new java.util.ArrayDeque<>();
    private static final java.util.function.Predicate<BlockState> IS_GLOWING_VINE =
            state -> state.is(CMIBlocks.GLOWING_VINE.get());

    private static int scanCenterChunkX = Integer.MIN_VALUE;
    private static int scanCenterChunkZ = Integer.MIN_VALUE;
    private static int scanRadiusChunks = -1;
    private static long lastScanRecheck = Long.MIN_VALUE;

    /**
     * Keeps the candidate index and active streams aligned with client block
     * updates. This is the fast path for vines placed/removed after a chunk was
     * scanned, and avoids waiting for vanilla's sparse animate-tick sampling.
     */
    public static void onBlockChanged(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        Set<Long> vines = VINES_BY_CHUNK.get(chunkKey);
        if (state.is(CMIBlocks.GLOWING_VINE.get())) {
            if (vines == null) {
                vines = new HashSet<>();
                VINES_BY_CHUNK.put(chunkKey, vines);
            }
            vines.add(pos.asLong());
        } else if (vines != null) {
            vines.remove(pos.asLong());
            if (vines.isEmpty())
                VINES_BY_CHUNK.remove(chunkKey);
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level && minecraft.player != null)
            reconcileBlock(level, pos, state, minecraft.gameRenderer.getMainCamera().getPosition(),
                    CMIParticleEngine.INSTANCE.fadeDistanceBlocks(),
                    CMIParticleEngine.INSTANCE.renderDistanceBlocks());
    }

    /** Drops candidates and streams owned by a client chunk that was unloaded. */
    public static void onChunkUnloaded(ClientLevel level, ChunkPos chunkPos) {
        long chunkKey = chunkPos.toLong();
        SCANNED_CHUNKS.remove(chunkKey);
        QUEUED_CHUNKS.remove(chunkKey);
        CHUNK_SCAN_QUEUE.remove(chunkKey);
        VINES_BY_CHUNK.remove(chunkKey);

        Iterator<Map.Entry<StreamKey, CMIParticleEngine.StreamHandle>> it = ACTIVE_STREAMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<StreamKey, CMIParticleEngine.StreamHandle> entry = it.next();
            StreamKey key = entry.getKey();
            BlockPos pos = BlockPos.of(key.blockPos());
            if (key.level() == level
                    && ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4) == chunkKey) {
                CMIParticleEngine.INSTANCE.removeStream(entry.getValue());
                it.remove();
            }
        }
    }

    /**
     * Removes streams that no longer have a visible reason to exist and
     * incrementally discovers vines as chunks enter the GPU fade range.
     */
    public static void tick(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null)
            return;

        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double fadeDistance = engine.fadeDistanceBlocks();
        double renderDistance = engine.renderDistanceBlocks();
        double fadeDistanceSqr = fadeDistance * fadeDistance;
        double maxDistanceSqr = renderDistance * renderDistance;

        scheduleChunkDiscovery(level, camera, renderDistance);
        long now = level.getGameTime();
        boolean recheckWindow = lastScanRecheck == Long.MIN_VALUE
                || now - lastScanRecheck >= SCAN_RECHECK_TICKS;
        if (recheckWindow) {
            lastScanRecheck = now;
            activateCandidatesInRange(level, camera, fadeDistanceSqr, maxDistanceSqr);
        }
        for (int i = 0; i < SCAN_CHUNKS_PER_TICK && !CHUNK_SCAN_QUEUE.isEmpty(); i++) {
            long chunkKey = CHUNK_SCAN_QUEUE.removeFirst();
            QUEUED_CHUNKS.remove(chunkKey);
            scanChunk(level, chunkKey, camera, fadeDistanceSqr, maxDistanceSqr);
        }

        Iterator<Map.Entry<StreamKey, CMIParticleEngine.StreamHandle>> it = ACTIVE_STREAMS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<StreamKey, CMIParticleEngine.StreamHandle> entry = it.next();
            StreamKey key = entry.getKey();
            BlockPos pos = BlockPos.of(key.blockPos());
            if (key.level() != level) {
                engine.removeStream(entry.getValue());
                it.remove();
                continue;
            }
            BlockState state = level.getBlockState(pos);
            boolean faceStillExists = state.is(CMIBlocks.GLOWING_VINE.get())
                    && state.getValue(VineBlock.getPropertyForFace(key.face()));
            boolean tooFar = Vec3.atCenterOf(pos).distanceToSqr(camera) > maxDistanceSqr;
            if (!faceStillExists || tooFar) {
                engine.removeStream(entry.getValue());
                it.remove();
            }
        }
    }

    private static void scheduleChunkDiscovery(ClientLevel level, Vec3 camera, double renderDistance) {
        long now = level.getGameTime();
        int centerX = ((int) Math.floor(camera.x)) >> 4;
        int centerZ = ((int) Math.floor(camera.z)) >> 4;
        int radius = Math.max(1, (int) Math.ceil(renderDistance / 16.0) + 1);
        boolean windowChanged = centerX != scanCenterChunkX || centerZ != scanCenterChunkZ
                || radius != scanRadiusChunks;
        if (windowChanged) {
            scanCenterChunkX = centerX;
            scanCenterChunkZ = centerZ;
            scanRadiusChunks = radius;
            pruneScanWindow(centerX, centerZ, radius + 1);
        }

        boolean recheck = lastScanRecheck == Long.MIN_VALUE
                || now - lastScanRecheck >= SCAN_RECHECK_TICKS;
        if (windowChanged || recheck)
            enqueueChunkWindow(centerX, centerZ, radius);
    }

    private static void pruneScanWindow(int centerX, int centerZ, int keepRadius) {
        SCANNED_CHUNKS.removeIf(key -> outsideChunkRadius(key, centerX, centerZ, keepRadius));
        VINES_BY_CHUNK.keySet().removeIf(key -> outsideChunkRadius(key, centerX, centerZ, keepRadius));

        Iterator<Long> it = CHUNK_SCAN_QUEUE.iterator();
        while (it.hasNext()) {
            long key = it.next();
            if (outsideChunkRadius(key, centerX, centerZ, keepRadius)) {
                it.remove();
                QUEUED_CHUNKS.remove(key);
            }
        }
    }

    private static boolean outsideChunkRadius(long key, int centerX, int centerZ, int radius) {
        return Math.abs(ChunkPos.getX(key) - centerX) > radius
                || Math.abs(ChunkPos.getZ(key) - centerZ) > radius;
    }

    private static void enqueueChunkWindow(int centerX, int centerZ, int radius) {
        // Near rings first: the center and inner chunks become active without
        // waiting behind the far edge of the GPU fade window.
        for (int ring = 0; ring <= radius; ring++) {
            if (ring == 0) {
                enqueueChunk(ChunkPos.asLong(centerX, centerZ));
                continue;
            }
            for (int dx = -ring; dx <= ring; dx++) {
                enqueueChunk(ChunkPos.asLong(centerX + dx, centerZ - ring));
                enqueueChunk(ChunkPos.asLong(centerX + dx, centerZ + ring));
            }
            for (int dz = -ring + 1; dz < ring; dz++) {
                enqueueChunk(ChunkPos.asLong(centerX - ring, centerZ + dz));
                enqueueChunk(ChunkPos.asLong(centerX + ring, centerZ + dz));
            }
        }
    }

    private static void enqueueChunk(long key) {
        if (!SCANNED_CHUNKS.contains(key) && QUEUED_CHUNKS.add(key))
            CHUNK_SCAN_QUEUE.addLast(key);
    }

    private static void scanChunk(ClientLevel level, long chunkKey, Vec3 camera,
            double fadeDistanceSqr, double maxDistanceSqr) {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        if (!engine.available())
            return;

        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);
        var chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        if (chunk == null)
            return; // retry from the discovery window once the client loads it

        SCANNED_CHUNKS.add(chunkKey);
        Set<Long> found = new HashSet<>();
        var sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            if (!section.maybeHas(IS_GLOWING_VINE))
                continue;
            int baseY = (level.getMinSection() + sectionIndex) << 4;
            BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
            for (int localX = 0; localX < 16; localX++) {
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        BlockState state = section.getBlockState(localX, localY, localZ);
                        if (!state.is(CMIBlocks.GLOWING_VINE.get()))
                            continue;
                        mutable.set((chunkX << 4) + localX, baseY + localY, (chunkZ << 4) + localZ);
                        found.add(mutable.asLong());
                    }
                }
            }
        }

        if (found.isEmpty())
            VINES_BY_CHUNK.remove(chunkKey);
        else
            VINES_BY_CHUNK.put(chunkKey, found);
        for (long packed : found) {
            BlockPos pos = BlockPos.of(packed);
            reconcileBlock(level, pos, level.getBlockState(pos), camera, fadeDistanceSqr, maxDistanceSqr);
        }
    }

    private static void activateCandidatesInRange(ClientLevel level, Vec3 camera,
            double fadeDistanceSqr, double maxDistanceSqr) {
        Iterator<Map.Entry<Long, Set<Long>>> chunkIt = VINES_BY_CHUNK.entrySet().iterator();
        while (chunkIt.hasNext()) {
            Set<Long> candidates = chunkIt.next().getValue();
            Iterator<Long> posIt = candidates.iterator();
            while (posIt.hasNext()) {
                BlockPos pos = BlockPos.of(posIt.next());
                BlockState state = level.getBlockState(pos);
                if (!state.is(CMIBlocks.GLOWING_VINE.get())) {
                    posIt.remove();
                    removeBlockStreams(level, pos);
                    continue;
                }
                reconcileBlock(level, pos, state, camera, fadeDistanceSqr, maxDistanceSqr);
            }
            if (candidates.isEmpty())
                chunkIt.remove();
        }
    }

    private static void reconcileBlock(ClientLevel level, BlockPos pos, BlockState state,
            Vec3 camera, double fadeDistanceSqr, double maxDistanceSqr) {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        if (!state.is(CMIBlocks.GLOWING_VINE.get())) {
            removeBlockStreams(level, pos);
            return;
        }
        double distanceSqr = Vec3.atCenterOf(pos).distanceToSqr(camera);
        if (distanceSqr > maxDistanceSqr) {
            removeBlockStreams(level, pos);
            return;
        }
        // Match additive.fsh/model.fsh exactly: do not create until the
        // shader's configured fade interval begins, but keep an existing
        // stream alive through the 24-block fade ramp.
        if (distanceSqr > fadeDistanceSqr)
            return;
        if (!engine.available())
            return;

        Vec3 center = Vec3.atCenterOf(pos);
        for (Direction face : FACES) {
            StreamKey key = new StreamKey(level, pos.asLong(), face);
            if (state.getValue(VineBlock.getPropertyForFace(face))) {
                if (ACTIVE_STREAMS.containsKey(key))
                    continue;
                Vec3 normal = new Vec3(face.getStepX(), face.getStepY(), face.getStepZ());
                Vec3 origin = center.add(normal.scale(0.452));
                CMIParticleEngine.StreamHandle handle = engine.stream(
                        GlowingVineSpecs.forFace(face), origin, STREAM_RATE, 0.0);
                ACTIVE_STREAMS.put(key, handle);
            } else {
                removeStream(key);
            }
        }
    }

    private static void removeBlockStreams(Level level, BlockPos pos) {
        for (Direction face : FACES)
            removeStream(new StreamKey(level, pos.asLong(), face));
    }

    private static void removeStream(StreamKey key) {
        CMIParticleEngine.StreamHandle handle = ACTIVE_STREAMS.remove(key);
        if (handle != null)
            CMIParticleEngine.INSTANCE.removeStream(handle);
    }

    /** Removes all vine streams when the client level changes. */
    public static void clear() {
        CMIParticleEngine engine = CMIParticleEngine.INSTANCE;
        for (CMIParticleEngine.StreamHandle handle : ACTIVE_STREAMS.values())
            engine.removeStream(handle);
        ACTIVE_STREAMS.clear();
        VINES_BY_CHUNK.clear();
        SCANNED_CHUNKS.clear();
        QUEUED_CHUNKS.clear();
        CHUNK_SCAN_QUEUE.clear();
        scanCenterChunkX = Integer.MIN_VALUE;
        scanCenterChunkZ = Integer.MIN_VALUE;
        scanRadiusChunks = -1;
        lastScanRecheck = Long.MIN_VALUE;
    }

    private GlowingVineParticleClient() {
    }
}
