package com.iridium126.createmanaindustry.client.particles;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Incremental discovery for all registered GPU block emitters; no active-block polling. */
public final class BlockParticleEmitterClient {
    private static final int SCAN_SECTIONS_PER_TICK = 8;
    private static final int SCAN_CHUNKS_PER_TICK = 4;
    private static final Long2ObjectOpenHashMap<Long2ObjectOpenHashMap<IntArrayList>> SOURCES_BY_CHUNK = new Long2ObjectOpenHashMap<>();
    private static final Set<Long> SCANNED_CHUNKS = new HashSet<>();
    private static final Set<Long> QUEUED_CHUNKS = new HashSet<>();
    private static final java.util.ArrayDeque<Long> CHUNK_SCAN_QUEUE = new java.util.ArrayDeque<>();
    private static final java.util.function.Predicate<BlockState> IS_EMITTER = BlockParticleEmitters::matches;
    private static ClientLevel owner;
    private static int scanCenterChunkX = Integer.MIN_VALUE;
    private static int scanCenterChunkZ = Integer.MIN_VALUE;
    private static int scanRadiusChunks = -1;
    private static long scanningChunk;
    private static int scanningSection = -1;

    public static void onBlockChanged(ClientLevel level, BlockPos pos) {
        if (level != owner) return;
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (!SCANNED_CHUNKS.contains(key) && !(scanningSection >= 0 && scanningChunk == key)) return;
        if (!CMIParticleEngine.INSTANCE.available()) {
            onChunkLoaded(level, new ChunkPos(pos));
            return;
        }
        replaceBlock(key, pos, level.getBlockState(pos));
    }

    private static void replaceBlock(long key, BlockPos pos, BlockState state) {
        var blocks = SOURCES_BY_CHUNK.get(key);
        if (blocks != null) {
            var old = blocks.remove(pos.asLong());
            if (old != null) for (int slot : old) CMIParticleEngine.INSTANCE.blockEmitters().remove(slot);
        }
        if (!BlockParticleEmitters.matches(state)) return;
        var slots = new IntArrayList(5);
        BlockParticleEmitters.collect(state, source -> {
            int id = CMIParticleEngine.INSTANCE.ensureEmitter(source.spec());
            if (id < 0) return;
            int slot = CMIParticleEngine.INSTANCE.blockEmitters().add(
                    pos.getX() + source.x(), pos.getY() + source.y(), pos.getZ() + source.z(),
                    source.rate(), id, source.cullRadius());
            if (slot >= 0) slots.add(slot);
        });
        if (!slots.isEmpty()) {
            if (blocks == null) {
                blocks = new Long2ObjectOpenHashMap<>();
                SOURCES_BY_CHUNK.put(key, blocks);
            }
            blocks.put(pos.asLong(), slots);
        }
    }

    private static void removeChunkSources(long key) {
        var blocks = SOURCES_BY_CHUNK.get(key);
        if (blocks != null) for (var slots : blocks.values())
            for (int slot : slots) CMIParticleEngine.INSTANCE.blockEmitters().remove(slot);
    }

    public static void onChunkUnloaded(ClientLevel level, ChunkPos pos) {
        if (level != owner) return;
        long key = pos.toLong();
        removeChunkSources(key);
        SOURCES_BY_CHUNK.remove(key);
        SCANNED_CHUNKS.remove(key);
        QUEUED_CHUNKS.remove(key);
        CHUNK_SCAN_QUEUE.remove(key);
        if (scanningSection >= 0 && scanningChunk == key) scanningSection = -1;
    }

    /** Full chunk packets may replace an existing chunk without an unload. */
    public static void onChunkLoaded(ClientLevel level, ChunkPos pos) {
        if (level != owner) return;
        onChunkUnloaded(level, pos);
        long key = pos.toLong();
        if (scanRadiusChunks >= 0 && !outsideChunkRadius(key, scanCenterChunkX, scanCenterChunkZ, scanRadiusChunks))
            enqueueChunk(key);
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft.level != owner) {
            clear();
            owner = minecraft.level;
        }
        if (owner == null || minecraft.player == null || !CMIParticleEngine.INSTANCE.available()) return;
        BlockParticleEmitters.registerDefaults();
        scheduleChunkDiscovery(minecraft.gameRenderer.getMainCamera().getPosition(),
                CMIParticleEngine.INSTANCE.renderDistanceBlocks());
        int chunksStarted = 0;
        for (int budget = 0; budget < SCAN_SECTIONS_PER_TICK;) {
            if (scanningSection < 0) {
                if (CHUNK_SCAN_QUEUE.isEmpty() || chunksStarted++ >= SCAN_CHUNKS_PER_TICK) break;
                scanningChunk = CHUNK_SCAN_QUEUE.removeFirst();
                QUEUED_CHUNKS.remove(scanningChunk);
                scanningSection = 0;
            }
            var chunk = owner.getChunkSource().getChunk(ChunkPos.getX(scanningChunk), ChunkPos.getZ(scanningChunk), false);
            if (chunk == null) { scanningSection = -1; continue; }
            var sections = chunk.getSections();
            var section = sections[scanningSection];
            if (section.maybeHas(IS_EMITTER)) {
                budget++;
                int y0 = (owner.getMinSection() + scanningSection) << 4;
                var pos = new BlockPos.MutableBlockPos();
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (!BlockParticleEmitters.matches(state)) continue;
                    pos.set((ChunkPos.getX(scanningChunk) << 4) + x, y0 + y, (ChunkPos.getZ(scanningChunk) << 4) + z);
                    replaceBlock(scanningChunk, pos, state);
                }
            }
            if (++scanningSection >= sections.length) {
                SCANNED_CHUNKS.add(scanningChunk);
                scanningSection = -1;
            }
        }
    }

    private static void scheduleChunkDiscovery(Vec3 camera, double renderDistance) {
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

        if (windowChanged)
            enqueueChunkWindow(centerX, centerZ, radius);
    }

    private static void pruneScanWindow(int centerX, int centerZ, int keepRadius) {
        if (scanningSection >= 0 && outsideChunkRadius(scanningChunk, centerX, centerZ, keepRadius))
            scanningSection = -1;
        SCANNED_CHUNKS.removeIf(key -> outsideChunkRadius(key, centerX, centerZ, keepRadius));
        var chunks = SOURCES_BY_CHUNK.keySet().iterator();
        while (chunks.hasNext()) {
            long key = chunks.nextLong();
            if (outsideChunkRadius(key, centerX, centerZ, keepRadius)) {
                removeChunkSources(key);
                chunks.remove();
            }
        }

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
        if (!(scanningSection >= 0 && scanningChunk == key) && !SCANNED_CHUNKS.contains(key) && QUEUED_CHUNKS.add(key))
            CHUNK_SCAN_QUEUE.addLast(key);
    }


    public static void clear() {
        CMIParticleEngine.INSTANCE.blockEmitters().clear();
        SOURCES_BY_CHUNK.clear();
        SCANNED_CHUNKS.clear();
        QUEUED_CHUNKS.clear();
        CHUNK_SCAN_QUEUE.clear();
        scanningSection = -1;
        owner = null;
        scanCenterChunkX = scanCenterChunkZ = Integer.MIN_VALUE;
        scanRadiusChunks = -1;
    }

    private BlockParticleEmitterClient() {}
}
