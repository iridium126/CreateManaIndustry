package com.iridium126.createmanaindustry.compat.ysm;

import com.iridium126.createmanaindustry.compat.ysm.model.YsmGeometry.Group;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmModelSnapshot;
import com.iridium126.createmanaindustry.compat.ysm.model.YsmResourceArchive;
import java.util.*;
import java.util.concurrent.*;

/** Prepares a complete replacement off the game thread; queries never wait. */
public final class YsmPreparedCache implements AutoCloseable {
    public enum State { LOADING, READY, FAILED }
    public record Result(State state, YsmResourceArchive archive, String reason) {}
    private record Key(String source, List<Group> roots) { Key { roots = List.copyOf(roots); } }
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), action -> { var thread = new Thread(action, "CMI YSM edit preparation"); thread.setDaemon(true); return thread; });
    private final LinkedHashMap<Key, CompletableFuture<YsmResourceArchive>> entries = new LinkedHashMap<>(8, .75f, true);
    private boolean closed;

    public synchronized Result getOrPrepare(YsmModelSnapshot source, List<Group> roots) {
        if (closed) throw new IllegalStateException("Model editor is closed");
        Key key = new Key(source.digest(), roots);
        var future = entries.get(key);
        if (future == null) {
            if (entries.size() >= 4) {
                var cursor = entries.entrySet().iterator();
                while (cursor.hasNext()) {
                    if (cursor.next().getValue().isDone()) { cursor.remove(); break; }
                }
                if (entries.size() >= 4) return new Result(State.FAILED, null, "Model preparation is busy; retry later");
            }
            future = new CompletableFuture<>();
            entries.put(key, future);
            var submitted = future;
            try {
                worker.execute(() -> {
                    try { submitted.complete(source.apply(key.roots())); }
                    catch (Exception failure) { submitted.completeExceptionally(failure); }
                });
            } catch (RejectedExecutionException busy) {
                entries.remove(key);
                return new Result(State.FAILED, null, "Model preparation is busy; retry later");
            }
        }
        if (!future.isDone()) return new Result(State.LOADING, null, "Model geometry is being prepared; retry later");
        try { return new Result(State.READY, future.join(), ""); }
        catch (CompletionException | CancellationException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            return new Result(State.FAILED, null, String.valueOf(cause.getMessage()));
        }
    }
    @Override public synchronized void close() {
        closed = true; entries.values().forEach(future -> future.cancel(false)); entries.clear(); worker.shutdownNow();
    }
}
