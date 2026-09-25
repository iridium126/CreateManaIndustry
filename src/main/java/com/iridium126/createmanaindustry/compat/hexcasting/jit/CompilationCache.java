package com.iridium126.createmanaindustry.compat.hexcasting.jit;

import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

/** Bounded, server-thread-owned cache. No game objects may be used as keys or compiler inputs. */
public final class CompilationCache implements AutoCloseable {
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, .75f, true);
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
                new ArrayBlockingQueue<>(maxEntries), task -> {
                    Thread thread = new Thread(task, "CMI Hex JIT compiler");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }

    public CompiledCall acquire(long site, CallCompiler.Description description, boolean compile) {
        if (closed) return null;
        calls++;
        Entry entry = entries.get(site);
        if (entry == null) {
            while (entries.size() >= maxEntries) evict();
            entry = new Entry();
            entries.put(site, entry);
        }
        if (entry.code != null) { hits++; return entry.code; }
        if (entry.failed) return null;
        if (entry.pending != null && entry.pending.isDone()) {
            try {
                Product product = entry.pending.get();
                compileNanos += product.nanos;
                // Refuse publication rather than evict an entry while its operation is in flight.
                if (residentBytes + product.bytes.length > maxBytes) {
                    entry.failed = true;
                    failures++;
                } else {
                    entry.code = CallCompiler.link(product.bytes);
                    entry.bytes = product.bytes.length;
                    residentBytes += entry.bytes;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                entry.failed = true;
                failures++;
            } catch (Exception | LinkageError failure) {
                entry.failed = true;
                failures++;
            } finally {
                entry.pending = null;
            }
            if (entry.code != null) { hits++; return entry.code; }
        }
        if (entry.observations < threshold) entry.observations++;
        if (compile && !entry.failed && entry.pending == null && entry.observations >= threshold) {
            try {
                entry.pending = worker.submit(() -> {
                    long start = System.nanoTime();
                    return new Product(CallCompiler.compile(description), System.nanoTime() - start);
                });
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
        if (oldest.pending != null) oldest.pending.cancel(false);
        residentBytes -= oldest.bytes;
        worker.purge();
        evictions++;
    }

    public Stats stats() {
        return new Stats(calls, hits, submitted, failures, compileNanos, entries.size(), residentBytes, evictions);
    }

    @Override public void close() {
        closed = true;
        for (Entry entry : entries.values()) if (entry.pending != null) entry.pending.cancel(false);
        entries.clear();
        residentBytes = 0;
        worker.shutdownNow();
    }

    public record Stats(long calls, long compiledHits, long submitted, long failures, long compileNanos,
                        int entries, long bytecodeBytes, long evictions) {}
    private record Product(byte[] bytes, long nanos) {}
    private static final class Entry {
        int observations, bytes;
        boolean failed;
        Future<Product> pending;
        CompiledCall code;
    }
}
