package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Owner-thread publication of a single immutable BVH job. Polls never wait for the worker. */
final class PackageForceSnapshots {
    private CompletableFuture<PackageForceScene.Snapshot> pending;
    private PackageForceScene.Snapshot snapshot;
    private long revision,pendingRevision,scheduled=Long.MIN_VALUE;

    private void poll() {
        if(pending==null || !pending.isDone())return;
        var completed=pending;pending=null;
        var result=completed.join();
        if(pendingRevision==revision)snapshot=result;
    }
    boolean needsCapture(long tick) {poll();return pending==null && scheduled!=tick;}
    void capture(long tick,List<PackageForceScene.Source> sources,double ox,double oy,double oz,Executor worker) {
        if(!needsCapture(tick))return;
        var immutable=List.copyOf(sources);
        pending=CompletableFuture.supplyAsync(()->PackageForceScene.bake(tick,immutable,ox,oy,oz),worker);
        pendingRevision=revision;scheduled=tick;
    }
    boolean ready(long tick) {
        poll();return snapshot!=null && tick>=snapshot.tick() && tick-snapshot.tick()<=1;
    }
    PackageForceScene.Snapshot snapshot(long tick) {
        if(!ready(tick))throw new IllegalStateException("Package force capture is not ready for this tick");
        return snapshot;
    }
    void invalidate() {revision++;snapshot=null;scheduled=Long.MIN_VALUE;}
    void clear() {
        invalidate();if(pending!=null)pending.cancel(false);pending=null;
    }
}
