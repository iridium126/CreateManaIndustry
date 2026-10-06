package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import com.iridium126.createmanaindustry.infrastructure.concurrent.CMIThreadFactory;

/** Bounded, server-thread-owned cache. No game objects may be used as keys or compiler inputs. */
public final class CompilationCache implements AutoCloseable {
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    /** Stateless call stubs are shared by descriptor; sites keep independent heat and failure state. */
    private final Map<CallCompiler.Description, SharedUnit> linkedByDescription = new HashMap<>();
    private final Map<CallCompiler.Description, PendingUnit> pendingByDescription = new HashMap<>();
    /** Includes completed worker products as well as linked bytecode. */
    private final AtomicLong budgetBytes = new AtomicLong();
    private final ThreadPoolExecutor worker;
    private final int threshold, maxEntries;
    private final long maxBytes;
    private long residentBytes, calls, hits, submitted, failures, compileNanos, evictions;
    private boolean closed;

    public CompilationCache(int threshold, int maxEntries, long maxBytes) {
        if (threshold < 1 || maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException();
        this.threshold = threshold;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        worker = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxEntries), CMIThreadFactory.daemonFactory("hexjit-compiler"),
                new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }

    public CompiledCall acquire(long site, CallCompiler.Description description, boolean compile) {
        if (closed) return null;
        calls++;
        Entry entry = entries.get(site);
        if (entry == null) {
            while (entries.size() >= maxEntries) evict();
            entry = new Entry();
            entry.description = description;
            entries.put(site, entry);
        } else if (!entry.description.equals(description)) {
            releasePending(entry);
            entry.failed = true;
            failures++;
            return null;
        }
        if (entry.unit != null) { hits++; return entry.unit.code; }
        if (entry.failed) return null;
        if (entry.pending != null && entry.pending.future.isDone()) {
            PendingUnit pending = entry.pending;
            try {
                Product product = pending.future.get();
                if (!product.accounted) {
                    compileNanos += product.nanos;
                    product.accounted = true;
                }
                // Refuse publication rather than evict an entry while its operation is in flight.
                SharedUnit shared = pending.shared;
                if (shared != null) {
                    retain(entry, shared);
                } else if (product.bytes == null) {
                    entry.failed = true;
                    failures++;
                } else {
                    shared = new SharedUnit(CallCompiler.link(product.bytes), product.bytes.length);
                    linkedByDescription.put(description, shared);
                    residentBytes += product.bytes.length;
                    // The pending unit owns one reference until its last waiting site resolves.
                    shared.references = 1;
                    pending.shared = shared;
                    pending.reservedBytes = 0; // Transfer the existing budget reservation.
                    product.bytes = null;
                    retain(entry, shared);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                entry.failed = true;
                failures++;
            } catch (Exception | LinkageError failure) {
                entry.failed = true;
                failures++;
            } finally {
                releasePending(entry);
            }
            if (entry.unit != null) { hits++; return entry.unit.code; }
        }
        if (entry.observations < threshold) entry.observations++;
        if (compile && !entry.failed && entry.pending == null && entry.observations >= threshold) {
            SharedUnit shared = linkedByDescription.get(description);
            if (shared != null) {
                retain(entry, shared);
                hits++;
                return shared.code;
            }
            PendingUnit sharedPending = pendingByDescription.get(description);
            if (sharedPending != null) {
                entry.pending = sharedPending;
                sharedPending.references++;
                return null;
            }
            try {
                PendingUnit pending = new PendingUnit();
                pending.future = worker.submit(() -> {
                    long start = System.nanoTime();
                    byte[] bytes = CallCompiler.compile(description);
                    long nanos = System.nanoTime() - start;
                    synchronized (pending) {
                        if (pending.released || !reserve(bytes.length)) return new Product(null, nanos);
                        pending.reservedBytes = bytes.length;
                        return new Product(bytes, nanos);
                    }
                });
                pending.references = 1;
                entry.pending = pending;
                pendingByDescription.put(description, pending);
                submitted++;
            } catch (RejectedExecutionException full) {
                // Retry on a later execution. Never block the server on compilation.
            }
        }
        return null;
    }

    private void evict() {
        var iterator = entries.values().iterator();
        Entry oldest = iterator.next();
        iterator.remove();
        releasePending(oldest);
        if (oldest.unit != null) releaseShared(oldest.description, oldest.unit);
        worker.purge();
        evictions++;
    }

    private boolean reserve(int bytes) {
        long current;
        do {
            current = budgetBytes.get();
            if (bytes > maxBytes - current) return false;
        } while (!budgetBytes.compareAndSet(current, current + bytes));
        return true;
    }

    private void releasePending(Entry entry) {
        PendingUnit pending = entry.pending;
        if (pending == null) return;
        entry.pending = null;
        if (--pending.references != 0) return;
        pendingByDescription.remove(entry.description, pending);
        synchronized (pending) {
            // A cancelled running Future may still finish: prevent a late reservation.
            pending.released = true;
            budgetBytes.addAndGet(-pending.reservedBytes);
            pending.reservedBytes = 0;
        }
        pending.future.cancel(false);
        if (pending.shared != null) releaseShared(entry.description, pending.shared);
    }

    private void releaseShared(CallCompiler.Description description, SharedUnit unit) {
        if (--unit.references != 0) return;
        linkedByDescription.remove(description, unit);
        residentBytes -= unit.bytes;
        budgetBytes.addAndGet(-unit.bytes);
    }

    private static void retain(Entry entry, SharedUnit unit) {
        entry.unit = unit;
        unit.references++;
    }

    public Stats stats() {
        return new Stats(calls, hits, submitted, failures, compileNanos, entries.size(), budgetBytes.get(), evictions,
                pendingByDescription.size(), budgetBytes.get() - residentBytes);
    }

    @Override public void close() {
        closed = true;
        for (Entry entry : entries.values()) {
            releasePending(entry);
            if (entry.unit != null) releaseShared(entry.description, entry.unit);
        }
        entries.clear();
        linkedByDescription.clear();
        pendingByDescription.clear();
        residentBytes = 0;
        worker.shutdownNow();
    }

    public record Stats(long calls, long compiledHits, long submitted, long failures, long compileNanos,
                        int entries, long bytecodeBytes, long evictions, int pendingDescriptions, long pendingBytes) {}
    private static final class Product {
        byte[] bytes;
        final long nanos;
        boolean accounted;

        private Product(byte[] bytes, long nanos) {
            this.bytes = bytes;
            this.nanos = nanos;
        }
    }
    private static final class Entry {
        int observations;
        boolean failed;
        CallCompiler.Description description;
        SharedUnit unit;
        PendingUnit pending;
    }
    private static final class PendingUnit {
        Future<Product> future;
        int references;
        boolean released;
        int reservedBytes;
        SharedUnit shared;
    }
    private static final class SharedUnit {
        final CompiledCall code;
        final int bytes;
        int references;

        private SharedUnit(CompiledCall code, int bytes) {
            this.code = code;
            this.bytes = bytes;
        }
    }
}
