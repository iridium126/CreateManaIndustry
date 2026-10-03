package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Server-thread chain lease and ordered node transaction boundary. Normal motion has no
 * per-package heartbeat, position packet or tick traversal. Inventory belongs to Target. */
public final class PackageChainAuthority {
    public record State(float progress,long tick,PackageLease.Pose pose) {
        public State {if(!Float.isFinite(progress) || progress<0 || tick<0)throw new IllegalArgumentException("Chain state");Objects.requireNonNull(pose);}
    }
    public record Baseline(int index,PackageLease.Identity identity,long leaseEpoch,long revision,
                           int track,long trackRevision,State state,int eligibility) {}
    public record Event(long identity,long generation,int candidate,long revision,int step,
                        int actual,int ahead,int track,int flags,float before,float after,float rate) {}
    public interface Target {
        PackageLease.Identity identity();
        State snapshot(long tick);
        boolean eligible();
        /** Freeze ONLY after hidden GPU admission; Create simulates throughout resource preparation. */
        boolean freeze();
        int eligibility();
        /** Validate native object, track and gameplay qualification; no side effects. */
        boolean validate(Event event,long tick);
        /** Execute once after the complete wire batch validates. true means native handoff/removal. */
        boolean commit(Event event,long tick);
        /** Reconstruct a checkpoint on demand, restore original lists, and notify the client. */
        void released(Baseline baseline,boolean frozen);
    }
    public enum Result { ACCEPTED,DUPLICATE,INVALID,STALE,FAILED }
    private static final class Entry {
        final int index,track;final long trackRevision;final Target target;final PackageLease lease;
        State state;int candidate=-1,mask,lastStep,lastAhead;boolean nativeFrozen;
        Entry(int index,int track,long revision,Target target,State state,PackageLease lease) {
            this.index=index;this.track=track;trackRevision=revision;this.target=target;this.state=state;this.lease=lease;
        }
    }
    private final UUID owner;
    private final long epoch;
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    private final Map<PackageLease.Identity,Entry> identities=new HashMap<>();
    private final Map<Long,Entry> liveIdentities=new HashMap<>();
    private final Map<Integer,Entry> candidates=new HashMap<>();
    private final Map<Integer,PackageLease.Identity> retired=new HashMap<>();
    private final Map<Integer,Set<Entry>> byTrack=new HashMap<>();
    private final Map<Long,Long> generations=new HashMap<>();
    private final Set<Entry> pending=new LinkedHashSet<>();
    private record InteractionReceipt(PackageChainInteraction request,Result result) {}
    private final Map<UUID,InteractionReceipt> interactions=new HashMap<>();
    private final ByteBuffer lastPacket=ByteBuffer.allocate(PackageChainEventCodec.MAX_WIRE_BYTES).limit(0);
    private long receipt,lastSequence=-1;private int nextIndex;private boolean closed,applying;
    private final long authorityTimeoutTicks;
    private final java.util.function.DoubleSupplier tickRate;
    public PackageChainAuthority(UUID owner,long epoch,long tick) {
        this(owner,epoch,tick,PackageLease.TIMEOUT_TICKS,PackageTickTiming.DEFAULT_RATE);
    }
    public PackageChainAuthority(UUID owner,long epoch,long tick,long authorityTimeoutTicks,java.util.function.DoubleSupplier tickRate) {
        this.owner=Objects.requireNonNull(owner);if(epoch<=0 || tick<0)throw new IllegalArgumentException("Chain authority epoch");this.epoch=epoch;receipt=tick;
        if(authorityTimeoutTicks<PackageLease.TIMEOUT_TICKS)throw new IllegalArgumentException("Chain authority timeout");
        this.authorityTimeoutTicks=authorityTimeoutTicks;this.tickRate=Objects.requireNonNull(tickRate);
    }
    public Baseline offer(Target target,int track,long revision,long tick) {
        Objects.requireNonNull(target);
        if(closed || nextIndex==Integer.MAX_VALUE || pending.size()>=256 || target.identity().generation()<=observedGeneration(target.identity().id()) || liveIdentities.containsKey(target.identity().id())
                || track<0 || track>=131072 || revision<=0 || !target.eligible())return null;
        var state=target.snapshot(tick);var lease=new PackageLease(target.identity(),state.pose(),()->receipt,authorityTimeoutTicks,
                authorityTimeoutTicks==PackageLease.TIMEOUT_TICKS?PackageLease.TIMEOUT_TICKS:PackageLease.FINAL_BASELINE_TIMEOUT_TICKS,tickRate);lease.acquire(owner,state.pose(),tick);
        var entry=new Entry(nextIndex++,track,revision,target,state,lease);entry.mask=target.eligibility();
        entries.put(entry.index,entry);identities.put(target.identity(),entry);pending.add(entry);
        liveIdentities.put(target.identity().id(),entry);
        generations.put(target.identity().id(),target.identity().generation());
        byTrack.computeIfAbsent(track,key->new LinkedHashSet<>()).add(entry);return baseline(entry);
    }
    public Baseline prepared(UUID sender,long epoch,int index,PackageLease.Identity identity,long lease,long revision,int candidate,long tick) {
        Entry entry=find(sender,epoch,index,identity,tick);
        if(entry!=null && entry.nativeFrozen && entry.candidate==candidate && entry.lease.epoch()==lease
                && revision==entry.lease.baselineRevision()-1)return baseline(entry);
        if(entry==null || candidate<0 || candidate>=131072 || candidates.containsKey(candidate) || retired.containsKey(candidate) || !entry.target.eligible())return null;
        var state=entry.target.snapshot(tick);
        if(entry.lease.freezeBaseline(sender,lease,revision,tick,state.pose())<0)return null;
        entry.state=state;entry.mask=entry.target.eligibility();entry.candidate=candidate;candidates.put(candidate,entry);
        try{if(!entry.target.freeze()){release(entry);return null;}entry.nativeFrozen=true;}
        catch(RuntimeException failed){release(entry);throw failed;}
        receipt=tick;return baseline(entry);
    }
    public Baseline ready(UUID sender,long epoch,int index,PackageLease.Identity identity,long lease,long revision,long tick) {
        Entry entry=find(sender,epoch,index,identity,tick);
        if(entry!=null && entry.lease.state()==PackageLease.State.GPU_OWNED && entry.lease.epoch()==lease
                && entry.lease.baselineRevision()==revision)return baseline(entry);
        if(entry==null || entry.candidate<0 || !entry.target.eligible()
                || !entry.lease.ready(sender,lease,revision,tick,entry.target.snapshot(tick).pose()))return null;
        entry.state=new State(entry.state.progress,tick,entry.state.pose);pending.remove(entry);receipt=tick;return baseline(entry);
    }
    /** Shared receipt renews ordinary chain motion too: geometry + time basis predict its position. */
    public boolean heartbeat(UUID sender,long epoch,long tick) {
        if(closed || !owner.equals(sender) || epoch!=this.epoch || expired(tick))return false;
        receipt=tick;return true;
    }
    public boolean release(UUID sender,long epoch,int index,PackageLease.Identity identity,long lease,long tick) {
        Entry entry=find(sender,epoch,index,identity,tick);if(entry==null || entry.lease.epoch()!=lease)return false;release(entry);return true;
    }
    public void release(PackageLease.Identity identity){Entry entry=identities.get(identity);if(entry!=null)release(entry);}
    public void invalidateTrack(int track) {
        var affected=byTrack.get(track);if(affected!=null)for(var entry:new ArrayList<>(affected))release(entry);
    }
    public Baseline baseline(PackageLease.Identity identity){Entry entry=identities.get(identity);return entry==null?null:baseline(entry);}
    public boolean active(PackageLease.Identity identity){Entry entry=identities.get(identity);return entry!=null && entry.lease.state()==PackageLease.State.GPU_OWNED;}
    public long observedGeneration(long identity){return generations.getOrDefault(identity,0L);}
    /** The actor may be an observer, not the physics owner. Its UUID comes from server context.
     * Keep only its last serial request. Claim before native callbacks; altered retries and
     * old serials cannot repeat item movement, even after the original lease was retired. */
    public Result pickup(UUID actor,PackageChainInteraction request,long tick,Function<Baseline,State> authorize,Consumer<State> commit) {
        Objects.requireNonNull(actor);Objects.requireNonNull(request);Objects.requireNonNull(authorize);Objects.requireNonNull(commit);
        if(closed || applying || request.epoch()!=epoch || expired(tick))return Result.STALE;
        var previous=interactions.get(actor);
        if(previous!=null && request.transaction()<=previous.request.transaction()) {
            if(!previous.request.equals(request))return Result.INVALID;
            return previous.result==Result.ACCEPTED?Result.DUPLICATE:previous.result;
        }
        var entry=identities.get(request.identity());Result result=Result.STALE;
        if(entry!=null && entry.lease.state()==PackageLease.State.GPU_OWNED && !entry.lease.expired(tick)
                && entry.lease.epoch()==request.leaseEpoch() && entry.lease.baselineRevision()==request.revision()
                && entry.track==request.track() && entry.trackRevision==request.trackRevision() && entry.target.eligible()) {
            var state=authorize.apply(baseline(entry));
            if(state!=null && state.tick()==tick && Float.floatToIntBits(state.progress())==Float.floatToIntBits(request.progress())) {
                interactions.put(actor,new InteractionReceipt(request,Result.ACCEPTED));applying=true;
                try {
                    entry.state=state;commit.accept(state);
                    if(identities.get(request.identity())==entry)release(entry);
                    return Result.ACCEPTED;
                }catch(RuntimeException failed) {
                    interactions.put(actor,new InteractionReceipt(request,Result.FAILED));
                    try{close();}catch(RuntimeException restore){failed.addSuppressed(restore);}return Result.FAILED;
                }finally{applying=false;}
            }
            result=Result.INVALID;
        }
        interactions.put(actor,new InteractionReceipt(request,result));return result;
    }
    /** Decode is bounded and finishes before gameplay callbacks. Exact duplicate ACK only:
     * out-of-order old packets never receive an ACK for an unrelated transaction. */
    public Result events(UUID sender,long epoch,long sequence,long tick,ByteBuffer wire,ByteBuffer scratch) {
        if(closed || applying || !owner.equals(sender) || epoch!=this.epoch || tick<receipt || tick-receipt>2)return Result.STALE;
        if(sequence==lastSequence)return lastPacket.equals(wire)?Result.DUPLICATE:Result.INVALID;
        if(sequence<0 || sequence!=lastSequence+1)return Result.INVALID;
        scratch.clear();int count;
        try{count=PackageChainEventCodec.decode(wire,scratch);}catch(IllegalArgumentException invalid){return Result.INVALID;}
        var raw=scratch.duplicate().order(ByteOrder.nativeOrder());raw.flip();Entry[] selected=new Entry[count];Event[] events=new Event[count];
        for(int i=0;i<count;i++) {
            int p=i*64;var event=new Event(raw.getLong(p),raw.getLong(p+8),raw.getInt(p+16),raw.getLong(p+20),raw.getInt(p+28),
                    raw.getInt(p+32),raw.getInt(p+36),raw.getInt(p+40),raw.getInt(p+44),raw.getFloat(p+48),raw.getFloat(p+52),raw.getFloat(p+56));
            Entry entry=candidates.get(event.candidate);events[i]=event;
            // Retired candidate indices are never recycled inside an epoch. Late events do no work.
            if(entry==null) {
                var old=retired.get(event.candidate);
                if(old==null || old.id()!=event.identity || old.generation()!=event.generation)return Result.INVALID;
                continue;
            }
            if(entry.target.identity().id()!=event.identity || entry.target.identity().generation()!=event.generation
                    || entry.track!=event.track || event.flags!=PackageChainEventCodec.FALLBACK && entry.trackRevision!=event.revision || entry.lease.state()!=PackageLease.State.GPU_OWNED
                    || entry.lease.expired(tick) || ((event.actual|event.ahead)&~entry.mask)!=0
                    || entry.lastStep!=0 && Integer.compareUnsigned(event.step,entry.lastStep)<=0)return Result.INVALID;
            // Durable GPU anticipation may remain in a newer actual event while its old ACK
            // is in flight. Validate/notify only newly requested anticipation bits.
            if((event.ahead&entry.lastAhead)!=0)events[i]=new Event(event.identity,event.generation,event.candidate,event.revision,event.step,
                    event.actual,event.ahead&~entry.lastAhead,event.track,event.flags,event.before,event.after,event.rate);
            if(!entry.target.validate(events[i],tick))return Result.INVALID;
            selected[i]=entry;
        }
        applying=true;
        try {
            // Claim the packet before side effects. A failure forces epoch fallback; retry cannot
            // repeat an inventory callback that threw after changing native state.
            lastPacket.clear().put(wire.duplicate()).flip();lastSequence=sequence;receipt=tick;
            for(int i=0;i<count;i++)if(selected[i]!=null) {
                Entry entry=selected[i];Event event=events[i];entry.lastStep=event.step;entry.lastAhead=raw.getInt(i*64+36);
                if((event.flags&PackageChainEventCodec.FALLBACK)!=0){release(entry);continue;}
                boolean terminal=entry.target.commit(event,tick);
                // Native list removal can retire this entry inside the callback. A fallback
                // without removal restores the newly accepted checkpoint, not the old offer.
                if(identities.get(entry.target.identity())==entry) {
                    entry.state=entry.target.snapshot(tick);if(terminal)release(entry);
                }
            }
            return Result.ACCEPTED;
        }catch(RuntimeException failed){try{close();}catch(RuntimeException restore){failed.addSuppressed(restore);}return Result.FAILED;}
        finally{applying=false;}
    }
    public void tick(long tick) {
        if(closed)return;
        if(expired(tick)){close();return;}
        if(!pending.isEmpty())for(Entry entry:new ArrayList<>(pending))if(entry.lease.expired(tick))release(entry);
    }
    public void close() {
        if(closed)return;closed=true;RuntimeException failure=null;
        for(Entry entry:new ArrayList<>(entries.values()))try{release(entry);}catch(RuntimeException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
        if(failure!=null)throw failure;
    }
    private void release(Entry entry) {
        if(!identities.remove(entry.target.identity(),entry))return;
        liveIdentities.remove(entry.target.identity().id(),entry);
        entries.remove(entry.index);candidates.remove(entry.candidate,entry);if(entry.candidate>=0)retired.put(entry.candidate,entry.target.identity());pending.remove(entry);
        var track=byTrack.get(entry.track);track.remove(entry);if(track.isEmpty())byTrack.remove(entry.track);
        boolean frozen=entry.nativeFrozen;Baseline previous=baseline(entry);entry.lease.release();entry.lease.finishRelease();
        entry.target.released(previous,frozen);
    }
    private Entry find(UUID sender,long epoch,int index,PackageLease.Identity identity,long tick) {
        if(closed || !owner.equals(sender) || epoch!=this.epoch)return null;
        Entry entry=entries.get(index);return entry!=null && entry.target.identity().equals(identity) && !entry.lease.expired(tick)?entry:null;
    }
    private Baseline baseline(Entry entry){return new Baseline(entry.index,entry.target.identity(),entry.lease.epoch(),entry.lease.baselineRevision(),entry.track,entry.trackRevision,entry.state,entry.mask);}
    public UUID owner(){return owner;}public long epoch(){return epoch;}public boolean closed(){return closed;}public int size(){return entries.size();}
    public boolean expired(long tick){return tick<receipt || tick-receipt>PackageTickTiming.deadlineTicks(authorityTimeoutTicks,tickRate.getAsDouble());}
}
