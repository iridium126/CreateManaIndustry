package com.iridium126.createmanaindustry.client.dimension.render;

import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small Sodium-style priority executor for ALLVR cell builds.
 *
 * <p>The scheduler owns no Minecraft or GL state.  Callers provide immutable
 * jobs and consume immutable results on the render thread.  This makes level
 * epoch/revision filtering explicit and keeps a worker failure local to one
 * cell.
 */
public final class AllvrBuildScheduler<J, R> implements AutoCloseable {

    public enum Priority {
        SCREEN_FIRST(0),
        NEAR_CAMERA(1),
        VISIBLE_UPDATE(2),
        SEAM_HANDOFF(3),
        BACKGROUND(4);

        private final int rank;

        Priority(int rank) {
            this.rank = rank;
        }
    }

    public enum Status {
        SUCCESS,
        CANCELLED,
        FAILED_RETRYABLE,
        FAILED_FATAL
    }

    public record Job<J>(long key, long epoch, long incarnation, long resourceRevision,
                         long revision, Priority priority, J input,
                         long sequence, long jobId, AtomicBoolean cancellation) {}

    public record Result<R>(long key, long epoch, long incarnation, long resourceRevision,
                            long revision, Status status, R output, Throwable failure,
                            long jobId) {}

    @FunctionalInterface
    public interface Builder<J, R> {
        R build(J input, CancellationToken token) throws Exception;
    }

    public static final class CancellationToken {
        private final AtomicBoolean cancelled;

        private CancellationToken(AtomicBoolean cancelled) {
            this.cancelled = cancelled;
        }

        public boolean isCancelled() {
            return this.cancelled.get() || Thread.currentThread().isInterrupted();
        }
    }

    private final PriorityBlockingQueue<Job<J>> queue = new PriorityBlockingQueue<>(64,
        Comparator.<Job<J>>comparingInt(job -> job.priority().rank)
            .thenComparingLong(Job::sequence));
    private final java.util.concurrent.ConcurrentLinkedQueue<Result<R>> results =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Long, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> latestJobByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong jobIds = new AtomicLong();
    private final Builder<J, R> builder;
    private final Thread[] workers;
    private volatile boolean running = true;

    public AllvrBuildScheduler(int workerCount, Builder<J, R> builder) {
        this.builder = java.util.Objects.requireNonNull(builder, "builder");
        int count = Math.max(1, Math.min(workerCount, 8));
        this.workers = new Thread[count];
        for (int i = 0; i < count; i++) {
            Thread worker = new Thread(this::run, "CMI-AllvrBuild-" + i);
            worker.setDaemon(true);
            this.workers[i] = worker;
            worker.start();
        }
    }

    public int workerCount() {
        return this.workers.length;
    }

    public int queuedCount() {
        return this.queue.size();
    }

    public void submit(long key, long epoch, long revision, Priority priority, J input) {
        this.submit(key, epoch, 0L, 0L, revision, priority, input);
    }

    public void submit(long key, long epoch, long incarnation, long resourceRevision,
                       long revision, Priority priority, J input) {
        if (!this.running) {
            return;
        }
        long jobId = this.jobIds.incrementAndGet();
        AtomicBoolean cancelled = new AtomicBoolean();
        this.cancellations.put(jobId, cancelled);
        this.latestJobByKey.put(key, jobId);
        this.queue.add(new Job<>(key, epoch, incarnation, resourceRevision, revision,
            priority == null ? Priority.BACKGROUND : priority, input, this.sequence.getAndIncrement(),
            jobId, cancelled));
    }

    /** Cancels queued and in-flight jobs for a key; no result is required. */
    public void cancel(long key) {
        Long latest = this.latestJobByKey.get(key);
        if (latest != null) {
            AtomicBoolean flag = this.cancellations.get(latest);
            if (flag != null) {
                flag.set(true);
            }
        }
        for (var it = this.queue.iterator(); it.hasNext(); ) {
            Job<J> job = it.next();
            if (job.key() == key) {
                job.cancellation().set(true);
                it.remove();
                this.results.add(new Result<>(job.key(), job.epoch(), job.incarnation(),
                    job.resourceRevision(), job.revision(), Status.CANCELLED, null, null,
                    job.jobId()));
                this.finish(job);
            }
        }
    }

    public Result<R> poll() {
        return this.results.poll();
    }

    public void clear() {
        this.queue.clear();
        this.results.clear();
        for (AtomicBoolean flag : this.cancellations.values()) {
            flag.set(true);
        }
        this.cancellations.clear();
        this.latestJobByKey.clear();
    }

    private void run() {
        while (this.running) {
            Job<J> job = null;
            try {
                job = this.queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (job == null) {
                    continue;
                }
                CancellationToken token = new CancellationToken(job.cancellation());
                if (token.isCancelled()) {
                    this.results.add(new Result<>(job.key(), job.epoch(), job.incarnation(),
                        job.resourceRevision(), job.revision(), Status.CANCELLED, null, null,
                        job.jobId()));
                    this.finish(job);
                    continue;
                }
                R output = this.builder.build(job.input(), token);
                this.results.add(new Result<>(job.key(), job.epoch(), job.incarnation(),
                    job.resourceRevision(), job.revision(),
                    token.isCancelled() ? Status.CANCELLED : Status.SUCCESS, output, null,
                    job.jobId()));
                this.finish(job);
            } catch (Throwable failure) {
                if (job != null) {
                    Status status = failure instanceof VirtualMachineError || failure instanceof LinkageError
                        ? Status.FAILED_FATAL : Status.FAILED_RETRYABLE;
                    this.results.add(new Result<>(job.key(), job.epoch(), job.incarnation(),
                        job.resourceRevision(), job.revision(), status, null, failure,
                        job.jobId()));
                    this.finish(job);
                }
            }
        }
    }

    private void finish(Job<J> job) {
        this.cancellations.remove(job.jobId());
        this.latestJobByKey.remove(job.key(), job.jobId());
    }

    @Override
    public void close() {
        this.running = false;
        this.clear();
        for (Thread worker : this.workers) {
            worker.interrupt();
        }
        for (Thread worker : this.workers) {
            try {
                worker.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        this.results.clear();
    }
}
