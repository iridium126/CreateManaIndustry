package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/** Bounded immutable raw records until server ACK. No Minecraft, OpenGL or worker world access. */
public final class PackageDeltaJournal implements AutoCloseable {
    public static final class Encoder {
        private final Executor executor;
        private final int maximum;
        private final AtomicInteger active=new AtomicInteger();
        public Encoder(Executor executor,int maximum){if(maximum<=0)throw new IllegalArgumentException("Encoder budget");this.executor=java.util.Objects.requireNonNull(executor);this.maximum=maximum;}
        CompletableFuture<Void> submit(Runnable task) {
            int n;do{n=active.get();if(n>=maximum)return null;}while(!active.compareAndSet(n,n+1));
            try{return CompletableFuture.runAsync(()->{try{task.run();}finally{active.decrementAndGet();}},executor);}
            catch(RejectedExecutionException rejected){active.decrementAndGet();return null;}
        }
        public int activeTasks(){return active.get();}
    }
    @FunctionalInterface public interface Sender {
        /** Borrowed bytes. true means copied into an ordered, reliable transport; false retains the packet. */
        boolean send(long sequence,ByteBuffer bytes);
    }
    @FunctionalInterface public interface Preparer {
        /** Worker-only: build immutable transport data; no I/O, OpenGL or mutable world access. */
        Object prepare(long sequence,ByteBuffer bytes);
    }
    public record Ack(int stamp,ByteBuffer records,long sentNanos,long queuedNanos) {}
    @FunctionalInterface interface RecordCodec {
        void encode(ByteBuffer raw,int count,ByteBuffer wire,long[] order,long[] identities,int[] localIds,int identityCount);
    }
    private static final int EMPTY=0,QUEUED=1,READY=2,SENT=3,ACKED=4;
    private static final class Slot {
        final ByteBuffer raw,wire;
        final long[] order;
        Slot(int batch,int bytes){raw=ByteBuffer.allocateDirect(batch*64).order(ByteOrder.nativeOrder());wire=ByteBuffer.allocate(bytes);order=new long[batch];}
        volatile long sequence=-1;
        long queuedNanos,sentNanos;
        int stamp,count;
        Object prepared;
        volatile int state;
    }
    private static final class Group {
        final long first;
        final int slots,identities;
        CompletableFuture<Void> future;
        Group(long first,int slots,int identities){this.first=first;this.slots=slots;this.identities=identities;}
    }
    private final Thread owner=Thread.currentThread();
    private final Slot[] slots;
    private final Encoder encoder;
    private final Preparer preparer;
    private final RecordCodec codec;
    private final int batch;
    private final long[] identities;
    private final int[] localIds,reserved;
    private final boolean[] releaseNotified;
    private final Int2IntOpenHashMap candidates=new Int2IntOpenHashMap();
    private final ArrayDeque<Group> groups=new ArrayDeque<>();
    private int identityCount,pending;
    private long firstSequence,nextSequence,wireBytes,sentPackets,ackedPackets;
    private boolean closed;

