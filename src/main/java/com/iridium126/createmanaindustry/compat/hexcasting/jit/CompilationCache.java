package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import com.iridium126.createmanaindustry.util.concurrent.CMIThreadFactory;

/** Bounded, server-thread-owned cache. No game objects may be used as keys or compiler inputs. */
public final class CompilationCache implements AutoCloseable {
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    /** Stateless call stubs are shared by descriptor; sites keep independent heat and failure state. */
    private final Map<CallCompiler.Description, SharedUnit> linkedByDescription = new HashMap<>();
    private final Map<CallCompiler.Description, Future<Product>> pendingByDescription = new HashMap<>();
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
            entry.failed = true;
            failures++;
            return null;
        }
        if (entry.unit != null) { hits++; return entry.unit.code; }
        if (entry.failed) return null;
        if (entry.pending != null && entry.pending.isDone()) {
            Future<Product> pending = entry.pending;
            try {
                Product product = pending.get();
                if (!product.accounted) {
                    compileNanos += product.nanos;
                    product.accounted = true;
                }
                // Refuse publication rather than evict an entry while its operation is in flight.
                SharedUnit shared = linkedByDescription.get(description);
                if (shared != null) {
                    retain(entry, shared);
                } else if (residentBytes + product.bytes.length > maxBytes) {
                    entry.failed = true;
                    failures++;
                } else {
                    shared = new SharedUnit(CallCompiler.link(product.bytes), product.bytes.length);
                    linkedByDescription.put(description, shared);
                    residentBytes += product.bytes.length;
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
                pendingByDescription.remove(description, pending);
                entry.pending = null;
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
            Future<Product> sharedPending = pendingByDescription.get(description);
            if (sharedPending != null) {
                entry.pending = sharedPending;
                return null;
            }
            try {
                Future<Product> pending = worker.submit(() -> {
                    long start = System.nanoTime();
                    return new Product(CallCompiler.compile(description), System.nanoTime() - start);
                });
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
        if (oldest.unit != null && --oldest.unit.references == 0) {
            linkedByDescription.remove(oldest.description, oldest.unit);
            residentBytes -= oldest.unit.bytes;
        }
        worker.purge();
        evictions++;
    }

    private static void retain(Entry entry, SharedUnit unit) {
        entry.unit = unit;
        unit.references++;
    }

    public Stats stats() {
        return new Stats(calls, hits, submitted, failures, compileNanos, entries.size(), residentBytes, evictions);
    }

    @Override public void close() {
        closed = true;
        for (Future<Product> pending : pendingByDescription.values()) pending.cancel(false);
        entries.clear();
        linkedByDescription.clear();
        pendingByDescription.clear();
        residentBytes = 0;
        worker.shutdownNow();
    }

    public record Stats(long calls, long compiledHits, long submitted, long failures, long compileNanos,
                        int entries, long bytecodeBytes, long evictions) {}
    private static final class Product {
        final byte[] bytes;
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
        Future<Product> pending;
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
