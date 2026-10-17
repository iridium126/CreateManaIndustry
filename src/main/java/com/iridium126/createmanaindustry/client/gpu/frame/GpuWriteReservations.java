package com.iridium126.createmanaindustry.client.gpu.frame;

/** Generation and output-range guards. Rejected reservations never advance the visible prefix. */
public final class GpuWriteReservations {
    public record Range(long generation,long storageEpoch,int first,int count,int capacity) {
        public Range {
            if(generation<=0||storageEpoch<0||first<0||count<0||capacity<0||first>capacity-count)
                throw new IllegalArgumentException("GPU output range");
        }
    }
    private long generation,storageEpoch;
    private int capacity,cursor;
    private boolean active,failed;
    public void begin(long next,long epoch,int slots) {
        if(next<=0||epoch<0||slots<0)throw new IllegalArgumentException("GPU output generation");
        generation=next;storageEpoch=epoch;capacity=slots;cursor=0;failed=false;active=true;
    }
    public Range reserve(int count,boolean whole) {
        check();if(count<0)throw new IllegalArgumentException("GPU reservation count");
        int available=capacity-cursor;
        int accepted=whole&&count>available?0:Math.min(count,available);
        var range=new Range(generation,storageEpoch,cursor,accepted,capacity);
        cursor+=accepted;return range;
    }
    /** GPU append passes use a bounded counter within this generation's physical output range. */
    public Range appendDomain() { check();return new Range(generation,storageEpoch,0,capacity,capacity); }
    public void validate(Range range,long epoch) {
        check();if(range.generation()!=generation||range.storageEpoch()!=storageEpoch||epoch!=storageEpoch
                ||range.capacity()!=capacity||range.first()>capacity-range.count()) {
            failed=true;throw new IllegalStateException("Stale GPU output reservation");
        }
    }
    public void fail() { failed=true; }
    public void requireCommittable() { check(); }
    public void retire() { active=false; }
    private void check() { if(!active||failed)throw new IllegalStateException("GPU candidate is retired or failed"); }
}
