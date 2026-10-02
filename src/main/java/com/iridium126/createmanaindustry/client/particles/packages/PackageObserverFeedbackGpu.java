package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.Objects;
import java.util.function.Consumer;

/** Independent four-slot control snapshots for an observer namespace. No pose or item data is
 * read back. Capture only a sampled publication; callers retain their dirty publication when
 * capture returns false. Rejected feedback requires fallback; a slow fence remains pending.
 * Namespace invalidation discards all pending old-world/stream results without waiting. */
public final class PackageObserverFeedbackGpu implements AutoCloseable {
    public record Status(long epoch,long publication,int invalid,int active,int stale,int slots,
                         long submittedNanos) {
        public boolean rejected(){return invalid!=0;}
        public boolean needsFallback(){return rejected() || stale!=0;}
    }
    private final Thread owner=Thread.currentThread();
    private final PackageReadbackRing ring;
    private final long[] publications=new long[PackageReadbackRing.SLOTS];
    private final int[] counts=new int[PackageReadbackRing.SLOTS];
    private final long[] submitted=new long[PackageReadbackRing.SLOTS];
    private long epoch,sequence,lastCaptured=-1,completed,skipped,readbackBytes,lastLatency;
    private Status latest;
    private PackageObserverGpu source;
    private boolean closed,polling;
    public PackageObserverFeedbackGpu(long epoch) {
        if(epoch<=0)throw new IllegalArgumentException("Observer feedback epoch");
        this.epoch=epoch;ring=new PackageReadbackRing(PackageObserverGpu.CONTROL_BYTES);
    }
    /** Submission is not admission. The publication and its slot count travel with the immutable
     * copied counters, so later uploads cannot change the meaning of an earlier result. */
    public boolean capture(PackageObserverGpu gpu) {
        open();Objects.requireNonNull(gpu);
        if(source!=null && source!=gpu)throw new IllegalArgumentException("Observer source changed without invalidating feedback");
        long publication=gpu.publicationVersion();
        if(publication<lastCaptured)throw new IllegalArgumentException("Observer source changed without invalidating feedback");
        if(publication==lastCaptured)return false;
        int count=gpu.count();
        if(!ring.submit(gpu.controlBuffer(),epoch,sequence)){skipped++;return false;}
        int slot=(int)(sequence%PackageReadbackRing.SLOTS);
        publications[slot]=publication;counts[slot]=count;submitted[slot]=System.nanoTime();
        source=gpu;sequence++;lastCaptured=publication;readbackBytes+=PackageObserverGpu.CONTROL_BYTES;return true;
    }
    /** Zero-timeout FIFO consumption. A callback may retain Status, never mapped/copied bytes.
     * Do not reset or close the component inside this callback; act on the result after poll. */
    public int poll(Consumer<Status> consumer) {
        open();Objects.requireNonNull(consumer);polling=true;
        try {
            return ring.poll(epoch,snapshot->{
                int slot=(int)(snapshot.sequence()%PackageReadbackRing.SLOTS);
                var bytes=snapshot.bytes();int invalid=bytes.getInt(0),stale=bytes.getInt(4),active=bytes.getInt(8);
                int count=counts[slot];
                if(stale<0 || active<0 || (long)active+stale>count || bytes.getInt(12)!=0)
                    throw new IllegalStateException("Invalid observer GPU feedback; restore Create ownership");
                var result=new Status(snapshot.epoch(),publications[slot],invalid,active,stale,count,snapshot.submittedNanos());
                consumer.accept(result);latest=result;completed++;lastLatency=System.nanoTime()-snapshot.submittedNanos();
            });
        }finally{polling=false;}
    }
    /** A new positive namespace epoch is required before using a rebuilt/reset GPU source.
     * Outstanding copies keep deleted storage alive; unfinished buffers are never reused. */
    public void invalidate(long nextEpoch) {
        open();if(nextEpoch<=0 || nextEpoch==epoch)throw new IllegalArgumentException("Fresh observer feedback epoch required");
        ring.invalidate();epoch=nextEpoch;sequence=0;lastCaptured=-1;latest=null;source=null;
        java.util.Arrays.fill(submitted,0);
    }
    public boolean overdue(long now) {
        open();int pending=ring.pending();
        for(long seq=sequence-pending;seq<sequence;seq++)
            if(now-submitted[(int)(seq%PackageReadbackRing.SLOTS)]>100_000_000L)return true;
        return false;
    }
    public Status latest(){open();return latest;}
    public int pending(){open();return ring.pending();}
    public long completed(){open();return completed;}
    public long skipped(){open();return skipped;}
    public long readbackBytes(){open();return readbackBytes;}
    public long lastLatencyNanos(){open();return lastLatency;}
    private void open(){if(closed || polling || Thread.currentThread()!=owner)throw new IllegalStateException("Observer feedback closed/off owner thread/reentrant");}
    @Override public void close(){if(closed)return;open();closed=true;ring.close();latest=null;source=null;}
}
