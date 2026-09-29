package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;

/** Four independent immutable GPU snapshots. A false submit never acknowledges dirty state/events. */
public final class PackageReadbackRing implements AutoCloseable {
    public static final int SLOTS=4;
    /** Bytes are borrowed until the callback returns; decoding/transport must not retain this buffer. */
    public record Snapshot(long epoch,long sequence,long submittedNanos,ByteBuffer bytes) {}
    private static final class Slot {
        int buffer;
        long fence,epoch,sequence,submittedNanos;
        int length;
        boolean copied;
    }
    private final Slot[] slots=new Slot[SLOTS];
    private final ByteBuffer scratch;
    private final int bytes;
    private int next, pending;
    private long lastSequence=-1;
    private boolean closed;
    public PackageReadbackRing(int bytes) {
        if(bytes<=0 || bytes>1024*1024)throw new IllegalArgumentException("Bounded package snapshot size");
        this.bytes=bytes;scratch=BufferUtils.createByteBuffer(bytes);
        try {
            for(int i=0;i<SLOTS;i++) {
                Slot slot=new Slot();slots[i]=slot;slot.buffer=GL15.glGenBuffers();
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,slot.buffer);
                GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,bytes,GL15.GL_STREAM_READ);
            }
        }catch(RuntimeException failure){close();throw failure;}
    }
    public boolean submit(int source,long epoch,long sequence) {
        return submit(source,0,bytes,epoch,sequence);
    }
    /** Bounded fragments of an immutable capture; never copies unused maximum-capacity records. */
    public boolean submit(int source,long sourceOffset,int length,long epoch,long sequence) {
        open();
        if(source<=0 || sourceOffset<0 || length<=0 || length>bytes || sourceOffset>Long.MAX_VALUE-length)
            throw new IllegalArgumentException("Invalid package snapshot source range");
        if(sequence<0 || sequence<=lastSequence)throw new IllegalArgumentException("Snapshot sequence must increase");
        if(pending==SLOTS)return false;
        Slot slot=slots[next];
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,source);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,slot.buffer);
        GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,sourceOffset,0,length);
        long fence=GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);
        if(fence==0)throw new IllegalStateException("Package snapshot fence allocation failed");
        slot.epoch=epoch;slot.sequence=sequence;slot.length=length;slot.copied=false;slot.submittedNanos=System.nanoTime();slot.fence=fence;
        lastSequence=sequence;next=(next+1)%SLOTS;pending++;
        return true;
    }
    /** Zero timeout; stale epochs are retired without decoding after their copies finish. */
    public int poll(long epoch,Consumer<Snapshot> consumer) {
        return pollAvailable(epoch,snapshot->{consumer.accept(snapshot);return true;});
    }
    /** A full transport journal can refuse consumption. Keep that completed slot until a later poll. */
    public int pollAvailable(long epoch,Predicate<Snapshot> consumer) {
        open();int consumed=0;
        while(pending>0) {
            Slot slot=slots[(next-pending+SLOTS)%SLOTS];
            int status=GL32.glClientWaitSync(slot.fence,GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if(status==GL32.GL_TIMEOUT_EXPIRED)break;
            if(status==GL32.GL_WAIT_FAILED) {
                invalidate();throw new IllegalStateException("Package snapshot fence wait failed; restore Create ownership");
            }
            if(slot.epoch!=epoch){retire(slot);continue;}
            // FIFO stops on refusal, so scratch remains this exact immutable slot. A full
            // journal must not cause the same <=1MiB GPU download on every subsequent frame.
            if(!slot.copied) {
                scratch.clear().limit(slot.length);GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,slot.buffer);
                GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER,0,scratch);slot.copied=true;
            }
            if(!consumer.test(new Snapshot(slot.epoch,slot.sequence,slot.submittedNanos,scratch.asReadOnlyBuffer().order(scratch.order()))))break;
            retire(slot);
            consumed++;
        }
        return consumed;
    }
    private void retire(Slot slot){GL32.glDeleteSync(slot.fence);slot.fence=0;slot.copied=false;pending--;}
    public void invalidate() {
        open();
        while(pending>0) {
            Slot slot=slots[(next-pending+SLOTS)%SLOTS];
            // New names/storage: outstanding GPU copies retain the deleted old storage until complete.
            // Never reuse an unfinished snapshot's storage for a new world/epoch.
            GL15.glDeleteBuffers(slot.buffer);
            slot.buffer=GL15.glGenBuffers();GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,slot.buffer);
            GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER,bytes,GL15.GL_STREAM_READ);
            retire(slot);
        }
        lastSequence=-1;
    }
    public int pending(){return pending;}
    public int capacityBytes(){return bytes;}
    private void open(){if(closed)throw new IllegalStateException("Package readbacks closed");}
    @Override public void close() {
        if(closed)return;closed=true;
        for(Slot slot:slots)if(slot!=null) {
            if(slot.fence!=0)GL32.glDeleteSync(slot.fence);
            if(slot.buffer!=0)GL15.glDeleteBuffers(slot.buffer);
        }
        pending=0;
    }
}
