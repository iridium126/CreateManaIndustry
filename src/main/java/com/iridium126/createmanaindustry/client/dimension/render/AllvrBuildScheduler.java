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

    public record Job<J>(long key, long epoch, long revision, Priority priority, J input,
                         long sequence) {}

    public record Result<R>(long key, long epoch, long revision, Status status, R output,
                            Throwable failure) {}

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
    private final AtomicLong sequence = new AtomicLong();
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
        if (!this.running) {
            return;
        }
        AtomicBoolean cancelled = this.cancellations.computeIfAbsent(key, ignored -> new AtomicBoolean());
        cancelled.set(false);
        this.queue.add(new Job<>(key, epoch, revision,
            priority == null ? Priority.BACKGROUND : priority, input, this.sequence.getAndIncrement()));
    }

    /** Cancels queued and in-flight jobs for a key; no result is required. */
    public void cancel(long key) {
        AtomicBoolean flag = this.cancellations.computeIfAbsent(key, ignored -> new AtomicBoolean());
        flag.set(true);
        this.queue.removeIf(job -> job.key() == key);
    }

    public Result<R> poll() {
        return this.results.poll();
    }

    public void clear() {
        this.queue.clear();
        this.results.clear();
        this.cancellations.clear();
    }

    private void run() {
        while (this.running) {
            Job<J> job = null;
            try {
                job = this.queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (job == null) {
                    continue;
                }
                AtomicBoolean cancelled = this.cancellations.computeIfAbsent(job.key(),
                    ignored -> new AtomicBoolean());
                CancellationToken token = new CancellationToken(cancelled);
                if (token.isCancelled()) {
                    this.results.add(new Result<>(job.key(), job.epoch(), job.revision(),
                        Status.CANCELLED, null, null));
                    continue;
                }
                R output = this.builder.build(job.input(), token);
                this.results.add(new Result<>(job.key(), job.epoch(), job.revision(),
                    token.isCancelled() ? Status.CANCELLED : Status.SUCCESS, output, null));
            } catch (Throwable failure) {
                if (job != null) {
                    Status status = failure instanceof VirtualMachineError || failure instanceof LinkageError
                        ? Status.FAILED_FATAL : Status.FAILED_RETRYABLE;
                    this.results.add(new Result<>(job.key(), job.epoch(), job.revision(),
                        status, null, failure));
                }
            }
        }
    }

    @Override
    public void close() {
        this.running = false;
        this.clear();
        for (Thread worker : this.workers) {
            worker.interrupt();
        }
    }
}
