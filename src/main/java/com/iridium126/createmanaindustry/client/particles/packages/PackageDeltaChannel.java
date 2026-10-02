package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.LongSupplier;
import org.lwjgl.opengl.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAckRanges;

/**
 * Render-thread header-first readback and asynchronous wire journal for ONE authority epoch.
 * Owns detector lifetime; close invalidates every pending capture/ACK. Never waits for a fence/worker.
 */
public final class PackageDeltaChannel implements AutoCloseable {
    private static final int FRAGMENT_BYTES=1024*1024,MAX_NOTICES=2048;
    public interface Transport {
        /** Negotiated v3 free-package path. Original codec remains the reference/harness default. */
        default boolean batchEncoded(){return false;}
        default boolean relativePositions(){return false;}
        default boolean predictedPositions(){return false;}
        /** Copy borrowed bytes before returning true; preserve per-region message order. */
        boolean send(long epoch,long revision,long sequence,ByteBuffer bytes);
        /** Worker-only immutable envelope preparation. No world access, GL or network writes. */
        default Object prepare(long epoch,long revision,long sequence,ByteBuffer bytes){return null;}
        default Object prepare(long epoch,long revision,long sequence,long step,ByteBuffer bytes){return prepare(epoch,revision,sequence,bytes);}
        default boolean sendPrepared(long epoch,long revision,long sequence,Object prepared,ByteBuffer bytes){return send(epoch,revision,sequence,bytes);}
        /** Must relinquish this epoch and restore Create. Invoked once on the render thread. */
        void failed(String reason);
        default void released(int localId,long id,long generation) {}
    }
    public record Stats(long captures,long skippedCaptures,long headerBytes,long payloadBytes,long wireBytes,
                        long sentPackets,long ackedPackets,long ackDispatches,long latestPreparationNanos,long latestRoundTripNanos) {}
    public record Timing(long samples,double p50Millis,double p95Millis) {}
    public record Timings(Timing capture,Timing pump,Timing packetPreparation,Timing roundTrip) {}
    private static final class Samples {
        final long[] values=new long[128];int cursor,count;
        void add(long value){values[cursor]=Math.max(0,value);cursor=(cursor+1)%values.length;count=Math.min(count+1,values.length);}
        void clear(){cursor=count=0;}
        Timing report(){if(count==0)return new Timing(0,0,0);long[] sorted=Arrays.copyOf(values,count);Arrays.sort(sorted);
            return new Timing(count,sorted[(count-1)/2]/1e6,sorted[(int)Math.ceil(count*.95)-1]/1e6);}
    }
    private static final class Frame {
        PackageDeltaGpu.Capture capture;
        long started,simulationStep;
        int total=-1,submitted,consumed;
        void reset(){capture=null;total=-1;submitted=consumed=0;}
    }
    private static final class Fragment {Frame frame;long sequence;int offset,bytes;}
    private final Thread owner=Thread.currentThread();
    private final PackageDeltaGpu detector;
    private final PackageDeltaJournal journal;
    private final PackageReadbackRing headers,payload;
    private final Frame[] frames=new Frame[4];
    private final Fragment[] fragments=new Fragment[4];
    private final Transport transport;
    private final LongSupplier clock;
    private final long epoch,revision;
    private final int capacity;
    private final long[] ackMailbox,ackScratch;
    private final int gpuJournal;
    private final long[] batchSequences;
    private int batchStamp,batchCount,batchFirstRecord,batchRecords;
    private final ByteBuffer notices=ByteBuffer.allocate(MAX_NOTICES*64).order(ByteOrder.nativeOrder());
    private final ByteBuffer noticeScratch=ByteBuffer.allocateDirect(MAX_NOTICES*64).order(ByteOrder.nativeOrder());
    private int noticeCount;
    private boolean noticeOverflow;
    private volatile boolean closed;
    private boolean profiling;
    private final Samples captureTimes=new Samples(),pumpTimes=new Samples(),preparationTimes=new Samples(),roundTripTimes=new Samples();
    private long nextFragment=1,captures,skipped,headerBytes,payloadBytes,ackDispatches,latestPreparation,latestRoundTrip;

