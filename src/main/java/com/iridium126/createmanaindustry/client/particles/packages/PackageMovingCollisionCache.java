package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Owner-thread incremental capture plus immutable worker BVH construction. */
public final class PackageMovingCollisionCache {
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
        boolean unsupported;
        public PackageMovingGeometry.Bounds bounds;public PackageMovingGeometry.Pose previous,current;
        public long poseFrame;
        Entry(Source source,int identity){this.source=source;this.identity=identity;}
        public PackageMovingGeometry.Snapshot snapshot(){return snapshot;}
        public long revision(){return revision;}
        public boolean unsupported(){return unsupported;}
    }
    private final Thread owner=Thread.currentThread();private final Executor executor;private final LongSupplier clock;
    private final LinkedHashMap<PackageMovingGeometry.Key,Entry> entries=new LinkedHashMap<>();
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
    private void revoke(Entry e){e.snapshot=null;e.cursor=null;e.captured=null;e.revision=++serial;e.unsupported=false;e.poseFrame=0;}
    public void invalidate(PackageMovingGeometry.Key key){owner();Entry e=entries.get(key);if(e!=null)revoke(e);}
    public void remove(PackageMovingGeometry.Key key){owner();Entry e=entries.remove(key);if(e!=null)revoke(e);}
    public void clear(){owner();for(Entry e:entries.values())revoke(e);entries.clear(); /* Never reuse an identity within this world. */}
    public Collection<Entry> entries(){owner();return Collections.unmodifiableCollection(entries.values());}
    public void tick(long budgetNanos) {
        owner();frame++;if(budgetNanos<=0)return;long start=clock.getAsLong();
        // Rotation of the queue avoids one large structure monopolizing captures.
        int attempts=entries.size();
        while(attempts-->0&&!entries.isEmpty()&&clock.getAsLong()-start<budgetNanos) {
            var iterator=entries.entrySet().iterator();var row=iterator.next();Entry e=row.getValue();iterator.remove();
            entries.put(row.getKey(),e);
            long version;
            try{if(!e.source.alive()){remove(row.getKey());continue;}version=e.source.revision();}
            catch(RuntimeException|LinkageError unavailable){e.snapshot=null;e.poseFrame=0;e.sourceRevision=Long.MIN_VALUE;e.revision=++serial;continue;}
            if(version!=e.sourceRevision){e.sourceRevision=version;e.revision=++serial;e.snapshot=null;e.cursor=null;e.captured=null;e.unsupported=false;}
            try{e.bounds=e.source.bounds();e.previous=e.source.pose(true);e.current=e.source.pose(false);e.poseFrame=frame;}
            catch(RuntimeException|LinkageError unavailable){e.poseFrame=0;continue;}
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
            int batch=0;
            boolean complete=false;
            while(batch++<16&&clock.getAsLong()-start<budgetNanos) {
                List<PackageMovingGeometry.Box> boxes;
                try{if(!e.cursor.hasNext()){complete=true;break;}boxes=e.cursor.next();}catch(RuntimeException|LinkageError changed){e.cursor=null;e.captured=null;e.snapshot=null;e.revision=++serial;break;}
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
        lastCapture=clock.getAsLong()-start;if(lastCapture>budgetNanos)overruns++;
    }
    public long lastCaptureNanos(){return lastCapture;}public long overruns(){return overruns;}
    public boolean posesReady(){owner();for(Entry e:entries.values())if(e.poseFrame!=frame)return false;return true;}
}
