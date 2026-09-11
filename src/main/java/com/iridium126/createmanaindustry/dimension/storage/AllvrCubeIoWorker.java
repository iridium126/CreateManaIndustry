package com.iridium126.createmanaindustry.dimension.storage;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * The cube I/O worker (plan §7.1/§8, the {@code AsyncBatchingCubeIO}
 * analogue): owns an explicit OPEN → CLOSING → CLOSED state, a latest-wins
 * pending map, and a single ordered mutation mailbox. Region3D opts into a
 * bounded foreground read pool; writes and header mutations remain ordered.
 * <p>
 * Invariants (plan §8):
 * <ul>
 *   <li>every cube key's pending entry is always the newest queued version —
 *       superseded futures complete immediately because the newer record will
 *       make the data durable;</li>
 *   <li>after a batch write, only the exact written pending values are
 *       removed — a snapshot enqueued mid-write can never be deleted by the
 *       older batch (§8.4);</li>
 *   <li>reads check the pending map first, so a save-then-reload never reads
 *       stale disk data (§8.5);</li>
 *   <li>failed batches keep their pending entries and are retried on the next
 *       drain; {@link #flush}/{@link #close} retry up to a cap and then throw
 *       an aggregate failure instead of pretending success (§8);</li>
 *   <li>after {@link #close()} returns, no further reads or writes are
 *       accepted (§8.9).</li>
 * </ul>
 */
public final class AllvrCubeIoWorker implements AutoCloseable {

    /**
     * Own logger on purpose: the worker must be constructible (and drainable)
     * outside the mod bootstrap — the persistence unit tests run it against a
     * fake storage without the {@code CreateManaIndustry} main class.
     */
    public static final Logger LOGGER = LoggerFactory.getLogger("createmanaindustry/AllvrCubeIoWorker");

    private static final int MAX_FLUSH_RETRIES = 5;
    private static final long IO_ERROR_LOG_INTERVAL_MS = 10_000;
    /** Small coalescing window; mirrors vanilla's low-priority background saves. */
    private static final long BATCH_DELAY_MS = 2L;
    /** Bound foreground reads so a fast player cannot grow an unbounded queue. */
    private static final int MAX_PENDING_READS = 512;
    /** Parallel region reads; the storage implementation still serializes mutations. */
    private static final int READ_WORKERS =
        Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    private enum State {
        OPEN, CLOSING, CLOSED
    }

    private final AllvrCubeStorage storage;
    private final AllvrStorageDiagnostics diagnostics;
    private final ScheduledExecutorService executor;
    private final ThreadPoolExecutor readExecutor;
    /** Ensures a burst of enqueue calls creates one delayed drain task. */
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    /**
     * Foreground reads waiting in the mailbox.  Vanilla's IOWorker gives
     * reads foreground priority over background stores; the counter lets the
     * delayed save drain yield before it starts a batch when a player is
     * already waiting for persisted cubes.
     */
    private final AtomicInteger queuedReads = new AtomicInteger();
    /** Increments after each successful commit so a concurrent read can retry a stale snapshot. */
    private final AtomicLong writeEpoch = new AtomicLong();
    /**
     * Cube key → newest pending write. {@code PendingWrite} instances are
     * immutable, so a batch's {@code remove(key, exactValue)} can never delete
     * a newer entry (identity is the version guard of §8.4).
     */
    private final ConcurrentHashMap<Long, PendingWrite> pending = new ConcurrentHashMap<>();
    private volatile State state = State.OPEN;
    private volatile long lastIoLogMs;
    private volatile IOException lastIoException;
    private final Object closeLock = new Object();

    private static final class PendingWrite {
        final AllvrCubePos pos;
        final long version;
        final long lightVersion;
        final CompoundTag tag;
        final CompletableFuture<Void> durable = new CompletableFuture<>();

        PendingWrite(AllvrCubePos pos, long version, long lightVersion, CompoundTag tag) {
            this.pos = pos;
            this.version = version;
            this.lightVersion = lightVersion;
            this.tag = tag;
        }
    }

    public AllvrCubeIoWorker(AllvrCubeStorage storage, AllvrStorageDiagnostics diagnostics) {
        this.storage = storage;
        this.diagnostics = diagnostics;
        AtomicInteger index = new AtomicInteger();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CMI-AllvrCubeIo-" + index.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        int workers = storage.supportsConcurrentReads() ? READ_WORKERS : 1;
        AtomicInteger readIndex = new AtomicInteger();
        this.readExecutor = new ThreadPoolExecutor(
            workers, workers, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_READS),
            r -> {
                Thread t = new Thread(r, "CMI-AllvrCubeRead-" + readIndex.incrementAndGet());
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    }

    // ------------------------------------------------------------------
    // writes
    // ------------------------------------------------------------------

    /**
     * Takes ownership of a snapshot (service thread). Returns immediately;
     * durability arrives via {@link #flush()} or the background drain.
     */
    public void enqueue(AllvrCubeSnapshot snapshot) {
        this.ensureOpen("enqueue");
        PendingWrite write = new PendingWrite(snapshot.pos(), snapshot.version(), snapshot.lightVersion(), snapshot.tag());
        PendingWrite previous = this.pending.put(snapshot.pos().asLong(), write);
        if (previous != null) {
            // superseded before it reached disk — the newer record carries the
            // data, so waiting on the old future would only stall the caller
            previous.durable.complete(null);
        }
        this.scheduleDrain();
    }

    private void scheduleDrain() {
        if (!this.drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            this.executor.schedule(this::drainOnce, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            this.drainScheduled.set(false);
            // racing close(); the cube map is shutting down anyway
            LOGGER.debug("[Allvr] drain scheduled after worker close — dropped", e);
        }
    }

    /** One batched write pass; failures keep the pending entries for retry. */
    private void drainOnce() {
        this.drainScheduled.set(false);
        if (this.pending.isEmpty()) return;
        if (this.queuedReads.get() > 0) {
            // Keep the region-file mailbox responsive to loadAsync callers.
            // The delayed retry is still a background task, so a continuous
            // read burst is drained first without allowing writes to run on
            // the server thread.
            this.scheduleDrain();
            return;
        }
        try {
            this.drainBatch();
        } catch (Exception e) {
            this.noteIoError(e instanceof IOException io ? io : new IOException(e));
        } finally {
            // New snapshots may have arrived while the batch was writing.
            // Schedule exactly one follow-up batch instead of one executor
            // task per enqueue call.
            if (!this.pending.isEmpty() && this.state == State.OPEN) {
                this.scheduleDrain();
            }
        }
    }

    private void drainBatch() throws IOException {
        if (this.pending.isEmpty()) {
            return;
        }
        Map<AllvrCubePos, CompoundTag> batch = new java.util.HashMap<>(this.pending.size());
        for (PendingWrite write : this.pending.values()) {
            batch.put(write.pos, write.tag);
        }
        long start = System.nanoTime();
        this.storage.writeBatch(batch);
        long elapsed = System.nanoTime() - start;
        this.writeEpoch.incrementAndGet();
        this.diagnostics.regionCommits.incrementAndGet();
        this.diagnostics.recordsWritten.addAndGet(batch.size());
        this.diagnostics.commitNanos.addAndGet(elapsed);
        this.diagnostics.maxCommitNanos.accumulateAndGet(elapsed, Math::max);
        if (batch.size() == 1) {
            this.diagnostics.singleRecordCommits.incrementAndGet();
        }
        long bytes = this.storage.bytesWritten();
        if (bytes >= 0) {
            this.diagnostics.payloadBytesWritten.set(bytes);
        }
        // conditional removal — identity of the exact written snapshot (§8.4)
        for (Map.Entry<AllvrCubePos, CompoundTag> entry : batch.entrySet()) {
            PendingWrite write = this.pending.get(entry.getKey().asLong());
            if (write != null && write.tag == entry.getValue()) {
                this.pending.remove(entry.getKey().asLong(), write);
                write.durable.complete(null);
            }
        }
        // Do not log every region commit.  Autosave traffic can contain
        // thousands of small batches; formatting one DEBUG line per batch
        // runs on the single I/O worker and can become a second persistence
        // bottleneck. Diagnostics already retain commit count and latency.
    }

    // ------------------------------------------------------------------
    // reads
    // ------------------------------------------------------------------

    /**
     * Reads the newest record for a cube — the pending map first, then disk
     * (read-your-writes, §8.5). May complete on the worker thread; the
     * payload is immutable so any thread may decode it.
     */
    public CompletableFuture<Optional<CompoundTag>> load(AllvrCubePos pos) {
        this.ensureOpen("load");
        CompletableFuture<Optional<CompoundTag>> future = new CompletableFuture<>();
        this.queuedReads.incrementAndGet();
        try {
            Runnable readTask = () -> this.readOne(pos, future);
            if (this.storage.supportsConcurrentReads()) {
                // Region3D protects its handles with a read/write lock, so
                // independent regions can read and inflate in parallel.
                this.readExecutor.execute(readTask);
            } else {
                // Keep custom/test storages on the original single mailbox
                // path unless they explicitly opt into concurrent reads.
                this.executor.execute(readTask);
            }
        } catch (RejectedExecutionException e) {
            this.queuedReads.decrementAndGet();
            future.completeExceptionally(new IOException("allvr io worker closed", e));
        }
        return future;
    }

    private void readOne(AllvrCubePos pos, CompletableFuture<Optional<CompoundTag>> future) {
        long key = pos.asLong();
        try {
            Optional<CompoundTag> result = Optional.empty();
            // A write can overlap a disk read. The pending map handles writes
            // not yet committed; the epoch catches a commit that completed
            // after the read began, forcing a fresh read of the new header.
            for (int attempt = 0; attempt < 8; attempt++) {
                PendingWrite write = this.pending.get(key);
                if (write != null) {
                    future.complete(Optional.of(write.tag));
                    return;
                }
                long epoch = this.writeEpoch.get();
                result = this.readStorage(pos);
                write = this.pending.get(key);
                if (write != null) {
                    future.complete(Optional.of(write.tag));
                    return;
                }
                if (this.writeEpoch.get() == epoch) {
                    future.complete(result);
                    return;
                }
            }
            // A sustained write storm is unusual; return the newest pending
            // value if one exists, otherwise complete with the last stable
            // storage result rather than spinning forever.
            PendingWrite write = this.pending.get(key);
            future.complete(write != null ? Optional.of(write.tag) : result);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        } finally {
            this.queuedReads.decrementAndGet();
        }
    }

    private Optional<CompoundTag> readStorage(AllvrCubePos pos) throws IOException {
        long start = System.nanoTime();
        try {
            return this.storage.read(pos);
        } finally {
            this.diagnostics.persistedStorageReadCount.incrementAndGet();
            this.diagnostics.persistedStorageReadNanos.addAndGet(System.nanoTime() - start);
        }
    }

    /** Blocking one-shot read used by the synchronous load path (plan §7.3). */
    public Optional<CompoundTag> loadBlocking(AllvrCubePos pos) throws IOException {
        try {
            return this.load(pos).join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("failed to read cube " + pos, cause);
        }
    }

    /**
     * Union of the pending map and the persisted header index — the
     * cross-session "has a record" predicate source (plan §7.3). Blocking;
     * intended for the cube-map constructor before any concurrent task runs.
     */
    public Set<Long> persistedKeysSnapshot() throws IOException {
        LongOpenHashSet keys = new LongOpenHashSet(this.pending.keySet());
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            this.executor.execute(() -> {
                try {
                    this.storage.forEachCube(pos -> keys.add(pos.asLong()));
                    done.complete(null);
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new IOException("allvr io worker closed", e);
        }
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while enumerating persisted cubes", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("failed to enumerate persisted cubes", cause);
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // flush / close
    // ------------------------------------------------------------------

    /**
     * Blocks until the pending map is empty and every region file has been
     * forced — the durable point of {@code /save-all flush} and normal
     * shutdown (§8.9). Throws after the retry cap instead of pretending
     * success.
     */
    public void flush() throws IOException {
        this.ensureOpen("flush");
        long start = System.nanoTime();
        this.runBarrier(() -> {
            this.drainUntilEmpty();
            this.storage.flush();
        });
        this.diagnostics.lastFlushDurationMs = (System.nanoTime() - start) / 1_000_000L;
    }

    private void drainUntilEmpty() throws IOException {
        for (int attempt = 0; attempt < MAX_FLUSH_RETRIES && !this.pending.isEmpty(); attempt++) {
            try {
                this.drainBatch();
            } catch (Exception e) {
                this.noteIoError(e instanceof IOException io ? io : new IOException(e));
            }
        }
        if (!this.pending.isEmpty()) {
            IOException failure = new IOException("[Allvr] " + this.pending.size()
                + " cube record(s) still pending after " + MAX_FLUSH_RETRIES + " drain attempts — last failure",
                this.lastIoException);
            this.diagnostics.noteIoError(failure.toString());
            throw failure;
        }
    }

    private void runBarrier(ThrowingTask task) throws IOException {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            this.executor.execute(() -> {
                try {
                    task.run();
                    done.complete(null);
                } catch (Throwable t) {
                    done.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            throw new IOException("allvr io worker closed", e);
        }
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while flushing allvr cubes", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("allvr cube flush failed", cause);
        }
    }

    @FunctionalInterface
    private interface ThrowingTask {
        void run() throws Exception;
    }

    /**
     * Idempotent close (§8.9): drains, forces, closes the region handles and
     * shuts the thread down. Rejected submissions afterwards.
     */
    @Override
    public void close() {
        synchronized (this.closeLock) {
            if (this.state != State.OPEN) {
                return;
            }
            this.state = State.CLOSING;
        }
        // Stop and drain foreground reads before closing region handles. The
        // storage write lock also protects against an active read, but queued
        // read tasks must not start after close() has closed their channel.
        this.readExecutor.shutdown();
        try {
            if (!this.readExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                this.readExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.readExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            this.executor.execute(() -> {
                try {
                    this.drainUntilEmpty();
                } catch (Throwable t) {
                    LOGGER.error("[Allvr] final cube drain failed — data may be missing from disk", t);
                    this.diagnostics.noteIoError(t.toString());
                } finally {
                    try {
                        this.storage.close();
                    } catch (Throwable t) {
                        LOGGER.error("[Allvr] closing region3d storage failed", t);
                    }
                    done.complete(null);
                }
            });
            done.get(60, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            LOGGER.error("[Allvr] allvr io worker rejected its own close task", e);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            LOGGER.error("[Allvr] waiting for allvr io worker close failed", e);
        }
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(30, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            this.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        this.state = State.CLOSED;
    }

    public int pendingCount() {
        return this.pending.size();
    }

    public AllvrStorageDiagnostics diagnostics() {
        return this.diagnostics;
    }

    private void ensureOpen(String what) {
        if (this.state != State.OPEN) {
            throw new IllegalStateException("allvr io worker is " + this.state + "; cannot " + what);
        }
    }

    private void noteIoError(IOException e) {
        this.lastIoException = e;
        this.diagnostics.noteIoError(e.toString());
        long now = System.currentTimeMillis();
        if (now - this.lastIoLogMs > IO_ERROR_LOG_INTERVAL_MS) {
            this.lastIoLogMs = now;
            LOGGER.error("[Allvr] region3d write failed — {} pending record(s) kept for retry", this.pending.size(), e);
        }
    }
}