    public PackageDeltaChannel(PackageDeltaGpu detector,int capacity,long epoch,long revision,
                               PackageDeltaJournal.Encoder encoder,Transport transport) {
        this(detector,capacity,epoch,revision,encoder,transport,System::nanoTime);
    }
    public PackageDeltaChannel(PackageDeltaGpu detector,int capacity,long epoch,long revision,
                               PackageDeltaJournal.Encoder encoder,Transport transport,LongSupplier clock) {
        // GPU journal coalescing remains an opt-in comparison: target-load p95 gains are not reproducible yet.
        this(detector,capacity,epoch,revision,encoder,transport,clock,false);
    }
    /** Internal benchmark switch. The direct path provides a comparison against ACK coalescing. */
    public PackageDeltaChannel(PackageDeltaGpu detector,int capacity,long epoch,long revision,
                               PackageDeltaJournal.Encoder encoder,Transport transport,LongSupplier clock,boolean coalesceAcks) {
        if(capacity<=0 || capacity>131072 || epoch<=0 || revision<=0)throw new IllegalArgumentException("Package channel namespace/capacity");
        this.detector=java.util.Objects.requireNonNull(detector);this.capacity=capacity;this.epoch=epoch;this.revision=revision;
        this.transport=java.util.Objects.requireNonNull(transport);this.clock=java.util.Objects.requireNonNull(clock);
        java.util.Objects.requireNonNull(encoder);
        if(detector.relativePositions()!=transport.relativePositions() || detector.predictedPositions()!=transport.predictedPositions()
                || transport.relativePositions() && !transport.batchEncoded())
            throw new IllegalArgumentException("Package GPU/wire position encoding mismatch");
        PackageDeltaJournal nextJournal=null;
        PackageReadbackRing headerRing=null;
        int nextGpuJournal=0;
        try{
            PackageDeltaJournal.Preparer prepare=new PackageDeltaJournal.Preparer(){
                public Object prepare(long sequence,ByteBuffer bytes){return transport.prepare(epoch,revision,sequence,bytes);}
                public Object prepare(long sequence,long step,ByteBuffer bytes){return transport.prepare(epoch,revision,sequence,step,bytes);}
            };
            nextJournal=transport.batchEncoded()?PackageDeltaJournal.batchEncoded(capacity,encoder,prepare):new PackageDeltaJournal(capacity,encoder,prepare);
            ackMailbox=new long[nextJournal.slotCapacity()];ackScratch=new long[ackMailbox.length];Arrays.fill(ackMailbox,-1);
            batchSequences=new long[ackMailbox.length];
            if(coalesceAcks) {
                nextGpuJournal=GL15.glGenBuffers();GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER,nextGpuJournal);
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER,(long)nextJournal.slotCapacity()*512*64,GL15.GL_DYNAMIC_COPY);
            }
            headerRing=new PackageReadbackRing(16);payload=new PackageReadbackRing(Math.min(FRAGMENT_BYTES,capacity*64));
        }catch(RuntimeException failure){if(headerRing!=null)headerRing.close();if(nextGpuJournal!=0)GL15.glDeleteBuffers(nextGpuJournal);if(nextJournal!=null)nextJournal.close();detector.close();throw failure;}
        journal=nextJournal;gpuJournal=nextGpuJournal;
        headers=headerRing;
        for(int i=0;i<4;i++){frames[i]=new Frame();fragments[i]=new Fragment();}
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Package channel off render thread");}
    /** Only append server-confirmed candidates. Caller handles final baseline/admission readiness. */
    public void append(ByteBuffer metadata,ByteBuffer baseline,int count) {
        owner();if(closed)throw new IllegalStateException("Package channel closed");
        try{journal.append(metadata,count);detector.append(metadata,baseline,count);}
        catch(RuntimeException failure){fail("Package identity append failed: "+failure.getMessage());throw failure;}
    }
    public boolean recyclable(int candidate,long barrier){
        owner();for(var frame:frames)if(frame.capture!=null&&Integer.toUnsignedLong(frame.capture.stamp())<=barrier)return false;
        return journal.recyclable(candidate);
    }
    public void recycle(int candidate){owner();detector.makeReusable(candidate);}
    public void write(int candidate,ByteBuffer metadata,ByteBuffer baseline){owner();journal.replace(candidate,metadata);detector.write(candidate,metadata,baseline);}
    /** Capture only a complete committed physical state. Capacity/ring pressure never blocks simulation. */
    public boolean capture(int bodies,int bodyCount,float ox,float oy,float oz){return capture(bodies,bodyCount,ox,oy,oz,0);}
    public boolean capture(int bodies,int bodyCount,float ox,float oy,float oz,long simulationStep) {
        owner();if(closed)return false;
        long cpuStarted=profiling?System.nanoTime():0;
        try {
            var capture=detector.capture(bodies,bodyCount,ox,oy,oz,capacity);
            if(capture==null){skipped++;return false;}
            Frame frame=frames[capture.bank()];if(frame.capture!=null)throw new IllegalStateException("Package readback frame overwritten");
            frame.capture=capture;frame.simulationStep=simulationStep;frame.started=clock.getAsLong();
            if(!headers.submit(capture.headerBuffer(),epoch,Integer.toUnsignedLong(capture.stamp()))) {
                detector.cancel(capture);frame.reset();skipped++;return false;
            }
            captures++;return true;
        }catch(RuntimeException failure){fail("Package capture failed: "+failure.getMessage());return false;}
        finally{if(cpuStarted!=0)captureTimes.add(System.nanoTime()-cpuStarted);}
    }
    /** Payload handlers only fill bounded mailboxes; no GL calls or mutable world reads here. */
    public synchronized boolean acknowledge(long candidateEpoch,long candidateRevision,long sequence) {
        if(closed || candidateEpoch!=epoch || candidateRevision!=revision || sequence<0 || !journal.sent(sequence))return false;
        int index=(int)(sequence%ackMailbox.length);ackMailbox[index]=Math.max(ackMailbox[index],sequence);return true;
    }
    /** One bounded mailbox transaction for exact server-accepted runs. Holes, guessed, unsent,
     * recycled and old namespace sequences never become ACKs. No GL calls or pose work. */
    public synchronized int acknowledge(long candidateEpoch,long candidateRevision,PackageAckRanges ranges) {
        if(closed || candidateEpoch!=epoch || candidateRevision!=revision)return 0;
        int accepted=0;
        for(int run=0;run<ranges.runs();run++) {
            long start=ranges.start(run);
            for(int i=0;i<ranges.length(run);i++) {
                long sequence=start+i;if(!journal.sent(sequence))continue;
                int index=(int)(sequence%ackMailbox.length);ackMailbox[index]=Math.max(ackMailbox[index],sequence);accepted++;
            }
        }
        return accepted;
    }
    public synchronized boolean released(long candidateEpoch,PackageAuthorityRegion.Baseline baseline) {
        if(closed || candidateEpoch!=epoch)return false;
        if(noticeCount==MAX_NOTICES){noticeOverflow=true;return false;}
        int p=noticeCount++*64;notices.putLong(p,baseline.identity().id()).putLong(p+8,baseline.identity().generation());
        notices.putInt(p+16,-1).putInt(p+20,baseline.index()).putInt(p+24,0).putInt(p+28,1);return true;
    }
    public void pump(int maximumPackets) {
        owner();if(closed)return;
        long cpuStarted=profiling?System.nanoTime():0;
        try {
            long now=clock.getAsLong();
            int noticesReady;
            synchronized(this) {
                if(noticeOverflow)throw new IllegalStateException("Ownership notification capacity exhausted");
                System.arraycopy(ackMailbox,0,ackScratch,0,ackMailbox.length);Arrays.fill(ackMailbox,-1);
                noticesReady=noticeCount;noticeScratch.clear();var source=notices.duplicate();source.position(0).limit(noticesReady*64);noticeScratch.put(source).flip();noticeCount=0;
            }
            for(int i=0;i<noticesReady;i++) {
                int p=i*64,localId=noticeScratch.getInt(p+20);long id=noticeScratch.getLong(p),generation=noticeScratch.getLong(p+8);
                int candidate=journal.releasedCandidate(localId,id,generation);noticeScratch.putInt(p+16,candidate);
                if(candidate>=0)transport.released(localId,id,generation);
            }
            if(noticesReady>0)detector.serverReleased(noticeScratch);
            for(long sequence:ackScratch)if(sequence>=0) {
                var ack=journal.acknowledge(sequence);if(ack==null)continue;
                if(gpuJournal==0) {
                    detector.acknowledge(ack.stamp(),ack.records());journal.confirm(sequence);ackDispatches++;
                } else {
                    int first=(int)(sequence%journal.slotCapacity())*512,n=ack.records().remaining()/64;
                    if(batchCount>0 && (batchStamp!=ack.stamp() || first!=batchFirstRecord+batchRecords || batchRecords+n>PackageDeltaGpu.MAX_ACK_RECORDS))flushAcks();
                    if(batchCount==0)batchFirstRecord=first;
                    batchStamp=ack.stamp();batchRecords+=n;batchSequences[batchCount++]=sequence;
                }
                latestPreparation=ack.sentNanos()-ack.queuedNanos();latestRoundTrip=Math.max(0,now-ack.sentNanos());
                if(profiling){preparationTimes.add(latestPreparation);roundTripTimes.add(latestRoundTrip);}
            }
            flushAcks();
            headers.poll(epoch,snapshot->{
                Frame frame=null;for(Frame candidate:frames)if(candidate.capture!=null && Integer.toUnsignedLong(candidate.capture.stamp())==snapshot.sequence()){frame=candidate;break;}
                if(frame==null)throw new IllegalStateException("Stale package header");
                int requested=snapshot.bytes().getInt(0),accepted=snapshot.bytes().getInt(4),overflow=snapshot.bytes().getInt(8);
                if(requested<0 || requested>capacity || accepted!=Math.min(requested,frame.capture.capacity()) || overflow!=requested-accepted)
                    throw new IllegalStateException("Invalid GPU delta counts");
                frame.total=accepted*64;headerBytes+=16;
                if(frame.total==0){detector.finish(frame.capture);frame.reset();}
            });
            payload.pollAvailable(epoch,snapshot->{
                Fragment fragment=fragments[(int)((snapshot.sequence()-1)%4)];Frame frame=fragment.frame;
                if(frame==null || fragment.sequence!=snapshot.sequence())throw new IllegalStateException("Stale package fragment");
                long first=journal.nextSequence();
                if(!journal.offer(frame.capture.stamp(),snapshot.bytes(),frame.started,frame.simulationStep))return false;
                if(gpuJournal!=0)copyJournal(fragment,first);
                payloadBytes+=fragment.bytes;frame.consumed+=fragment.bytes;fragment.frame=null;
                if(frame.consumed==frame.total){detector.finish(frame.capture);frame.reset();}
                return true;
            });
            // Keep fragment submissions ordered by capture stamp. A bank remains immutable until
            // every copied fragment has entered the journal, including fragments awaiting capacity.
            while(payload.pending()<4) {
                Frame frame=null;
                for(Frame candidate:frames)if(candidate.capture!=null && candidate.total>=0 && candidate.submitted<candidate.total
                        && (frame==null || Integer.compareUnsigned(candidate.capture.stamp(),frame.capture.stamp())<0))frame=candidate;
                if(frame==null)break;
                int bytes=Math.min(payload.capacityBytes(),frame.total-frame.submitted);
                long sequence=nextFragment;
                if(!payload.submit(frame.capture.recordBuffer(),frame.submitted,bytes,epoch,sequence))break;
                Fragment fragment=fragments[(int)((sequence-1)%4)];if(fragment.frame!=null)throw new IllegalStateException("Package fragment overwritten");
                fragment.frame=frame;fragment.offset=frame.submitted;fragment.bytes=bytes;fragment.sequence=sequence;frame.submitted+=bytes;nextFragment++;
            }
            journal.prepare();
            now=clock.getAsLong();
            // Worker/fence latency is backpressure, not an ownership failure. The journal and
            // capture rings are bounded; keep their immutable records until they can be sent.
            journal.sendReady((sequence,bytes)->transport.sendPrepared(epoch,revision,sequence,journal.prepared(sequence),bytes),
                    clock,maximumPackets,Long.MAX_VALUE);
        }catch(RuntimeException failure){fail(failure.getMessage());}
        finally{if(cpuStarted!=0)pumpTimes.add(System.nanoTime()-cpuStarted);}
    }
    private void flushAcks() {
        if(batchCount==0)return;
        detector.acknowledgeRange(batchStamp,gpuJournal,batchFirstRecord,batchRecords);ackDispatches++;
        // CPU reservations retire only after this complete, matching GPU update was submitted.
        for(int i=0;i<batchCount;i++)journal.confirm(batchSequences[i]);
        batchCount=batchRecords=0;
    }
    private void copyJournal(Fragment fragment,long firstSequence) {
        int copied=0,first=(int)(firstSequence%journal.slotCapacity())*512*64,size=journal.slotCapacity()*512*64;
        GL42.glMemoryBarrier(GL42.GL_BUFFER_UPDATE_BARRIER_BIT);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER,fragment.frame.capture.recordBuffer());GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER,gpuJournal);
        while(copied<fragment.bytes) {
            int bytes=Math.min(fragment.bytes-copied,size-first);
            GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER,GL31.GL_COPY_WRITE_BUFFER,fragment.offset+copied,first,bytes);
            copied+=bytes;first=0;
        }
    }
    private void fail(String reason){if(closed)return;close();transport.failed(reason==null?"Package channel failed":reason);}
    public boolean closed(){return closed;}
    public long epoch(){return epoch;}
    public long revision(){return revision;}
    boolean uses(PackageDeltaGpu candidate){owner();return !closed && detector==candidate;}
    /** Changes only after a submitted GPU ACK. A skipped/in-flight detection must be retried when
     * that ACK frees its identity, even if physics is paused and its publication did not change. */
    public long confirmationVersion(){owner();return ackDispatches;}
    public Stats stats(){owner();return new Stats(captures,skipped,headerBytes,payloadBytes,journal.wireBytes(),journal.sentPackets(),journal.ackedPackets(),ackDispatches,latestPreparation,latestRoundTrip);}
    public void profiling(boolean enabled){owner();if(enabled && !profiling){captureTimes.clear();pumpTimes.clear();preparationTimes.clear();roundTripTimes.clear();}profiling=enabled;}
    /** Sorting is diagnostic-only; never called from frame submission. */
    public Timings timings(){owner();return new Timings(captureTimes.report(),pumpTimes.report(),preparationTimes.report(),roundTripTimes.report());}
    @Override public void close(){owner();if(closed)return;closed=true;headers.close();payload.close();journal.close();detector.close();if(gpuJournal!=0)GL15.glDeleteBuffers(gpuJournal);for(Frame frame:frames)frame.reset();}
}