    public PackageDeltaJournal(int capacity,Encoder encoder) {
        this(capacity,encoder,(sequence,bytes)->null);
    }
    public PackageDeltaJournal(int capacity,Encoder encoder,Preparer preparer) {
        this(capacity,encoder,preparer,PackageDeltaRecords.BATCH,PackageDeltaRecords.MAX_WIRE_BYTES,PackageDeltaRecords::encode);
    }
    static PackageDeltaJournal batchEncoded(int capacity,Encoder encoder,Preparer preparer) {
        return new PackageDeltaJournal(capacity,encoder,preparer,PackageDeltaRecords.BATCH,
                PackageDeltaRecords.MAX_BATCH_WIRE_BYTES,PackageDeltaRecords::encodeBatch);
    }
    /** Shared bounded retention/worker scheduling; each internal event protocol owns its codec. */
    PackageDeltaJournal(int capacity,Encoder encoder,Preparer preparer,int batch,int wireBytes,RecordCodec codec) {
        if(capacity<=0 || capacity>131072)throw new IllegalArgumentException("Journal capacity");
        if(batch<1 || batch>512 || wireBytes<1 || wireBytes>24576)throw new IllegalArgumentException("Journal record bounds");
        this.encoder=java.util.Objects.requireNonNull(encoder);
        this.preparer=java.util.Objects.requireNonNull(preparer);
        this.batch=batch;this.codec=java.util.Objects.requireNonNull(codec);
        slots=new Slot[Math.max(4,(capacity+batch-1)/batch)];for(int i=0;i<slots.length;i++)slots[i]=new Slot(batch,wireBytes);
        identities=new long[capacity*2];localIds=new int[capacity];reserved=new int[capacity];releaseNotified=new boolean[capacity];candidates.defaultReturnValue(-1);
    }
    private void open(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Delta journal off owner thread");if(closed)throw new IllegalStateException("Delta journal closed");}
    /** Append only. Never reuse a candidate or server-local identity within this region epoch. */
    public void append(ByteBuffer metadata,int count) {
        open();if(count<0 || identityCount+count>localIds.length || metadata.remaining()!=count*32)throw new IllegalArgumentException("Journal identity layout");
        var input=metadata.duplicate().order(ByteOrder.nativeOrder());int start=input.position();
        var added=new IntOpenHashSet(count);
        for(int i=0;i<count;i++) {
            int p=start+i*32,id=input.getInt(p+20);
            if(input.getLong(p)<=0 || input.getLong(p+8)<=0 || id<0 || candidates.containsKey(id) || !added.add(id))
                throw new IllegalArgumentException("Journal baseline identity");
        }
        for(int i=0;i<count;i++) {
            int p=start+i*32,candidate=identityCount+i,id=input.getInt(p+20);
            candidates.put(id,candidate);localIds[candidate]=id;identities[candidate*2]=input.getLong(p);identities[candidate*2+1]=input.getLong(p+8);
        }
        identityCount+=count;
    }
    /** Atomically borrow a whole <=1MiB fragment. Full capacity retains its readback slot. */
    public boolean offer(int stamp,ByteBuffer fragment,long now) {
        open();int bytes=fragment.remaining(),count=bytes/64,needed=(count+batch-1)/batch;
        if(stamp==0 || bytes<=0 || bytes%64!=0 || bytes>1024*1024)throw new IllegalArgumentException("Journal fragment");
        if(pending+needed>slots.length)return false;
        var input=fragment.duplicate().order(ByteOrder.nativeOrder());int start=input.position(),marked=0;
        try {
            for(int i=0;i<count;i++) {
                int candidate=input.getInt(start+i*64+16);
                if(candidate<0 || candidate>=identityCount || reserved[candidate]!=0)throw new IllegalArgumentException("Duplicate/unknown in-flight GPU candidate");
                reserved[candidate]=stamp;marked++;
            }
        }catch(RuntimeException failure){for(int i=0;i<marked;i++)reserved[input.getInt(start+i*64+16)]=0;throw failure;}
        if(nextSequence>Long.MAX_VALUE-needed){for(int i=0;i<count;i++)reserved[input.getInt(start+i*64+16)]=0;throw new IllegalStateException("Delta sequence exhausted");}
        long first=nextSequence;
        for(int i=0;i<needed;i++) {
            Slot slot=slot(nextSequence);if(slot.state!=EMPTY)throw new IllegalStateException("Journal sequence ring overwritten");
            int n=Math.min(batch,count-i*batch),position=start+i*batch*64;
            var source=input.duplicate();source.position(position).limit(position+n*64);
            slot.raw.clear().put(source).flip();slot.sequence=nextSequence++;slot.stamp=stamp;slot.count=n;slot.prepared=null;
            slot.queuedNanos=now;slot.state=QUEUED;pending++;
        }
        groups.addLast(new Group(first,needed,identityCount));return true;
    }
    /** Encoding is batched per fragment, not one worker task per package or wire packet. */
    public void prepare() {
        open();
        for(Iterator<Group> iterator=groups.iterator();iterator.hasNext();) {
            Group group=iterator.next();
            if(group.future==null) {
                group.future=encoder.submit(()->{
                    for(int i=0;i<group.slots;i++) {
                        Slot slot=slot(group.first+i);
                        codec.encode(slot.raw.asReadOnlyBuffer(),slot.count,slot.wire,slot.order,identities,localIds,group.identities);
                        slot.prepared=preparer.prepare(slot.sequence,slot.wire.asReadOnlyBuffer());
                    }
                });
                if(group.future==null)break;
            }
            if(group.future.isDone()) {
                group.future.join(); // Only completed futures; never waits for an encoder.
                for(int i=0;i<group.slots;i++)slot(group.first+i).state=READY;
                iterator.remove();
            }
        }
    }
    public int sendReady(Sender sender,long now,int maximum) {
        return sendReady(sender,()->now,maximum,Long.MAX_VALUE);
    }
    /** Sample immediately before each send; worker completion must never hide a preparation timeout. */
    public int sendReady(Sender sender,java.util.function.LongSupplier clock,int maximum,long timeout) {
        open();int sent=0;
        for(long seq=firstSequence;seq<nextSequence && sent<maximum;seq++) {
            Slot slot=slot(seq);
            if(slot.state==SENT || slot.state==ACKED)continue;
            if(slot.state!=READY)break;
            long now=clock.getAsLong();
            if(timeout!=Long.MAX_VALUE && (now<slot.queuedNanos || now-slot.queuedNanos>timeout))
                throw new IllegalStateException("Package monotonic clock reversed during packet preparation");
            // Publish before sending: a loopback ACK can arrive inside the transport callback.
            slot.sentNanos=now;slot.state=SENT;
            if(!sender.send(seq,slot.wire.asReadOnlyBuffer())){slot.state=READY;break;}
            wireBytes+=slot.wire.limit();sentPackets++;sent++;
        }
        return sent;
    }
    public Ack acknowledge(long sequence) {
        open();if(sequence<firstSequence || sequence>=nextSequence)return null;
        Slot slot=slot(sequence);if(slot.sequence!=sequence || slot.state!=SENT)return null;
        return new Ack(slot.stamp,slot.raw.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),slot.sentNanos,slot.queuedNanos);
    }
    /** Mailbox-side check; volatile sequence/state reject guessed, unsent and recycled packet slots. */
    public boolean sent(long sequence){if(sequence<0 || closed)return false;Slot slot=slot(sequence);return slot.sequence==sequence && slot.state==SENT;}
    /** Call only after the matching GPU baseline update has been enqueued successfully. */
    public void confirm(long sequence) {
        open();Slot slot=slot(sequence);if(slot.sequence!=sequence || slot.state!=SENT)throw new IllegalArgumentException("Unsent/stale ACK");
        for(int i=0;i<slot.count;i++){int candidate=slot.raw.getInt(i*64+16);if(reserved[candidate]==slot.stamp)reserved[candidate]=0;}
        slot.state=ACKED;slot.prepared=null;ackedPackets++;
        while(pending>0 && slot(firstSequence).state==ACKED){Slot first=slot(firstSequence++);first.state=EMPTY;first.sequence=-1;pending--;}
    }
    public int candidate(int localId,long id,long generation){open();int candidate=candidates.get(localId);return candidate>=0 && identities[candidate*2]==id && identities[candidate*2+1]==generation?candidate:-1;}
    /** Full identity gate and exactly one lifecycle callback for a terminal server notification. */
    public int releasedCandidate(int localId,long id,long generation) {
        int candidate=candidate(localId,id,generation);
        if(candidate<0 || releaseNotified[candidate])return -1;
        releaseNotified[candidate]=true;return candidate;
    }
    public Object prepared(long sequence) {
        open();Slot slot=slot(sequence);
        if(slot.sequence!=sequence || slot.state!=SENT)throw new IllegalArgumentException("Unprepared/stale transport packet");
        return slot.prepared;
    }
    public boolean preparationExpired(long now,long timeout) {
        open();for(long seq=firstSequence;seq<nextSequence;seq++){Slot slot=slot(seq);if(slot.state!=SENT && slot.state!=ACKED && (now<slot.queuedNanos || now-slot.queuedNanos>timeout))return true;}return false;
    }
    private Slot slot(long sequence){return slots[(int)(sequence%slots.length)];}
    public int pending(){open();return pending;}
    public int slotCapacity(){return slots.length;}
    public long nextSequence(){open();return nextSequence;}
    public long wireBytes(){return wireBytes;}
    public long sentPackets(){return sentPackets;}
    public long ackedPackets(){return ackedPackets;}
    @Override public void close(){if(closed)return;open();closed=true;/* Workers retain immutable input until they finish. */}
}
