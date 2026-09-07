package com.iridium126.createmanaindustry.client.dimension.render;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMeshCodec;

/**
 * Multi-worker cell builder facade.
 *
 * <p>The old implementation was a single FIFO queue over 32³ cubes.  The
 * public facade is kept stable for the renderer, but jobs now carry epoch and
 * content revision and are ordered by the Sodium-inspired priority scheduler.
 * Every job settles with one of the four explicit statuses.
 */
public final class AllvrMesherWorker {

    public enum Status { SUCCESS, CANCELLED, FAILED_RETRYABLE, FAILED_FATAL }

    public record MeshResult(long key, long epoch, long incarnation, long resourceRevision,
                             long revision, long[] quads, int connectivityMask,
                             AllvrFallbackBlock[] fallbackBlocks,
                             Status status, Throwable failure) {
        public MeshResult(long key, long epoch, long revision, long[] quads, int connectivityMask,
                          Status status, Throwable failure) {
            this(key, epoch, 0L, 0L, revision, quads, connectivityMask,
                new AllvrFallbackBlock[0], status, failure);
        }

        public MeshResult(long key, long epoch, long[] quads, Status status) {
            this(key, epoch, 0L, 0L, 0L, quads, 0, new AllvrFallbackBlock[0], status, null);
        }
    }

    public record BuildOutput(long[] quads, int connectivityMask,
                              AllvrFallbackBlock[] fallbackBlocks) {
        public BuildOutput(long[] quads, int connectivityMask) {
            this(quads, connectivityMask, new AllvrFallbackBlock[0]);
        }
    }

    private static AllvrBuildScheduler<Long, BuildOutput> scheduler;
    private static volatile Thread marker;

    public static synchronized void start() {
        if (scheduler != null) {
            return;
        }
        int processors = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(1, Math.min(8, processors - 1));
        scheduler = new AllvrBuildScheduler<>(workers, AllvrMesherWorker::buildCell);
        marker = Thread.currentThread();
        CreateManaIndustry.LOGGER.info("[Allvr] cell build scheduler started with {} workers", workers);
    }

    public static synchronized void stop() {
        if (scheduler != null) {
            scheduler.close();
            scheduler = null;
        }
        marker = null;
    }

    public static void submit(long key, long epoch) {
        submit(key, epoch, 0L, AllvrBuildScheduler.Priority.BACKGROUND);
    }

    public static void submit(long key, long epoch, long revision,
                              AllvrBuildScheduler.Priority priority) {
        submit(key, epoch, 0L, 0L, revision, priority);
    }

    public static void submit(long key, long epoch, long incarnation, long resourceRevision,
                              long revision, AllvrBuildScheduler.Priority priority) {
        start();
        scheduler.submit(key, epoch, incarnation, resourceRevision, revision, priority, key);
    }

    public static void cancel(long key) {
        AllvrBuildScheduler<Long, BuildOutput> current = scheduler;
        if (current != null) {
            current.cancel(key);
        }
    }

    public static Thread threadOrNull() {
        return marker;
    }

    public static void clearQueues() {
        AllvrBuildScheduler<Long, BuildOutput> current = scheduler;
        if (current != null) {
            current.clear();
        }
    }

    public static MeshResult poll() {
        AllvrBuildScheduler<Long, BuildOutput> current = scheduler;
        if (current == null) {
            return null;
        }
        AllvrBuildScheduler.Result<BuildOutput> result = current.poll();
        if (result == null) {
            return null;
        }
        BuildOutput output = result.output();
        return new MeshResult(result.key(), result.epoch(), result.incarnation(),
            result.resourceRevision(), result.revision(),
            output == null ? null : output.quads(),
            output == null ? 0 : output.connectivityMask(),
            output == null ? new AllvrFallbackBlock[0] : output.fallbackBlocks(),
            switch (result.status()) {
                case SUCCESS -> Status.SUCCESS;
                case CANCELLED -> Status.CANCELLED;
                case FAILED_RETRYABLE -> Status.FAILED_RETRYABLE;
                case FAILED_FATAL -> Status.FAILED_FATAL;
            }, result.failure());
    }

    public static int queuedCount() {
        AllvrBuildScheduler<Long, BuildOutput> current = scheduler;
        return current == null ? 0 : current.queuedCount();
    }

    private static BuildOutput buildCell(Long key, AllvrBuildScheduler.CancellationToken token) {
        AllvrCellSnapshotPool.Snapshot snapshot = AllvrCellSnapshotPool.acquire();
        BlockState[] states = snapshot.states();
        byte[] occludes = snapshot.occludes();
        if (token.isCancelled()) {
            return null;
        }
        AllvrClientCubeCache.snapshotForCell(key, states, occludes);
        if (token.isCancelled()) {
            return null;
        }
        AllvrMeshCodec codec = AllvrRenderStateMap.CLIENT_CODEC;
        AllvrCellLightBaker light = AllvrCellLightBaker.capture(key, occludes);
        return new BuildOutput(AllvrCellMesher.build(states, occludes, light, codec),
            AllvrCellConnectivity.mask(occludes), collectFallbackBlocks(key, states, light));
    }

    private static AllvrFallbackBlock[] collectFallbackBlocks(long key, BlockState[] states,
                                                               AllvrCellLightBaker light) {
        java.util.ArrayList<AllvrFallbackBlock> blocks = new java.util.ArrayList<>();
        int minX = AllvrRenderCellKey.minBlockX(key);
        int minY = AllvrRenderCellKey.minBlockY(key);
        int minZ = AllvrRenderCellKey.minBlockZ(key);
        for (int y = 0; y < AllvrCellMesher.CELL; y++) {
            for (int z = 0; z < AllvrCellMesher.CELL; z++) {
                for (int x = 0; x < AllvrCellMesher.CELL; x++) {
                    BlockState state = states[AllvrCellMesher.paddedIndex(x, y, z)];
                    if (state.isAir()) {
                        continue;
                    }
                    short id = AllvrRenderStateMap.idOf(state);
                    boolean fluid = !state.getFluidState().isEmpty();
                    boolean descriptor = id != AllvrRenderStateMap.ID_AIR
                        && AllvrRenderStateMap.entryOf(id).renderable;
                    // Model and fluid ownership are independent. A waterlogged
                    // stair/fence must submit both its block model and its
                    // liquid surface; one boolean must not erase the other.
                    boolean model = !descriptor;
                    boolean renderFluid = fluid;
                    if (model || renderFluid) {
                        int sky = light.sky(x, z, (long) minY + y);
                        int block = light.block(x, z, (long) minY + y);
                        RenderType type = renderFluid
                            ? ItemBlockRenderTypes.getRenderLayer(state.getFluidState())
                            : ItemBlockRenderTypes.getChunkRenderType(state);
                        blocks.add(new AllvrFallbackBlock(minX + x, minY + y, minZ + z, state,
                            LightTexture.pack(block, sky), model, renderFluid,
                            type == RenderType.translucent()));
                    }
                }
            }
        }
        return blocks.toArray(AllvrFallbackBlock[]::new);
    }

    private AllvrMesherWorker() {}
}
