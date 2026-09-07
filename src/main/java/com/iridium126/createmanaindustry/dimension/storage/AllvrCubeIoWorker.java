package com.iridium126.createmanaindustry.dimension.storage;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;

import com.iridium126.createmanaindustry.dimension.cube.AllvrCubePos;

/**
 * The single-threaded cube I/O worker (plan §7.1/§8, the
 * {@code AsyncBatchingCubeIO} analogue): owns an explicit OPEN → CLOSING →
 * CLOSED state, a latest-wins pending map, and the only thread that touches
 * region files.
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

    private enum State {
        OPEN, CLOSING, CLOSED
    }

    private final AllvrCubeStorage storage;
    private final AllvrStorageDiagnostics diagnostics;
    private final ExecutorService executor;
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
        final CompoundTag tag;
        final CompletableFuture<Void> durable = new CompletableFuture<>();

        PendingWrite(AllvrCubePos pos, long version, CompoundTag tag) {
            this.pos = pos;
            this.version = version;
            this.tag = tag;
        }
    }

    public AllvrCubeIoWorker(AllvrCubeStorage storage, AllvrStorageDiagnostics diagnostics) {
        this.storage = storage;
        this.diagnostics = diagnostics;
        AtomicInteger index = new AtomicInteger();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CMI-AllvrCubeIo-" + index.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
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
        PendingWrite write = new PendingWrite(snapshot.pos(), snapshot.version(), snapshot.tag());
        PendingWrite previous = this.pending.put(snapshot.pos().asLong(), write);
        if (previous != null) {
            // superseded before it reached disk — the newer record carries the
            // data, so waiting on the old future would only stall the caller
            previous.durable.complete(null);
        }
        this.scheduleDrain();
    }

    private void scheduleDrain() {
        try {
            this.executor.execute(this::drainOnce);
        } catch (RejectedExecutionException e) {
            // racing close(); the cube map is shutting down anyway
            LOGGER.debug("[Allvr] drain scheduled after worker close — dropped", e);
        }
    }

    /** One batched write pass; failures keep the pending entries for retry. */
    private void drainOnce() {
        if (this.pending.isEmpty()) {
            return;
        }
        try {
            this.drainBatch();
        } catch (Exception e) {
            this.noteIoError(e instanceof IOException io ? io : new IOException(e));
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
        this.diagnostics.regionCommits.incrementAndGet();
        this.diagnostics.recordsWritten.addAndGet(batch.size());
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
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[Allvr] wrote {} cube record(s) in {} µs", batch.size(),
                (System.nanoTime() - start) / 1000);
        }
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
        PendingWrite write = this.pending.get(pos.asLong());
        if (write != null) {
            return CompletableFuture.completedFuture(Optional.of(write.tag));
        }
        this.ensureOpen("load");
        CompletableFuture<Optional<CompoundTag>> future = new CompletableFuture<>();
        try {
            this.executor.execute(() -> {
                try {
                    future.complete(this.storage.read(pos));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IOException("allvr io worker closed", e));
        }
        return future;
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
