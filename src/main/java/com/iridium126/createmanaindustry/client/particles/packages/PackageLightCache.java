package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Owner-thread light snapshots. Two vanilla nibble layers, no world references or geometry bake. */
public final class PackageLightCache {
    public static final int BYTES=4096, BLOCKS=4096;
    public interface Source {
        /** Detached block/sky nibble arrays, or null to use the bounded fallback. */
        byte[] copy(PackageCollisionCache.Section section);
        /** -1 means unavailable; otherwise block in bits 0..3, sky in bits 4..7. */
        int sample(PackageCollisionCache.Section section,int index);
    }
    public interface Listener {
        void invalidated(PackageCollisionCache.Section section,long revision);
        void published(PackageCollisionCache.Section section,Snapshot snapshot);
    }
    public static final class Snapshot {
        private final long revision;
        private final ByteBuffer bytes;
        public Snapshot(long revision,byte[] data) {
            if(revision<=0 || data.length!=BYTES)throw new IllegalArgumentException("Light snapshot layout");
            this.revision=revision;
            bytes=ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());bytes.put(data).flip();
        }
        public long revision(){return revision;}
        public ByteBuffer bytes(){return bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        public int light(int index) {
            if(index<0 || index>=BLOCKS)throw new IllegalArgumentException("Light cell");
            int shift=(index&1)*4;
            return ((bytes.get(index/2)>>>shift)&15)|(((bytes.get(2048+index/2)>>>shift)&15)<<4);
        }
    }
    private static final class Entry {
        long revision=1,blockedTick=-1;Snapshot ready;byte[] capture;int cursor;boolean queued;
    }
    private final Thread owner=Thread.currentThread();
    private final int capacity;
    private final LongSupplier clock;
    private final Map<PackageCollisionCache.Section,Entry> entries=new HashMap<>();
    private final Map<Long,java.util.List<PackageCollisionCache.Section>> columns=new HashMap<>();
    private final ArrayDeque<PackageCollisionCache.Section> pending=new ArrayDeque<>();
    private Listener listener;
    private long lastNanos,overruns,sourceFailures;
    private final long[] samples=new long[128];
    private int sampleCursor,sampleCount;
    private long tick;
    public PackageLightCache(int capacity){this(capacity,System::nanoTime);}
    PackageLightCache(int capacity,LongSupplier clock) {
        if(capacity<1 || capacity>1024)throw new IllegalArgumentException("Light section capacity");
        this.capacity=capacity;this.clock=clock;
    }
    public void listener(Listener listener){owner();this.listener=listener;}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Light capture off owner thread");}
    public boolean request(PackageCollisionCache.Section section) {
        owner();var entry=entries.get(section);
        if(entry==null){if(entries.size()==capacity)return false;entry=new Entry();entries.put(section,entry);
            columns.computeIfAbsent(column(section.x(),section.z()),key->new java.util.ArrayList<>()).add(section);}
        if(entry.ready==null)queue(section,entry);return true;
    }
    private void queue(PackageCollisionCache.Section section,Entry entry){if(!entry.queued){entry.queued=true;pending.addLast(section);}}
    public void invalidate(PackageCollisionCache.Section section) {
        owner();var entry=entries.get(section);if(entry==null)return;
        entry.revision=Math.incrementExact(entry.revision);entry.ready=null;entry.capture=null;entry.cursor=0;
        queue(section,entry);if(listener!=null)listener.invalidated(section,entry.revision);
    }
    /** Missing sky sections inherit light from higher sections: invalidate the requested column. */
    public static long column(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
    public void invalidateColumn(int x,int z){owner();var sections=columns.get(column(x,z));if(sections!=null)for(var section:sections)invalidate(section);}
    public void tick(Source source,long budgetNanos) {
        owner();long start=clock.getAsLong();long frame=++tick;int visits=pending.size()+128;
        while(visits-->0 && !pending.isEmpty() && clock.getAsLong()-start<budgetNanos) {
            var section=pending.removeFirst();var entry=entries.get(section);entry.queued=false;
            if(entry.blockedTick==frame){queue(section,entry);continue;}
            if(entry.capture==null) {
                byte[] detached;
                try{detached=source.copy(section);}catch(RuntimeException | LinkageError unavailable){sourceFailures++;entry.blockedTick=frame;queue(section,entry);continue;}
                if(detached!=null){publish(section,entry,detached);continue;}
                entry.capture=new byte[BYTES];
            }
            // A missing/unloaded section makes no progress and cannot become dark "air".
            int end=Math.min(BLOCKS,entry.cursor+32);
            while(entry.cursor<end && clock.getAsLong()-start<budgetNanos) {
                int value;
                try{value=source.sample(section,entry.cursor);}catch(RuntimeException | LinkageError unavailable){sourceFailures++;value=-1;}
                if(value<0){entry.blockedTick=frame;break;}
                if(value>255)throw new IllegalArgumentException("Light sample range");
                int offset=entry.cursor/2,shift=(entry.cursor&1)*4;
                entry.capture[offset]|=(byte)((value&15)<<shift);
                entry.capture[2048+offset]|=(byte)((value>>>4)<<shift);entry.cursor++;
            }
            if(entry.cursor==BLOCKS)publish(section,entry,entry.capture);else queue(section,entry);
        }
        lastNanos=clock.getAsLong()-start;if(lastNanos>budgetNanos && budgetNanos>0)overruns++;
        samples[sampleCursor]=lastNanos;sampleCursor=(sampleCursor+1)%samples.length;sampleCount=Math.min(sampleCount+1,samples.length);
    }
    private void publish(PackageCollisionCache.Section section,Entry entry,byte[] data) {
        entry.ready=new Snapshot(entry.revision,data);entry.capture=null;entry.cursor=0;
        if(listener!=null)listener.published(section,entry.ready);
    }
    public Snapshot snapshot(PackageCollisionCache.Section section){owner();var entry=entries.get(section);return entry==null?null:entry.ready;}
    public long revision(PackageCollisionCache.Section section){owner();var entry=entries.get(section);return entry==null?0:entry.revision;}
    public void forEachRequested(java.util.function.BiConsumer<PackageCollisionCache.Section,Long> consumer){owner();entries.forEach((section,entry)->consumer.accept(section,entry.revision));}
    public Map<PackageCollisionCache.Section,Snapshot> snapshots(){owner();var result=new HashMap<PackageCollisionCache.Section,Snapshot>();entries.forEach((s,e)->{if(e.ready!=null)result.put(s,e.ready);});return result;}
    public int size(){owner();return entries.size();}
    public int pending(){owner();return pending.size();}
    public long lastNanos(){return lastNanos;}
    public long overruns(){return overruns;}
    public long sourceFailures(){return sourceFailures;}
    public long capturePercentile(double fraction){owner();if(sampleCount==0)return 0;var sorted=java.util.Arrays.copyOf(samples,sampleCount);java.util.Arrays.sort(sorted);return sorted[Math.clamp((int)Math.ceil(sampleCount*fraction)-1,0,sampleCount-1)];}
    public void clear(){owner();entries.clear();columns.clear();pending.clear();}
}
