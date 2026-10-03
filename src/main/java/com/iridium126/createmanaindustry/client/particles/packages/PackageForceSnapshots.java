package com.iridium126.createmanaindustry.client.particles.packages;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Immutable one-second history with at most four queued/running worker jobs. */
final class PackageForceSnapshots {
    private record Job(long tick,List<PackageForceScene.Source> sources,double x,double y,double z,CompletableFuture<PackageForceScene.Snapshot> result) {}
    private record Input(long tick,Job job) {}
    private final NavigableMap<Long,Input> history=new TreeMap<>();
    private final AtomicInteger workers=new AtomicInteger();
    private final Set<Job> scheduled=Collections.newSetFromMap(new IdentityHashMap<>());
    private Executor worker;
    private long admissionTick=Long.MIN_VALUE;
    private int historyTicks=PackageSimulationClock.HISTORY_TICKS;
    void tickRate(double rate){historyTicks=Math.max(historyTicks,com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageTickTiming.historyTicks(rate));}
    boolean needsCapture(long tick){schedule();return !history.containsKey(tick);}
    void capture(long tick,List<PackageForceScene.Source> sources,double x,double y,double z,Executor worker){
        captureRange(tick,tick,sources,x,y,z,worker);
    }
    void captureRange(long first,long last,List<PackageForceScene.Source> sources,double x,double y,double z,Executor worker){
        if(first<0||last<first||last-first>=historyTicks)throw new IllegalArgumentException("Force input range");
        if(!needsCapture(last))return;this.worker=worker;
        var job=new Job(first,List.copyOf(sources),x,y,z,new CompletableFuture<>());
        for(long tick=first;tick<=last;tick++)history.putIfAbsent(tick,new Input(tick,job));
        while(history.size()>historyTicks){var old=history.pollFirstEntry().getValue().job;
            if(history.values().stream().noneMatch(input->input.job==old)){old.result.cancel(false);scheduled.remove(old);}}
        schedule();
    }
    private void schedule(){
        if(worker==null)return;
        for(var row:history.values()){
            var input=row.job;
            if(workers.get()>=4)break;if(input.result.isDone()||!scheduled.add(input))continue;
            workers.incrementAndGet();
            try{worker.execute(()->{try{if(!input.result.isCancelled())input.result.complete(PackageForceScene.bake(input.tick,input.sources,input.x,input.y,input.z));}
                catch(Throwable error){input.result.completeExceptionally(error);}finally{workers.decrementAndGet();}});}
            catch(RejectedExecutionException rejected){workers.decrementAndGet();scheduled.remove(input);break;}
        }
    }
    boolean ready(long tick){schedule();var row=history.get(tick);if(row==null||!row.job.result.isDone()||row.job.result.isCancelled())return false;
        row.job.result.join();return true;}
    PackageForceScene.Snapshot snapshot(long tick){if(!ready(tick))throw new IllegalStateException("Package force history unavailable at tick "+tick);
        var baked=history.get(tick).job.result.join();return new PackageForceScene.Snapshot(tick,baked.nodes(),baked.sources(),baked.frames(),baked.data());}
    Object identity(long tick){return history.get(tick).job;}
    boolean contains(long tick){return history.containsKey(tick);}
    void consumed(long tick){
        // Keep the newest capture for admission. Rendering again before the next client
        // tick must not recapture mutable sources under an already consumed identity.
        if(!history.isEmpty())history.headMap(Math.min(tick,history.lastKey()-1),true).clear();
        scheduled.removeIf(job->history.values().stream().noneMatch(input->input.job==job));schedule();
    }
    /** Existing bodies still need immutable, unconsumed inputs. A new interest region waits
     * for the next capture instead of deleting or rewriting the old population's history. */
    void changedRegions(long tick){admissionTick=history.containsKey(tick)?Math.incrementExact(tick):tick;}
    boolean admissionReady(long tick){return tick>=admissionTick&&ready(tick);}
    void clear(){history.values().forEach(input->input.job.result.cancel(false));history.clear();scheduled.clear();}
}
