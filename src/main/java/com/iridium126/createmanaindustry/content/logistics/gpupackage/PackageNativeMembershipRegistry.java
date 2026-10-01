package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.*;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackageObserverPacket.Visual;
import net.minecraft.resources.ResourceLocation;

/** Validates membership-only streams before invoking an engine adapter. No pose decoding,
 * world access, GL calls or authority side effects. Work per ordinary batch is bounded by its
 * records. Nonempty namespace replacement requires runtime rebuild until slot reclamation is
 * supported; it must not synchronously traverse a 131072-member stream on the client thread. */
public final class PackageNativeMembershipRegistry {
    public enum Result { ACCEPTED,STALE,RESYNC }
    public interface Sink {
        void confirm(PackageRegion region,long epoch,PackageObserverFeed.Member<Visual> member);
        void retire(PackageRegion region,long epoch,PackageObserverFeed.Member<Visual> member);
    }
    private static final class Stream {
        final long epoch,revision;
        final PackageObserverReplica<Visual> replica;
        boolean closed;
        Stream(long epoch,long revision,int capacity){this.epoch=epoch;this.revision=revision;replica=new PackageObserverReplica<>(capacity);}
    }
    private record Owner(PackageRegion region,int index) {}
    private final ResourceLocation dimension;
    private final Set<PackageRegion> subscribed;
    private final Map<PackageRegion,Stream> streams=new HashMap<>();
    private final Map<UUID,Owner> uuids=new HashMap<>();
    private final Map<Integer,Owner> entities=new HashMap<>();
    private final Map<PackageLease.Identity,Owner> identities=new HashMap<>();
    private final int capacity;
    public PackageNativeMembershipRegistry(ResourceLocation dimension,Set<PackageRegion> subscribed,int capacity) {
        this.dimension=Objects.requireNonNull(dimension);this.subscribed=Set.copyOf(subscribed);this.capacity=capacity;
        if(subscribed.size()>8||capacity<1||capacity>131072)throw new IllegalArgumentException("Native membership bounds");
    }
    public Result apply(ClientboundPackageObserverPacket p,Sink sink) {
        Objects.requireNonNull(sink);
        if(!dimension.equals(p.dimension())||!subscribed.contains(p.region()))return Result.STALE;
        if((p.flags()&ClientboundPackageObserverPacket.MEMBERSHIP_ONLY)==0)return Result.RESYNC;
        var previous=streams.get(p.region());
        boolean reset=(p.flags()&ClientboundPackageObserverPacket.RESET)!=0;
        if(previous!=null) {
            if(p.epoch()<previous.epoch||p.stream()<previous.replica.stream())return Result.STALE;
            if(p.stream()==previous.replica.stream()&&(p.epoch()!=previous.epoch||p.revision()!=previous.revision))return Result.RESYNC;
            if((p.flags()&ClientboundPackageObserverPacket.CLOSE)==0&&p.stream()==previous.replica.stream()
                    && (previous.closed||p.sequence()<=previous.replica.sequence()))return Result.STALE;
        }
        if((p.flags()&ClientboundPackageObserverPacket.CLOSE)!=0) {
            if(previous==null||p.epoch()!=previous.epoch||p.revision()!=previous.revision||p.stream()!=previous.replica.stream())return Result.STALE;
            if(previous.replica.size()!=0)return Result.RESYNC;
            if(!previous.replica.close(p.stream()))return Result.STALE;
            previous.closed=true;return Result.ACCEPTED;
        }
        if(previous==null&&!reset)return Result.RESYNC;
        if(previous!=null&&reset&&p.stream()>previous.replica.stream()&&previous.replica.size()!=0)return Result.RESYNC;
        var stream=previous==null||reset&&p.stream()>previous.replica.stream()?new Stream(p.epoch(),p.revision(),capacity):previous;
        // Prevalidate both visual keys across all subscribed regions. Inventory identity alone
        // cannot disambiguate duplicate native entity IDs/UUIDs in a corrupted introduction.
        var uuidOverlay=new HashMap<UUID,Owner>();var entityOverlay=new HashMap<Integer,Owner>();
        var identityOverlay=new HashMap<PackageLease.Identity,Owner>();var releases=new IntOpenHashSet();
        var retired=new ArrayList<PackageObserverFeed.Member<Visual>>();
        for(var change:p.changes()) {
            if(change.mask()!=PackageDeltaCodec.RELEASE)return Result.RESYNC;
            releases.add(change.id());
            var old=stream.replica.member(change.id());
            if(old!=null){retired.add(old);uuidOverlay.put(old.metadata().entityUuid(),null);entityOverlay.put(old.metadata().entityId(),null);identityOverlay.put(old.identity(),null);}
        }
        int added=0;
        for(var member:p.baselines()) {
            // An acquisition already retired in this same packet has no renderer transition.
            if(releases.contains(member.index()))continue;
            var visual=member.metadata();var owner=new Owner(p.region(),member.index());
            var u=uuidOverlay.containsKey(visual.entityUuid())?uuidOverlay.get(visual.entityUuid()):uuids.get(visual.entityUuid());
            var e=entityOverlay.containsKey(visual.entityId())?entityOverlay.get(visual.entityId()):entities.get(visual.entityId());
            var i=identityOverlay.containsKey(member.identity())?identityOverlay.get(member.identity()):identities.get(member.identity());
            if(u!=null||e!=null||i!=null)return Result.RESYNC;
            uuidOverlay.put(visual.entityUuid(),owner);entityOverlay.put(visual.entityId(),owner);identityOverlay.put(member.identity(),owner);added++;
        }
        if(uuids.size()-retired.size()+added>capacity)return Result.RESYNC;
        var batch=new PackageObserverFeed.Batch<>(p.stream(),p.sequence(),reset,(p.flags()&ClientboundPackageObserverPacket.COMPLETE)!=0,
                p.baselines(),p.changes(),p.stateTicks());
        var result=stream.replica.apply(batch);
        if(result!=PackageObserverReplica.Result.ACCEPTED)return result==PackageObserverReplica.Result.STALE?Result.STALE:Result.RESYNC;
        streams.put(p.region(),stream);
        uuidOverlay.forEach((id,owner)->{if(owner==null)uuids.remove(id);else uuids.put(id,owner);});
        entityOverlay.forEach((id,owner)->{if(owner==null)entities.remove(id);else entities.put(id,owner);});
        identityOverlay.forEach((id,owner)->{if(owner==null)identities.remove(id);else identities.put(id,owner);});
        for(var member:retired)sink.retire(p.region(),stream.epoch,member);
        for(var member:p.baselines())if(stream.replica.member(member.index())!=null)sink.confirm(p.region(),stream.epoch,member);
        return Result.ACCEPTED;
    }
    public long stream(PackageRegion region){var s=streams.get(region);return s==null||s.closed?0:s.replica.stream();}
    public int size(){return uuids.size();}
}
