package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainEventCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Nonblocking node-event readback for one authority/table epoch. Owns track detector lifetime.
 * No world access or gameplay callbacks. Transport ACK means server accepted every candidate in
 * that immutable packet; copying, sending and receiving a packet are never implicit ACKs. */
public final class PackageChainEventChannel implements AutoCloseable {
    public interface Transport {
        /** Copy borrowed bytes into reliable ordered transport before returning true. */
        boolean send(long epoch,long revision,long sequence,ByteBuffer bytes);
        /** Worker-only immutable packet construction; no GL, I/O or mutable world access. */
        default Object prepare(long epoch,long revision,long sequence,ByteBuffer bytes){return null;}
        default boolean sendPrepared(long epoch,long revision,long sequence,Object prepared,ByteBuffer bytes){return send(epoch,revision,sequence,bytes);}
        /** Relinquish the entire epoch and restore Create; invoked once on the render thread. */
        void failed(String reason);
    }
    public record Stats(long captures,long skippedCaptures,long headerBytes,long payloadBytes,long wireBytes,
                        long sentPackets,long ackedPackets,long latestPreparationNanos,long latestRoundTripNanos) {}
    private static final class Frame {
        PackageChainTrackGpu.Capture capture;
        long started;
        int total=-1,submitted,consumed;
        void reset(){capture=null;total=-1;submitted=consumed=0;}
    }
    private static final class Fragment {Frame frame;long sequence;int bytes;}
    private final Thread owner=Thread.currentThread();
    private final PackageChainTrackGpu tracks;
    private final PackageDeltaJournal journal;
    private final PackageReadbackRing headers,payload;
    private final Frame[] frames=new Frame[4];
    private final Fragment[] fragments=new Fragment[4];
    private final ByteBuffer identityScratch;
    private final Transport transport;
    private final LongSupplier clock;
    private final long epoch,revision;
    private final int capacity;
    private final long[] ackMailbox,ackScratch;
    private int candidates;
    private long nextFragment=1,captures,skipped,headerBytes,payloadBytes,latestPreparation,latestRoundTrip,confirmationVersion;
    private volatile boolean closed;

