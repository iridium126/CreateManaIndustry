package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

import net.minecraft.world.level.block.state.BlockState;

import com.iridium126.createmanaindustry.client.dimension.AllvrClientCubeCache;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.dimension.mesh.AllvrMesher;

/**
 * Daemon worker that turns streamed cubes into greedy-meshed quad streams
 * (doc §8.2 M0). Jobs carry the submitting renderer session's epoch — a
 * result whose epoch is stale (level switched in between) is discarded by
 * the drain instead of resurrecting old-session geometry (sodium-parity plan
 * §7.1). The snapshot is taken through
 * {@link AllvrClientCubeCache#snapshotForMesher} — the lock is held only for
 * the neighborhood lookup, eight private section copies and the padding
 * strips, so main-thread applies/writes never queue behind a full snapshot.
 * Results are drained by the render thread in {@code AllvrRenderer}.
 * <p>
 * Result statuses (plan §7.1): every dequeued job eventually produces a
 * result of one of the four states, and the renderer settles the cube's
 * pending state on EVERY outcome — an exception inside the worker can no
 * longer strand a cube's pending entry forever (the old drop-on-throw path).
 * FAILED_RETRYABLE is a per-job failure (bad snapshot, mesher bug): logged,
 * not auto-retried — the next edit to the cube re-triggers it. FAILED_FATAL
 * (JVM/linkage errors) additionally logs at error. CANCELLED covers jobs
 * dropped by {@link #cancel} before execution.
 * <p>
 * V0 keeps one worker: a burst of 24 streamed cubes/tick meshes at a few ms
 * per cube, so results lag the stream slightly during load spikes — load
 * order pop-in, not data loss (a re-submitted job is deduped upstream). The
 * multi-worker priority scheduler is M3.
 */
public final class AllvrMesherWorker {

    /** Outcome of one mesh job (plan §7.1 four-state contract). */
    public enum Status { SUCCESS, CANCELLED, FAILED_RETRYABLE, FAILED_FATAL }

    /** One settled mesh job, ready to upload (or already failed). */
    public record MeshResult(long key, long epoch, long[] quads, Status status) {}

    /** One queued job: the cube key plus the submitting session's epoch. */
    private record Job(long key, long epoch) {}

    private static final LinkedBlockingQueue<Job> JOBS = new LinkedBlockingQueue<>();
    private static final ConcurrentLinkedQueue<MeshResult> RESULTS = new ConcurrentLinkedQueue<>();
    private static volatile boolean running;
    private static Thread thread;

    public static void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(AllvrMesherWorker::run, "CMI-AllvrMesher");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        running = false;
        JOBS.clear();
        RESULTS.clear();
        thread = null;
    }

    /** Queues a remesh for {@code key} under {@code epoch}; deduped upstream. */
    public static void submit(long key, long epoch) {
        JOBS.add(new Job(key, epoch));
    }

    /**
     * Best-effort cancellation of queued jobs for {@code key} (cube forgotten
     * / level dropped). A job already dequeued or mid-run still settles with
     * a result — the renderer's pending bookkeeping handles that outcome.
     */
    public static void cancel(long key) {
        JOBS.removeIf(job -> job.key() == key);
    }

    /** Thread handle for lazy startup checks (null before the first start). */
    public static Thread threadOrNull() {
        return thread;
    }

    /** Drops queued jobs/results (level switch); the worker thread stays up. */
    public static void clearQueues() {
        JOBS.clear();
        RESULTS.clear();
    }

    public static MeshResult poll() {
        return RESULTS.poll();
    }

    private static void run() {
        while (running) {
            Job job = null;
            try {
                job = JOBS.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (job != null) {
                    process(job);
                }
            } catch (InterruptedException ignored) {
                return;
            } catch (Throwable t) {
                // keep the worker alive: a dead thread silently ends ALL remeshing
                // (the renderer's lazy-start check only sees a non-null handle);
                // the failed job still settles so its pending entry drains
                CreateManaIndustry.LOGGER.error("[Allvr] mesher failed on cube {}, job settled as failure",
                    job == null ? "?" : job.key(), t);
                if (job != null) {
                    RESULTS.add(new MeshResult(job.key(), job.epoch(), new long[0], statusOf(t)));
                }
            }
        }
    }

    /** JVM-breaking throwables are fatal, everything else is retryable-on-edit. */
    private static Status statusOf(Throwable t) {
        return t instanceof VirtualMachineError || t instanceof LinkageError
            ? Status.FAILED_FATAL
            : Status.FAILED_RETRYABLE;
    }

    private static void process(Job job) {
        try {
            BlockState[] states = new BlockState[AllvrMesher.PADDED * AllvrMesher.PADDED * AllvrMesher.PADDED];
            byte[] occludes = new byte[states.length];
            // Snapshot via the cache's copy-based path: the lock is held only for
            // the neighborhood lookup + section copies + padding strips; the 32³
            // interior is filled outside it (the old per-voxel scan held the lock
            // for the full 39k reads and stalled main-thread cube writes).
            AllvrClientCubeCache.snapshotForMesher(job.key(), states, occludes);
            // light bake always on: cheap (column scans with per-section skips +
            // a tiny emitter table), keeps the quad stream format config-agnostic
            AllvrLightBaker light = AllvrLightBaker.capture(job.key(), occludes);
            RESULTS.add(new MeshResult(job.key(), job.epoch(),
                AllvrMesher.build(states, occludes, light, AllvrRenderStateMap.CLIENT_CODEC), Status.SUCCESS));
        } catch (Throwable t) {
            CreateManaIndustry.LOGGER.error("[Allvr] mesher job failed on cube {}",
                job.key(), t);
            RESULTS.add(new MeshResult(job.key(), job.epoch(), new long[0], statusOf(t)));
        }
    }

    private AllvrMesherWorker() {}
}
