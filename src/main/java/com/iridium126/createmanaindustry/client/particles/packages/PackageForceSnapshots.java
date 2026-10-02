package com.iridium126.createmanaindustry.client.particles.packages;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Immutable one-second history with at most four queued/running worker jobs. */
final class PackageForceSnapshots {
    private record Input(long tick,List<PackageForceScene.Source> sources,double x,double y,double z,CompletableFuture<PackageForceScene.Snapshot> result) {}
    private final NavigableMap<Long,Input> history=new TreeMap<>();
    private final AtomicInteger workers=new AtomicInteger();
    private final Set<Input> scheduled=Collections.newSetFromMap(new IdentityHashMap<>());
    private Executor worker;
    boolean needsCapture(long tick){schedule();return !history.containsKey(tick);}
    void capture(long tick,List<PackageForceScene.Source> sources,double x,double y,double z,Executor worker){
        if(!needsCapture(tick))return;this.worker=worker;
        history.put(tick,new Input(tick,List.copyOf(sources),x,y,z,new CompletableFuture<>()));
        while(history.size()>PackageSimulationClock.HISTORY_TICKS){var old=history.pollFirstEntry().getValue();old.result.cancel(false);scheduled.remove(old);}
        schedule();
    }
    private void schedule(){
        if(worker==null)return;
        for(var input:history.values()){
            if(workers.get()>=4)break;if(input.result.isDone()||!scheduled.add(input))continue;
            workers.incrementAndGet();
            try{worker.execute(()->{try{if(!input.result.isCancelled())input.result.complete(PackageForceScene.bake(input.tick,input.sources,input.x,input.y,input.z));}
                catch(Throwable error){input.result.completeExceptionally(error);}finally{workers.decrementAndGet();}});}
            catch(RejectedExecutionException rejected){workers.decrementAndGet();scheduled.remove(input);break;}
        }
    }
    boolean ready(long tick){schedule();var input=history.get(tick);if(input==null||!input.result.isDone()||input.result.isCancelled())return false;
        input.result.join();return true;}
    PackageForceScene.Snapshot snapshot(long tick){if(!ready(tick))throw new IllegalStateException("Package force history unavailable at tick "+tick);return history.get(tick).result.join();}
    boolean contains(long tick){return history.containsKey(tick);}
    void consumed(long tick){var old=new ArrayList<>(history.headMap(tick,true).values());history.headMap(tick,true).clear();scheduled.removeAll(old);schedule();}
    void invalidate(){clear();}
    void clear(){history.values().forEach(input->input.result.cancel(false));history.clear();scheduled.clear();}
}