    public PackageChainEventChannel(PackageChainTrackGpu tracks,long epoch,long revision,
                                    PackageDeltaJournal.Encoder encoder,Transport transport) {
        this(tracks,epoch,revision,encoder,transport,System::nanoTime);
    }
    public PackageChainEventChannel(PackageChainTrackGpu tracks,long epoch,long revision,
                                    PackageDeltaJournal.Encoder encoder,Transport transport,LongSupplier clock) {
        if(epoch<=0 || revision<=0)throw new IllegalArgumentException("Chain channel namespace");
        this.tracks=Objects.requireNonNull(tracks);capacity=tracks.capacity();
        if(tracks.count()!=0)throw new IllegalArgumentException("Chain channel must register identities before GPU append");
        this.epoch=epoch;this.revision=revision;this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);
        PackageDeltaJournal nextJournal=null;PackageReadbackRing nextHeaders=null,nextPayload=null;
        try {
            nextJournal=new PackageDeltaJournal(capacity,encoder,(sequence,bytes)->transport.prepare(epoch,revision,sequence,bytes),
                    PackageChainEventCodec.BATCH,PackageChainEventCodec.MAX_WIRE_BYTES,PackageChainEventChannel::encode);
            ackMailbox=new long[nextJournal.slotCapacity()];ackScratch=new long[ackMailbox.length];Arrays.fill(ackMailbox,-1);
            identityScratch=ByteBuffer.allocateDirect(capacity*32).order(ByteOrder.nativeOrder());
            nextHeaders=new PackageReadbackRing(16);nextPayload=new PackageReadbackRing(Math.min(1024*1024,capacity*64));
        }catch(RuntimeException failure) {
            if(nextHeaders!=null)nextHeaders.close();if(nextPayload!=null)nextPayload.close();if(nextJournal!=null)nextJournal.close();tracks.close();throw failure;
        }
        journal=nextJournal;headers=nextHeaders;payload=nextPayload;
        for(int i=0;i<4;i++){frames[i]=new Frame();fragments[i]=new Fragment();}
    }
    private static void encode(ByteBuffer raw,int count,ByteBuffer wire,long[] order,long[] identities,int[] localIds,int identityCount) {
        var in=raw.duplicate().order(ByteOrder.nativeOrder());int start=in.position();
        for(int i=0;i<count;i++) {
            int p=start+i*64,candidate=in.getInt(p+16);
            if(candidate<0 || candidate>=identityCount || localIds[candidate]!=candidate
                    || in.getLong(p)!=identities[candidate*2] || in.getLong(p+8)!=identities[candidate*2+1])
                throw new IllegalArgumentException("Chain event journal identity mismatch");
        }
        PackageChainEventCodec.encode(in,count,wire,order);
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Chain channel off render thread");}
    /** The single append entrance registers the same candidate order on GPU and in its journal. */
    public void append(ByteBuffer metadata) {
        owner();if(closed)throw new IllegalStateException("Chain channel closed");
        try {
            if(metadata==null || !metadata.isDirect() || metadata.remaining()%32!=0 || metadata.remaining()/32>capacity-candidates)
                throw new IllegalArgumentException("Chain channel metadata layout");
            int n=metadata.remaining()/32;identityScratch.clear().put(metadata.duplicate()).flip();
            for(int i=0;i<n;i++)identityScratch.putInt(i*32+20,candidates+i);
            journal.append(identityScratch,n);tracks.append(metadata);candidates+=n;
        }catch(RuntimeException failure){fail("Chain identity append failed: "+failure.getMessage());throw failure;}
    }
    /** Called only after complete physical publication. Saturated banks retain pending GPU events. */
    public boolean capture() {
        owner();if(closed)return false;
        try {
            var capture=tracks.capture(capacity);if(capture==null){skipped++;return false;}
            Frame frame=frames[capture.bank()];if(frame.capture!=null)throw new IllegalStateException("Chain frame overwritten");
            frame.capture=capture;frame.started=clock.getAsLong();
            if(!headers.submit(capture.headerBuffer(),epoch,Integer.toUnsignedLong(capture.stamp()))) {
                tracks.cancel(capture);tracks.finish(capture);frame.reset();skipped++;return false;
            }
            captures++;return true;
        }catch(RuntimeException failure){fail("Chain capture failed: "+failure.getMessage());return false;}
    }
    /** Network-thread mailbox. Old epochs, table namespaces, unsent and duplicate packets fail. */
    public synchronized boolean acknowledge(long candidateEpoch,long candidateRevision,long sequence) {
        if(closed || candidateEpoch!=epoch || candidateRevision!=revision || sequence<0 || !journal.sent(sequence))return false;
        int slot=(int)(sequence%ackMailbox.length);
        if(ackMailbox[slot]==sequence)return false;
        ackMailbox[slot]=sequence;return true;
    }
    public void pump(int maximumPackets) {
        owner();if(closed)return;
        try {
            long now=clock.getAsLong();
            synchronized(this){System.arraycopy(ackMailbox,0,ackScratch,0,ackMailbox.length);Arrays.fill(ackMailbox,-1);}
            for(long sequence:ackScratch)if(sequence>=0) {
                var ack=journal.acknowledge(sequence);if(ack==null)continue;
                tracks.acknowledge(ack.stamp(),ack.records());journal.confirm(sequence);
                confirmationVersion++;
                latestPreparation=ack.sentNanos()-ack.queuedNanos();latestRoundTrip=Math.max(0,now-ack.sentNanos());
            }
            headers.poll(epoch,snapshot->{
                Frame frame=null;
                for(Frame candidate:frames)if(candidate.capture!=null && Integer.toUnsignedLong(candidate.capture.stamp())==snapshot.sequence()){frame=candidate;break;}
                if(frame==null)throw new IllegalStateException("Stale chain header");
                int requested=snapshot.bytes().getInt(0),accepted=snapshot.bytes().getInt(4),overflow=snapshot.bytes().getInt(8);
                if(requested<0 || requested>candidates || accepted!=Math.min(requested,frame.capture.capacity())
                        || overflow!=requested-accepted || snapshot.bytes().getInt(12)!=0)
                    throw new IllegalStateException("Invalid chain GPU counts");
                frame.total=accepted*64;headerBytes+=16;
                if(frame.total==0){tracks.finish(frame.capture);frame.reset();}
            });
            payload.pollAvailable(epoch,snapshot->{
                Fragment fragment=fragments[(int)((snapshot.sequence()-1)%4)];Frame frame=fragment.frame;
                if(frame==null || fragment.sequence!=snapshot.sequence())throw new IllegalStateException("Stale chain fragment");
                if(!journal.offer(frame.capture.stamp(),snapshot.bytes(),frame.started))return false;
                payloadBytes+=fragment.bytes;frame.consumed+=fragment.bytes;fragment.frame=null;
                if(frame.consumed==frame.total){tracks.finish(frame.capture);frame.reset();}
                return true;
            });
            while(payload.pending()<4) {
                Frame frame=null;
                for(Frame candidate:frames)if(candidate.capture!=null && candidate.total>=0 && candidate.submitted<candidate.total
                        && (frame==null || Integer.compareUnsigned(candidate.capture.stamp(),frame.capture.stamp())<0))frame=candidate;
                if(frame==null)break;
                int bytes=Math.min(payload.capacityBytes(),frame.total-frame.submitted);long sequence=nextFragment;
                if(sequence<=0 || sequence==Long.MAX_VALUE)throw new IllegalStateException("Chain fragment namespace exhausted");
                if(!payload.submit(frame.capture.recordBuffer(),frame.submitted,bytes,epoch,sequence))break;
                Fragment fragment=fragments[(int)((sequence-1)%4)];if(fragment.frame!=null)throw new IllegalStateException("Chain fragment overwritten");
                fragment.frame=frame;fragment.sequence=sequence;fragment.bytes=bytes;frame.submitted+=bytes;nextFragment++;
            }
            journal.prepare();now=clock.getAsLong();
            // Delayed packet preparation/readback holds this bounded event journal in place;
            // latency alone does not invalidate chain ownership.
            journal.sendReady((sequence,bytes)->transport.sendPrepared(epoch,revision,sequence,journal.prepared(sequence),bytes),
                    clock,maximumPackets,Long.MAX_VALUE);
        }catch(RuntimeException failure){fail(failure.getMessage());}
    }
    private void fail(String reason){if(closed)return;close();transport.failed(reason==null?"Chain channel failed":reason);}
    public boolean closed(){return closed;}
    public long epoch(){return epoch;}
    public long revision(){return revision;}
    public boolean uses(PackageChainTrackGpu tracks){return this.tracks==tracks;}
    public long confirmationVersion(){owner();return confirmationVersion;}
    public Stats stats(){owner();return new Stats(captures,skipped,headerBytes,payloadBytes,journal.wireBytes(),journal.sentPackets(),journal.ackedPackets(),latestPreparation,latestRoundTrip);}
    @Override public void close() {
        owner();if(closed)return;closed=true;headers.close();payload.close();journal.close();tracks.close();
        for(Frame frame:frames)frame.reset();for(Fragment fragment:fragments)fragment.frame=null;
    }
}
