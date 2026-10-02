package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.HashMap;
import java.util.Map;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

/** Client-side identity/state validation before any GPU upload. No world access, rendering,
 * physics, gameplay or ACK of authority proposals occurs here. A batch commits atomically using
 * an overlay proportional to its changed records, rather than copying/iterating all packages. */
public final class PackageObserverReplica<M> {
    public enum Result { ACCEPTED,STALE,RESYNC }
    private final int capacity;
    private final Int2ObjectOpenHashMap<PackageObserverFeed.Member<M>> members=new Int2ObjectOpenHashMap<>();
    private final Map<PackageLease.Identity,Integer> identities=new HashMap<>();
    private final IntOpenHashSet introduced=new IntOpenHashSet();
    private final PackageObserverFeed.Member<M>[] poseScratch;
    private long stream,lastSequence=-1;
    private boolean complete,closed;
    @SuppressWarnings("unchecked") public PackageObserverReplica(int capacity) {
        if(capacity<1 || capacity>131072)throw new IllegalArgumentException("Observer replica capacity");
        this.capacity=capacity;
        poseScratch=(PackageObserverFeed.Member<M>[])new PackageObserverFeed.Member<?>[PackageDeltaCodec.MAX_ENTRIES];
    }
    public Result apply(PackageObserverFeed.Batch<M> batch) {
        boolean reset=batch.reset();
        if(batch.stream()<stream || batch.stream()==stream && (closed || batch.sequence()<=lastSequence))return Result.STALE;
        if(reset) {if(batch.stream()<=stream || batch.sequence()!=0)return Result.RESYNC;}
        else if(batch.stream()!=stream || batch.sequence()!=lastSequence+1 || complete && !batch.complete())return Result.RESYNC;
        // Position/velocity/yaw/flags change no membership or identity. Validate into reusable
        // scratch, then commit in place; no per-batch identity maps or boxed index sets are needed.
        boolean poseOnly=!reset && batch.baselines().isEmpty();
        if(poseOnly)for(var change:batch.changes())if(change.mask()==PackageDeltaCodec.RELEASE){poseOnly=false;break;}
        if(poseOnly)return poses(batch);
        Map<Integer,PackageObserverFeed.Member<M>> base=reset?Map.of():members;
        var next=new HashMap<Integer,PackageObserverFeed.Member<M>>();var additions=new IntOpenHashSet();
        for(var member:batch.baselines()) {
            if(!additions.add(member.index()) || !reset && introduced.contains(member.index()))return Result.RESYNC;
            next.put(member.index(),member);
        }
        int previous=-1,ordinal=0;
        try {
            for(var change:batch.changes()) {
                long tick=batch.stateTick(ordinal++);
                if(change.id()<=previous)return Result.RESYNC;previous=change.id();
                var member=next.containsKey(change.id())?next.get(change.id()):base.get(change.id());
                if(member==null)return Result.RESYNC;
                if(tick<member.stateTick())return Result.RESYNC;
                if(change.mask()==PackageDeltaCodec.RELEASE){next.put(change.id(),null);continue;}
                next.put(change.id(),new PackageObserverFeed.Member<>(member.index(),member.identity(),member.leaseEpoch(),
                        member.revision(),PackageDeltaCodec.merge(member.state(),change),member.metadata(),tick));
            }
        }catch(IllegalArgumentException invalid){return Result.RESYNC;}
        // Validate final identity uniqueness after retirements. A live object may be released and
        // re-acquired under a new server-local index in the same batch; the old index stays retired.
        var identityOverlay=new HashMap<PackageLease.Identity,Integer>();int size=base.size();
        for(var entry:next.entrySet()) {
            var old=base.get(entry.getKey());if(old!=null){identityOverlay.put(old.identity(),null);size--;}
        }
        for(var entry:next.entrySet())if(entry.getValue()!=null) {
            var member=entry.getValue();
            Integer index=identityOverlay.containsKey(member.identity())?identityOverlay.get(member.identity())
                    :reset?null:identities.get(member.identity());
            if(index!=null && index!=member.index())return Result.RESYNC;
            identityOverlay.put(member.identity(),member.index());size++;
        }
        // Introduction capacity also bounds retired-index memory and the future GPU candidate
        // domain. A long-lived stream is renewed instead of silently recycling old identities.
        if(size>capacity || (reset?0:introduced.size())+additions.size()>capacity)return Result.RESYNC;
        if(reset){members.clear();identities.clear();introduced.clear();}
        for(var entry:next.entrySet()) {
            if(entry.getValue()==null)members.remove(entry.getKey());else members.put(entry.getKey(),entry.getValue());
        }
        for(var entry:identityOverlay.entrySet()) {
            if(entry.getValue()==null)identities.remove(entry.getKey());else identities.put(entry.getKey(),entry.getValue());
        }
        introduced.addAll(additions);stream=batch.stream();lastSequence=batch.sequence();complete=batch.complete();closed=false;
        return Result.ACCEPTED;
    }
    private Result poses(PackageObserverFeed.Batch<M> batch) {
        if(batch.changes().size()>poseScratch.length)return Result.RESYNC;
        int previous=-1,staged=0;
        try {
            for(var change:batch.changes()) {
                if(change.id()<=previous)return Result.RESYNC;previous=change.id();
                var member=members.get(change.id());if(member==null)return Result.RESYNC;
                long tick=batch.stateTick(staged);if(tick<member.stateTick())return Result.RESYNC;
                poseScratch[staged++]=new PackageObserverFeed.Member<>(member.index(),member.identity(),member.leaseEpoch(),
                        member.revision(),PackageDeltaCodec.merge(member.state(),change),member.metadata(),tick);
            }
            for(int i=0;i<staged;i++){var member=poseScratch[i];members.put(member.index(),member);}
            lastSequence=batch.sequence();complete=batch.complete();return Result.ACCEPTED;
        }catch(IllegalArgumentException invalid){return Result.RESYNC;}
        finally {java.util.Arrays.fill(poseScratch,0,staged,null);}
    }
    /** Exact stream identity fences a delayed close from an earlier region subscription. */
    public boolean close(long candidateStream) {
        if(candidateStream!=stream || closed)return false;
        members.clear();identities.clear();introduced.clear();complete=false;closed=true;return true;
    }
    public PackageObserverFeed.Member<M> member(int index){return members.get(index);}
    public int size(){return members.size();}
    public long stream(){return stream;}
    public long sequence(){return lastSequence;}
    public boolean complete(){return complete;}
}
