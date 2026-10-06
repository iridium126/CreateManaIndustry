package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Owner-thread incremental capture plus immutable worker BVH construction. */
public final class PackageMovingCollisionCache {
    public record GeometryRevision(int identity,long revision) {}
    public interface Cursor {
        boolean hasNext();
        /** Null pauses capture (missing chunk/context); empty means a confirmed empty block. */
        List<PackageMovingGeometry.Box> next();
    }
    public interface Source {
        PackageMovingGeometry.Key key();long revision();boolean alive();
        PackageMovingGeometry.Bounds bounds();
        PackageMovingGeometry.Pose pose(boolean previous);
        Cursor open();
    }
    public static final class Entry {
        public final Source source;public final int identity;
        long revision,sourceRevision=Long.MIN_VALUE,futureRevision;Cursor cursor;List<PackageMovingGeometry.Box> captured;
        CompletableFuture<PackageMovingGeometry.Snapshot> future;
        PackageMovingGeometry.Snapshot snapshot;
        GeometryRevision geometryReference;
        boolean unsupported;
        public PackageMovingGeometry.Bounds bounds;public PackageMovingGeometry.Pose previous,current;
        public long poseFrame;
        Entry(Source source,int identity){this.source=source;this.identity=identity;geometryReference=new GeometryRevision(identity,revision);}
        void setRevision(long revision){this.revision=revision;geometryReference=new GeometryRevision(identity,revision);}
        public PackageMovingGeometry.Snapshot snapshot(){return snapshot;}
        public long revision(){return revision;}
        public boolean unsupported(){return unsupported;}
    }
    private final Thread owner=Thread.currentThread();private final Executor executor;private final LongSupplier clock;
    private final LinkedHashMap<PackageMovingGeometry.Key,Entry> entries=new LinkedHashMap<>();
    private final NavigableMap<Long,List<Entry>> history=new TreeMap<>();
    private final Map<GeometryRevision,Integer> retainedGeometryCounts=new HashMap<>();
    private Set<GeometryRevision> retainedGeometrySnapshot=Set.of();
    private boolean retainedGeometryDirty;
    private int historyTicks=PackageSimulationClock.HISTORY_TICKS;
    public void tickRate(double rate){owner();historyTicks=Math.max(historyTicks,com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageTickTiming.historyTicks(rate));}
    public void captureHistory(long tick,boolean available){
        captureHistory(tick,tick,available);
    }
    /** Split the one observed client pose pair rather than sweeping that whole pair again
     * for each accelerated game step. Each interval has its own immutable identity. */
    public void captureHistory(long first,long last,boolean available){
        if(first<0||last<first||last-first>=historyTicks)throw new IllegalArgumentException("Moving input range");
        for(long tick=first;tick<=last;tick++)captureInterval(tick,available,(double)(tick-first)/(last-first+1),(double)(tick-first+1)/(last-first+1));
    }
    private void captureInterval(long tick,boolean available,double from,double to){
        owner();if(history.containsKey(tick))return;
        List<Entry> frame=new ArrayList<>();
        if(available&&posesReady())for(Entry source:entries.values()){
            Entry frozen=new Entry(source.source,source.identity);frozen.revision=source.revision;frozen.geometryReference=source.geometryReference;
            frozen.bounds=source.bounds;frozen.previous=source.previous.interpolate(source.current,from);frozen.current=source.previous.interpolate(source.current,to);
            frozen.unsupported=source.unsupported;frozen.poseFrame=tick+1;frame.add(frozen);
        }
        else frame=null;
        List<Entry> captured=frame==null?null:List.copyOf(frame);
        history.put(tick,captured);retainGeometry(captured);
        while(history.size()>historyTicks)releaseGeometry(history.pollFirstEntry().getValue());
    }
    public List<Entry> history(long tick){owner();return history.get(tick);}
    /** A missed moving-scene sample may be treated as empty only after a complete discovery
     * confirms there are no moving sources left. Keep the recorded history itself immutable. */
    public List<Entry> simulationFrame(long tick,boolean confirmedEmptyScene){
        owner();List<Entry> frame=history.get(tick);return frame==null&&confirmedEmptyScene?List.of():frame;
    }
    /** Geometry versions referenced by retained input frames remain live in the GPU atlas. */
    public Set<GeometryRevision> retainedGeometry(){
        owner();
        if(retainedGeometryDirty){retainedGeometrySnapshot=Set.copyOf(retainedGeometryCounts.keySet());retainedGeometryDirty=false;}
        return retainedGeometrySnapshot;
    }
    private void retainGeometry(List<Entry> frame){
        if(frame==null)return;
        for(Entry entry:frame){
            GeometryRevision reference=entry.geometryReference;Integer count=retainedGeometryCounts.get(reference);
            if(count==null){retainedGeometryCounts.put(reference,1);retainedGeometryDirty=true;}
            else retainedGeometryCounts.put(reference,count+1);
        }
    }
    private void releaseGeometry(List<Entry> frame){
        if(frame==null)return;
        for(Entry entry:frame){
            GeometryRevision reference=entry.geometryReference;Integer count=retainedGeometryCounts.get(reference);
            if(count==null)throw new IllegalStateException("Missing retained moving geometry reference");
            if(count==1){retainedGeometryCounts.remove(reference);retainedGeometryDirty=true;}
            else retainedGeometryCounts.put(reference,count-1);
        }
    }
    public boolean hasHistory(long tick){owner();return history.containsKey(tick);}
    private final int capacity;private int nextIdentity=1;private long serial,frame,lastCapture,overruns;
    private final java.util.concurrent.atomic.AtomicInteger workers=new java.util.concurrent.atomic.AtomicInteger();
    public PackageMovingCollisionCache(Executor executor,int capacity){this(executor,capacity,System::nanoTime);}
    public PackageMovingCollisionCache(Executor executor,int capacity,LongSupplier clock){if(capacity<=0)throw new IllegalArgumentException("Moving cache capacity");this.executor=Objects.requireNonNull(executor);this.capacity=capacity;this.clock=Objects.requireNonNull(clock);}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Moving collision capture off owner thread");}
    public boolean offer(Source source) {
        owner();Entry old=entries.get(source.key());if(old!=null&&old.source==source)return true;
        if(old==null&&entries.size()==capacity)return false;
        if(nextIdentity>0x1fff_ffff)throw new IllegalStateException("Moving identity exhausted");
        if(old!=null)revoke(old);entries.put(source.key(),new Entry(source,nextIdentity++));return true;
    }
    private void revoke(Entry e){e.snapshot=null;e.cursor=null;e.captured=null;e.setRevision(++serial);e.unsupported=false;e.poseFrame=0;}
    public void invalidate(PackageMovingGeometry.Key key){owner();Entry e=entries.get(key);if(e!=null)revoke(e);}
    public void remove(PackageMovingGeometry.Key key){owner();Entry e=entries.remove(key);if(e!=null)revoke(e);}
    public void clear(){owner();for(Entry e:entries.values())revoke(e);entries.clear();history.clear();retainedGeometryCounts.clear();retainedGeometrySnapshot=Set.of();retainedGeometryDirty=false; /* Never reuse an identity within this world. */}
    public Collection<Entry> entries(){owner();return Collections.unmodifiableCollection(entries.values());}
    public void tick(long budgetNanos) {
        owner();frame++;long start=clock.getAsLong();
        // Sample every light-weight pose before spending the tick budget on block geometry.
        // Otherwise one large/slow sublevel can consume the budget and leave later sources
        // with stale poseFrame values; the immutable history then rejects the whole scene.
        // Static section capture shares this budget and may have already consumed all of it.
        // Poses remain mandatory even with no remaining geometry budget: losing one immutable
        // input interval blocks subsequent physics ticks, including unrelated free packages.
        for(var iterator=entries.entrySet().iterator();iterator.hasNext();) {
            var row=iterator.next();Entry e=row.getValue();long version;
            try{if(!e.source.alive()){iterator.remove();revoke(e);continue;}version=e.source.revision();}
            catch(RuntimeException|LinkageError unavailable){e.snapshot=null;e.poseFrame=0;e.sourceRevision=Long.MIN_VALUE;e.setRevision(++serial);continue;}
            if(version!=e.sourceRevision){e.sourceRevision=version;e.setRevision(++serial);e.snapshot=null;e.cursor=null;e.captured=null;e.unsupported=false;}
            try{e.bounds=e.source.bounds();e.previous=e.source.pose(true);e.current=e.source.pose(false);e.poseFrame=frame;}
            catch(RuntimeException|LinkageError unavailable){e.poseFrame=0;}
        }
        // Rotation of the queue avoids one large structure monopolizing geometry capture.
        int attempts=entries.size();
        while(attempts-->0&&!entries.isEmpty()&&clock.getAsLong()-start<budgetNanos) {
            var row=entries.entrySet().iterator().next();Entry e=row.getValue();entries.remove(row.getKey());entries.put(row.getKey(),e);
            if(e.poseFrame!=frame)continue;
            if(e.future!=null) {
                if(!e.future.isDone())continue;
                if(!e.future.isCompletedExceptionally()&&!e.future.isCancelled()) {
                    var result=e.future.getNow(null);if(result!=null&&result.revision()==e.revision)e.snapshot=result;
                } else if(e.futureRevision==e.revision)e.unsupported=true;
                e.future=null;
            }
            if(e.snapshot!=null||e.unsupported)continue;
            if(e.cursor==null)try{e.cursor=e.source.open();e.captured=new ArrayList<>();}
            catch(RuntimeException|LinkageError unsupported){e.unsupported=true;continue;}
            boolean complete=false;
            // The wall-clock budget is the bound. A fixed per-tick cell count made
            // large Sable plots take minutes to capture (a non-empty section has
            // 4096 cells), keeping their pose-only collision proxy active long after
            // the first packages reached it.
            while(clock.getAsLong()-start<budgetNanos) {
                List<PackageMovingGeometry.Box> boxes;
                try{if(!e.cursor.hasNext()){complete=true;break;}boxes=e.cursor.next();}catch(RuntimeException|LinkageError changed){e.cursor=null;e.captured=null;e.snapshot=null;e.setRevision(++serial);break;}
                if(boxes==null)break;
                if(e.captured.size()+boxes.size()>PackageMovingGeometry.MAX_CAPTURE_BOXES){e.unsupported=true;e.captured=null;e.cursor=null;break;}
                e.captured.addAll(boxes);
            }
            if(e.cursor!=null&&complete&&workers.get()<4) {
                // Freeze this private primitive list. The closure captures no Entry, Source or Level.
                List<PackageMovingGeometry.Box> captured=e.captured;long revision=e.revision;
                var accounting=workers;accounting.incrementAndGet();
                try{e.future=CompletableFuture.supplyAsync(()->{try{return PackageMovingGeometry.bake(revision,captured);}finally{accounting.decrementAndGet();}},executor);e.futureRevision=revision;e.captured=null;e.cursor=null;}
                catch(RejectedExecutionException rejected){accounting.decrementAndGet();/* Retry unchanged cursor next tick. */}
            }
        }
        lastCapture=clock.getAsLong()-start;if(lastCapture>Math.max(0,budgetNanos))overruns++;
    }
    public long lastCaptureNanos(){return lastCapture;}public long overruns(){return overruns;}
    public boolean posesReady(){owner();for(Entry e:entries.values())if(e.poseFrame!=frame)return false;return true;}
}
