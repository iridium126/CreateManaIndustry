package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/** Server-thread downstream journal. It contains committed poses, never client proposals or items.
 * Initial subscriptions walk a live ordered index in bounded batches, without copying the region.
 * Indices are never reused within an authority epoch. The finite journal cannot stall simulation:
 * a slow subscriber must abandon its stream and start a new baseline instead. */
public final class PackageObserverFeed<M> {
    // Two complete 131072-package change waves fit before a poll. Storage is lazy and released
    // when the final observer leaves, so authority-only regions pay no journal reservation.
    public static final int DEFAULT_LOG_CAPACITY=262144;
    public record Member<M>(int index,PackageLease.Identity identity,long leaseEpoch,long revision,
                            PackageDeltaCodec.Quantized state,M metadata,long stateTick) {
        public Member(int index,PackageLease.Identity identity,long leaseEpoch,long revision,
                      PackageDeltaCodec.Quantized state,M metadata){this(index,identity,leaseEpoch,revision,state,metadata,0);}
        public Member {
            if(index<0 || identity==null || leaseEpoch<=0 || revision<=0 || state==null || metadata==null
                    || stateTick<0 || (state.flags()&~PackageAuthorityRegion.STATE_FLAGS)!=0)
                throw new IllegalArgumentException("Observer member");
        }
    }
    public record Batch<M>(long stream,long sequence,boolean reset,boolean complete,
                           List<Member<M>> baselines,List<PackageDeltaCodec.Entry> changes,PackageObserverTimes stateTicks) {
        public Batch(long stream,long sequence,boolean reset,boolean complete,List<Member<M>> baselines,List<PackageDeltaCodec.Entry> changes) {
            this(stream,sequence,reset,complete,baselines,changes,PackageObserverTimes.zeros(changes.size()));
        }
        public Batch {
            if(stream<=0 || sequence<0 || reset && sequence!=0)throw new IllegalArgumentException("Observer stream");
            baselines=List.copyOf(baselines);changes=List.copyOf(changes);
            if(stateTicks==null || stateTicks.size()!=changes.size())throw new IllegalArgumentException("Observer state times");
        }
        public long stateTick(int index){return stateTicks.get(index);}
    }
    public static final class LaggedException extends IllegalStateException {
        private LaggedException(){super("Observer journal overwritten; a fresh stream baseline is required");}
    }
    public static final class Cursor {
        private final PackageObserverFeed<?> feed;
        private final long stream;
        private long nextSerial,sequence;
        private int scanned=-1;
        private boolean reset=true,complete,closed,scanTurn;
        private final NavigableMap<Integer,SnapshotCut> cuts=new TreeMap<>();
        private Cursor(PackageObserverFeed<?> feed,long stream,long serial){this.feed=feed;this.stream=stream;nextSerial=serial;}
        public long stream(){return stream;}
        public boolean complete(){return complete;}
    }
    private record SnapshotCut(int first,long serial) {}
    private record Mutation<M>(long serial,Member<M> addition,PackageDeltaCodec.Entry change,long stateTick) {}
    private static final class Cell<M> {
        final int index;
        final PackageLease.Identity identity;
        final long leaseEpoch,revision;
        final M metadata;
        PackageDeltaCodec.Quantized state;
        long stateTick;
        Cell(Member<M> member){index=member.index();identity=member.identity();leaseEpoch=member.leaseEpoch();revision=member.revision();metadata=member.metadata();state=member.state();stateTick=member.stateTick();}
        Member<M> snapshot(){return new Member<>(index,identity,leaseEpoch,revision,state,metadata,stateTick);}
    }
    private final NavigableMap<Integer,Cell<M>> ordered=new TreeMap<>();
    private final Int2ObjectOpenHashMap<Cell<M>> members=new Int2ObjectOpenHashMap<>();
    private final IntOpenHashSet usedIndices=new IntOpenHashSet();
    private final PackageDeltaCodec.Quantized[] acceptedScratch;
    private final int logCapacity;
    private Mutation<M>[] log;
    private long tail;
    private int subscribers;

