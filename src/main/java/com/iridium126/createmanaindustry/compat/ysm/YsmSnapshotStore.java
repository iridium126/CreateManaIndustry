package com.iridium126.createmanaindustry.compat.ysm;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Session-owned, bounded async prewarming. No file parsing occurs in the query methods. */
public final class YsmSnapshotStore implements AutoCloseable {
    public enum Status { MISSING, LOADING, READY, FAILED }
    public record Result(Status status, YsmModelSnapshot snapshot, String reason) {}
    private static final long MAX_WEIGHT = 256L * 1024 * 1024;
    private static final int MAX_SNAPSHOTS = 32;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), action -> { var thread = new Thread(action, "CMI YSM resource loader"); thread.setDaemon(true); return thread; });
    private final Map<String, CompletableFuture<String>> requests = new HashMap<>();
    private final LinkedHashMap<String, YsmModelSnapshot> snapshots = new LinkedHashMap<>(16, .75f, true);
    private long weight;
    private boolean closed;

    /** Caller obtains trusted source paths from the server model catalog, never from client input. */
    public synchronized void prewarm(String modelId, Path source) {
        prewarm(modelId, List.of(source));
    }
    public synchronized void prewarm(String modelId, List<Path> candidates) {
        var sourceCandidates = List.copyOf(candidates);
        if (closed) throw new IllegalStateException("Model snapshot store is closed");
        if (requests.containsKey(modelId)) return;
        if (requests.size() >= 256) {
            requests.entrySet().removeIf(entry -> entry.getValue().isDone()
                    && (entry.getValue().isCompletedExceptionally() || !snapshots.containsKey(entry.getValue().getNow(null))));
            if (requests.size() >= 256) throw new IllegalStateException("Too many pending model snapshots");
        }
        var result = new CompletableFuture<String>();
        requests.put(modelId, result);
        try {
            worker.execute(() -> {
                try {
                    var matches = sourceCandidates.stream().filter(java.nio.file.Files::exists).toList();
                    if (matches.isEmpty()) throw new java.nio.file.NoSuchFileException("YSM model resources are not ready");
                    if (matches.size() != 1) throw new IllegalArgumentException("Model source is ambiguous");
                    if (java.nio.file.Files.isSymbolicLink(matches.getFirst())) throw new IllegalArgumentException("Linked model source is not allowed");
                    var snapshot = YsmModelSnapshot.load(matches.getFirst());
                    synchronized (this) {
                        if (closed || requests.get(modelId) != result) { result.cancel(false); return; }
                        if (snapshot.weight() > MAX_WEIGHT) throw new IllegalArgumentException("Decoded model exceeds cache limit");
                        if (!snapshots.containsKey(snapshot.digest())) {
                            while (!snapshots.isEmpty() && (snapshots.size() >= MAX_SNAPSHOTS || weight + snapshot.weight() > MAX_WEIGHT)) {
                                var eldest = snapshots.entrySet().iterator();
                                weight -= eldest.next().getValue().weight(); eldest.remove();
                            }
                            snapshots.put(snapshot.digest(), snapshot); weight += snapshot.weight();
                        }
                        result.complete(snapshot.digest());
                    }
                } catch (Exception failure) { result.completeExceptionally(failure); }
            });
        } catch (RejectedExecutionException busy) {
            requests.remove(modelId);
            throw new IllegalStateException("Model resource loader is busy; retry later", busy);
        }
    }
    public synchronized Result query(String modelId) {
        var future = requests.get(modelId);
        if (future == null) return new Result(Status.MISSING, null, "Model snapshot has not been requested");
        if (!future.isDone()) return new Result(Status.LOADING, null, "Model resources are still loading; retry later");
        try {
            var snapshot = snapshots.get(future.join());
            if (snapshot == null) { requests.remove(modelId); return new Result(Status.MISSING, null, "Source snapshot was evicted; read the model again"); }
            return new Result(Status.READY, snapshot, "");
        } catch (CompletionException | CancellationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof java.nio.file.NoSuchFileException) {
                requests.remove(modelId);
                return new Result(Status.LOADING, null, "Model resources are still syncing; retry later");
            }
            return new Result(Status.FAILED, null, String.valueOf(cause.getMessage()));
        }
    }
    public synchronized YsmModelSnapshot require(String digest) {
        var snapshot = snapshots.get(digest);
        if (snapshot == null) throw new IllegalArgumentException("Source snapshot is missing; read the model again");
        return snapshot;
    }
    public synchronized Optional<String> modelIdForDigest(String digest) {
        for (var entry : requests.entrySet()) {
            var future = entry.getValue();
            if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()
                    && digest.equals(future.getNow(null)) && snapshots.containsKey(digest))
                return Optional.of(entry.getKey());
        }
        return Optional.empty();
    }
    public synchronized void invalidate(String modelId) {
        var old = requests.remove(modelId); if (old != null) old.cancel(false);
    }
    @Override public synchronized void close() {
        closed = true; requests.values().forEach(future -> future.cancel(false)); requests.clear();
        snapshots.clear(); weight = 0; worker.shutdownNow();
    }
}
