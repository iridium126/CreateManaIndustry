package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/**
 * Render-thread confirmation of new package admissions. The four staging slots copy only the
 * requested 32-byte candidate range; a full ring refuses submission without claiming ownership.
 * The entire immutable identity must match the committed GPU output before any slot is trusted.
 */
public final class PackageAdmissionTracker implements AutoCloseable {
    public static final int RECORD_BYTES=32;
    public record Expected(long id,long generation,int flags) {
        public Expected {
            if(id<=0 || generation<=0 || (flags&~(PackagePoolGpu.CHAIN|PackagePoolGpu.FLIPPED|PackagePoolGpu.HIDDEN|PackagePoolGpu.FRAMED|PackagePoolGpu.ACTIVE_AUTHORITY))!=0
                    || (flags&PackagePoolGpu.FRAMED)!=0&&(flags&PackagePoolGpu.CHAIN)==0)
                throw new IllegalArgumentException("Invalid expected package admission");
        }
    }
    public record Outcome(long submission,int candidate,long id,long generation,int slotPlusOne,int flags) {
        public boolean accepted(){return slotPlusOne>0;}
    }
    private record Pending(long submission,int first,int capacity,List<Expected> expected) {}
    private final PackageReadbackRing ring;
    private final ArrayDeque<Pending> pending=new ArrayDeque<>(PackageReadbackRing.SLOTS);
    private final long epoch;
    private final int maxCandidates;
    private final Thread owner=Thread.currentThread();
    private boolean closed;

    public PackageAdmissionTracker(int maxCandidates,long epoch) {
        if(maxCandidates<=0 || maxCandidates>32768 || epoch<=0)
            throw new IllegalArgumentException("Package admission bounds/epoch");
        this.maxCandidates=maxCandidates;this.epoch=epoch;
        ring=new PackageReadbackRing(maxCandidates*RECORD_BYTES);
    }
    /** Call after pool.commit(); false means all staging slots are borrowed, so retry later. */
    public boolean submit(PackagePoolGpu pool,int firstCandidate,List<Expected> expected,long submission) {
        open();
        if(pool==null || expected==null || expected.isEmpty() || expected.size()>maxCandidates
                || firstCandidate<0 || (long)firstCandidate+expected.size()>pool.admissionCount())
            throw new IllegalArgumentException("Package admission candidate range");
        List<Expected> copy=List.copyOf(expected);
        if(!ring.submit(pool.admissionBuffer(),(long)firstCandidate*RECORD_BYTES,
                copy.size()*RECORD_BYTES,epoch,submission))return false;
        pending.addLast(new Pending(submission,firstCandidate,pool.capacity(),copy));
        return true;
    }
    /** Never waits for a fence; failure revokes all snapshots and requires authority retirement. */
    public int poll(Consumer<Outcome> consumer) {
        open();if(consumer==null)throw new IllegalArgumentException("Missing admission consumer");
        try {
            return ring.poll(epoch,snapshot->{
                Pending batch=pending.peekFirst();
                if(batch==null || batch.submission!=snapshot.sequence()
                        || snapshot.bytes().remaining()!=batch.expected.size()*RECORD_BYTES)
                    throw new IllegalStateException("Package admission generation/length mismatch");
                ByteBuffer data=snapshot.bytes().duplicate().order(ByteOrder.nativeOrder());
                var slots=new IntOpenHashSet(batch.expected.size());
                for(int i=0;i<batch.expected.size();i++) {
                    Expected expected=batch.expected.get(i);int offset=data.position()+i*RECORD_BYTES;
                    long id=data.getLong(offset),generation=data.getLong(offset+8);
                    int slotPlusOne=data.getInt(offset+16),flags=data.getInt(offset+20);
                    if(id!=expected.id || generation!=expected.generation
                            || slotPlusOne<0 || slotPlusOne>batch.capacity
                            || flags!=(slotPlusOne==0?0:expected.flags)
                            || data.getInt(offset+24)!=0 || data.getInt(offset+28)!=0
                            || (slotPlusOne!=0 && !slots.add(slotPlusOne)))
                        throw new IllegalStateException("Package admission identity/flags mismatch");
                }
                // Validate the whole snapshot before publishing any ownership transition.
                for(int i=0;i<batch.expected.size();i++) {
                    int offset=data.position()+i*RECORD_BYTES;
                    long id=data.getLong(offset),generation=data.getLong(offset+8);
                    int slotPlusOne=data.getInt(offset+16),flags=data.getInt(offset+20);
                    consumer.accept(new Outcome(batch.submission,batch.first+i,id,generation,slotPlusOne,flags));
                }
                pending.removeFirst();
            });
        }catch(RuntimeException failed){close();throw failed;}
    }
    public int pending(){open();return pending.size();}
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Package admission tracker closed/off render thread");}
    @Override public void close(){
        if(closed)return;closed=true;pending.clear();ring.close();
    }
}