    public PackageObserverFeed(){this(DEFAULT_LOG_CAPACITY);}
    @SuppressWarnings("unchecked") public PackageObserverFeed(int capacity) {
        if(capacity<1)throw new IllegalArgumentException("Observer journal capacity");
        logCapacity=capacity;
        acceptedScratch=new PackageDeltaCodec.Quantized[PackageDeltaCodec.MAX_ENTRIES];
    }
    public void activate(Member<M> member) {
        Objects.requireNonNull(member);
        if(!usedIndices.add(member.index()))throw new IllegalArgumentException("Observer index reused");
        var cell=new Cell<>(member);members.put(member.index(),cell);ordered.put(member.index(),cell);append(member,null,member.stateTick());
    }
    /** Call only after the entire authority batch committed. Retired in-flight indices are ignored.
     * Validate all live updates before changing the journal, including velocity-only/flag-only updates. */
    public void accepted(List<PackageDeltaCodec.Entry> changes) {
        accepted(changes,0);
    }
    public void accepted(List<PackageDeltaCodec.Entry> changes,long tick) {
        accepted(changes,null,tick);
    }
    /** Commit already-resolved authority poses, avoiding relative-position wrapper entries and merges. */
    public void acceptedResolved(List<PackageDeltaCodec.Entry> changes,PackageDeltaCodec.Quantized[] resolved,long tick) {
        if(resolved==null || resolved.length<changes.size())throw new IllegalArgumentException("Resolved observer delta");
        accepted(changes,resolved,tick);
    }
    private void accepted(List<PackageDeltaCodec.Entry> changes,PackageDeltaCodec.Quantized[] resolved,long tick) {
        if(tick<0)throw new IllegalArgumentException("Observer state tick");
        if(changes.size()>PackageDeltaCodec.MAX_ENTRIES)throw new IllegalArgumentException("Observer delta count");
        int previous=-1,staged=0;
        try {
        for(int i=0;i<changes.size();i++) {
            var change=changes.get(i);
            if(change.id()<=previous)throw new IllegalArgumentException("Observer delta index");
            previous=change.id();var cell=members.get(change.id());
            if(cell==null || change.mask()==PackageDeltaCodec.RELEASE){acceptedScratch[staged++]=null;continue;}
            if(tick<cell.stateTick)throw new IllegalArgumentException("Observer state clock reversed");
            var state=resolved==null?PackageDeltaCodec.merge(cell.state,change):resolved[i];
            if(state==null || (state.flags()&~PackageAuthorityRegion.STATE_FLAGS)!=0)
                throw new IllegalArgumentException("Invalid resolved observer state");
            acceptedScratch[staged++]=state;
        }
        for(int i=0;i<changes.size();i++) {
            var change=changes.get(i);var cell=members.get(change.id());
            if(cell==null)continue;
            if(change.mask()==PackageDeltaCodec.RELEASE){retire(cell.index,cell.identity);continue;}
            var before=cell.state;var after=acceptedScratch[i];int mask=PackageDeltaCodec.changes(before,after);
            cell.state=after;cell.stateTick=tick;
            if(mask!=0)appendChange(cell.index,mask,after,tick);
        }
        }finally {java.util.Arrays.fill(acceptedScratch,0,staged,null);}
    }
    public boolean retire(int index,PackageLease.Identity identity) {
        var cell=members.get(index);
        if(cell==null || !cell.identity.equals(identity))return false;
        members.remove(index);ordered.remove(index);
        appendChange(index,PackageDeltaCodec.RELEASE,cell.state,cell.stateTick);return true;
    }
    private void appendChange(int index,int mask,PackageDeltaCodec.Quantized state,long tick) {
        append(null,subscribers==0?null:new PackageDeltaCodec.Entry(index,mask,state),tick);
    }
    private void append(Member<M> addition,PackageDeltaCodec.Entry change,long tick) {
        if(tail==Long.MAX_VALUE)throw new IllegalStateException("Observer journal serial exhausted");
        if(subscribers>0)log[(int)(tail%logCapacity)]=new Mutation<>(tail,addition,change,tick);tail++;
    }
    @SuppressWarnings("unchecked") public Cursor subscribe(long stream) {
        if(stream<=0)throw new IllegalArgumentException("Observer stream epoch");
        if(log==null)log=(Mutation<M>[])new Mutation<?>[logCapacity];
        subscribers++;return new Cursor(this,stream,tail);
    }
    public boolean unsubscribe(Cursor cursor) {
        if(cursor==null || cursor.feed!=this)throw new IllegalArgumentException("Observer cursor");
        if(cursor.closed)return false;
        cursor.closed=true;
        if(--subscribers==0)log=null;return true;
    }
    /** A successful send consumes this cursor. If transport throws, discard the cursor; never retry a
     * partially enqueued batch under the same stream. Work is bounded even for unseen journal records. */
    public Batch<M> poll(Cursor cursor,int budget) {
        if(cursor==null || cursor.feed!=this || cursor.closed || budget<1 || budget>PackageDeltaCodec.MAX_ENTRIES)
            throw new IllegalArgumentException("Observer cursor/budget");
        if(cursor.nextSerial<tail-logCapacity)throw new LaggedException();
        if(!cursor.reset && cursor.complete && cursor.nextSerial==tail)return null;
        var baselines=new ArrayList<Member<M>>();var linearChanges=new ArrayList<PackageDeltaCodec.Entry>();
        var linearTicks=new LongArrayList();Int2LongOpenHashMap mergedTicks=null;
        TreeMap<Integer,PackageDeltaCodec.Entry> mergedChanges=null;
        boolean reset=cursor.reset,wasComplete=cursor.complete;int work=0;
        // Give snapshot discovery a share even when motion keeps the journal busy. A range cut
        // fences older queued deltas from overwriting the newer baseline read during that scan.
        int journalBudget=budget;
        if(!cursor.complete && cursor.nextSerial<tail) {
            journalBudget=budget==1?(cursor.scanTurn?0:1):Math.max(1,budget/2);cursor.scanTurn=!cursor.scanTurn;
        }
        while(cursor.nextSerial<tail && work<journalBudget) {
            var mutation=log[(int)(cursor.nextSerial%logCapacity)];
            if(mutation==null || mutation.serial()!=cursor.nextSerial)throw new LaggedException();
            cursor.nextSerial++;work++;
            int index=mutation.addition()!=null?mutation.addition().index():mutation.change().id();
            if(!cursor.complete && index>cursor.scanned)continue;
            var cut=cursor.cuts.ceilingEntry(index);
            if(cut!=null && index>=cut.getValue().first() && mutation.serial()<cut.getValue().serial())continue;
                if(mutation.addition()!=null)baselines.add(mutation.addition());
            else {
                var change=mutation.change();
                if(mergedChanges==null && (linearChanges.isEmpty() || index>linearChanges.getLast().id())) {
                    linearChanges.add(change);linearTicks.add(mutation.stateTick());continue;
                }
                if(mergedChanges==null){mergedChanges=new TreeMap<>();mergedTicks=new Int2LongOpenHashMap();
                    for(int i=0;i<linearChanges.size();i++){var old=linearChanges.get(i);mergedChanges.put(old.id(),old);mergedTicks.put(old.id(),linearTicks.getLong(i));}}
                var before=mergedChanges.get(index);
                int mask=change.mask()==PackageDeltaCodec.RELEASE?PackageDeltaCodec.RELEASE
                        :change.mask()|(before==null?0:before.mask());
                mergedChanges.put(index,new PackageDeltaCodec.Entry(index,mask,change.value()));
                mergedTicks.put(index,mutation.stateTick());
            }
        }
        if(!cursor.complete) {
            int first=cursor.scanned+1;
            while(work<budget) {
                var entry=ordered.higherEntry(cursor.scanned);
                if(entry==null){cursor.complete=true;break;}
                cursor.scanned=entry.getKey();baselines.add(entry.getValue().snapshot());work++;
            }
            if(ordered.higherEntry(cursor.scanned)==null)cursor.complete=true;
            if(cursor.nextSerial<tail) {
                int last=cursor.complete?Integer.MAX_VALUE:cursor.scanned;
                if(last>=first)cursor.cuts.put(last,new SnapshotCut(first,tail));
            }
        }
        if(cursor.nextSerial==tail)cursor.cuts.clear();
        var changes=mergedChanges==null?linearChanges:new ArrayList<>(mergedChanges.values());
        if(!reset && baselines.isEmpty() && changes.isEmpty() && cursor.complete==wasComplete)return null;
        cursor.reset=false;
        long[] times;
        if(mergedChanges==null)times=linearTicks.toLongArray();
        else {times=new long[changes.size()];for(int i=0;i<changes.size();i++)times[i]=mergedTicks.get(changes.get(i).id());}
        return new Batch<>(cursor.stream,cursor.sequence++,reset,cursor.complete,baselines,changes,new PackageObserverTimes(times));
    }
    public Member<M> member(int index){var cell=members.get(index);return cell==null?null:cell.snapshot();}
    public int size(){return members.size();}
    public int logCapacity(){return logCapacity;}
}
