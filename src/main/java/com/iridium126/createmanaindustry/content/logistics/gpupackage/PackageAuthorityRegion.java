package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.*;

/** Server-thread protocol and pose commit boundary, independent of Minecraft and GPU code. */
public final class PackageAuthorityRegion {
    public static final int GROUNDED=1,SLEEPING=2,STATE_FLAGS=3,MAX_PENDING=256;
    public record Snapshot(PackageLease.Pose pose,int flags) {
        public Snapshot {Objects.requireNonNull(pose);if((flags&~STATE_FLAGS)!=0)throw new IllegalArgumentException("Package flags");}
    }
    public interface Target {
        PackageLease.Identity identity();
        Snapshot snapshot();
        boolean eligible();
        /** Pose/ground state only; inventory and gameplay operations are outside this protocol. */
        void apply(Snapshot snapshot);
        default void released(Baseline baseline) {}
    }
    public record Baseline(int index,PackageLease.Identity identity,long leaseEpoch,long revision,Snapshot snapshot) {}
    public enum Result { ACCEPTED,STALE,INVALID,FAILED }
    private static final class Entry {
        final int index;
        final Target target;
        final PackageLease lease;
        PackageDeltaCodec.Quantized acknowledged;
        int flags;
        long stateReceipt,motionTick=-1;
        double motionUsed;
        Entry(int index,Target target,PackageLease lease){this.index=index;this.target=target;this.lease=lease;}
    }
    private final PackageRegion region;
    private final UUID owner;
    private final long epoch,revision;
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    private final Map<PackageLease.Identity,Entry> identities=new HashMap<>();
    private final Set<Entry> pending=new LinkedHashSet<>();
    private long receipt,lastSequence=-1;
    private int nextIndex;
    private boolean closed;

    public PackageAuthorityRegion(PackageRegion region,UUID owner,long epoch,long revision,long tick) {
        this.region=Objects.requireNonNull(region);this.owner=Objects.requireNonNull(owner);
        if(epoch<=0 || revision<=0)throw new IllegalArgumentException("Region epoch/revision");
        this.epoch=epoch;this.revision=revision;receipt=tick;
    }
    public Baseline offer(Target target,long tick) {
        if(closed || expired(tick) || pending.size()>=MAX_PENDING || identities.containsKey(target.identity())
                || !target.eligible() || !region.contains(target.snapshot().pose()) || nextIndex==Integer.MAX_VALUE)return null;
        Snapshot snapshot=target.snapshot();
        PackageLease lease=new PackageLease(target.identity(),snapshot.pose(),()->receipt);
        lease.acquire(owner,snapshot.pose(),tick);
        Entry entry=new Entry(nextIndex++,target,lease);entry.flags=snapshot.flags();
        entry.stateReceipt=tick;
        entries.put(entry.index,entry);identities.put(target.identity(),entry);pending.add(entry);
        return baseline(entry);
    }
    /** Original offer pose may have moved. Capture final state only AFTER resource preparation. */
    public Baseline prepared(UUID sender,long candidateEpoch,int index,PackageLease.Identity identity,
                             long leaseEpoch,long offeredRevision,long tick) {
        Entry entry=find(sender,candidateEpoch,index,identity,tick);
        if(entry==null || !entry.target.eligible())return null;
        Snapshot current=entry.target.snapshot();
        if(!region.contains(current.pose()) || entry.lease.freezeBaseline(sender,leaseEpoch,offeredRevision,tick,current.pose())<0)return null;
        entry.flags=current.flags();receipt=tick;return baseline(entry);
    }
    public boolean finalReady(UUID sender,long candidateEpoch,int index,PackageLease.Identity identity,
                              long leaseEpoch,long finalRevision,long tick) {
        Entry entry=find(sender,candidateEpoch,index,identity,tick);
        if(entry==null || !entry.target.eligible() || !entry.lease.physicsPaused())return false;
        Snapshot current=entry.target.snapshot();
        // Quantization must succeed BEFORE switching ownership. A failed final baseline
        // cannot leave a GPU-owned lease without an acknowledged state.
        var quantized=quantize(current);
        if(current.flags()!=entry.flags || !region.contains(current.pose())
                || !entry.lease.ready(sender,leaseEpoch,finalRevision,tick,current.pose()))return false;
        entry.acknowledged=quantized;entry.stateReceipt=tick;receipt=tick;pending.remove(entry);return true;
    }
    /** One heartbeat renews every stationary package without per-package iteration or position writes. */
    public boolean heartbeat(UUID sender,long candidateEpoch,long tick) {
        if(closed || !owner.equals(sender) || candidateEpoch!=epoch || expired(tick))return false;
        receipt=tick;return true;
    }
    public Result delta(UUID sender,long candidateEpoch,long candidateRevision,long sequence,long tick,
                        List<PackageDeltaCodec.Entry> changes,double maximumDisplacement) {
        if(closed || !owner.equals(sender) || candidateEpoch!=epoch || candidateRevision!=revision
                || expired(tick) || sequence<0 || sequence<=lastSequence)return Result.STALE;
        if(changes.isEmpty() || changes.size()>PackageDeltaCodec.MAX_ENTRIES)return Result.INVALID;
        Entry[] targets=new Entry[changes.size()];Snapshot[] next=new Snapshot[changes.size()],previous=new Snapshot[changes.size()];
        PackageDeltaCodec.Quantized[] quantized=new PackageDeltaCodec.Quantized[changes.size()];
        int lastIndex=-1;
        try {
            for(int i=0;i<changes.size();i++) {
                var change=changes.get(i);Entry entry=entries.get(change.id());
                if(change.id()<=lastIndex)return Result.INVALID;
                lastIndex=change.id();
                // Dense, never-reused server baseline indices prove this is an already retired
                // identity. In-flight updates after pickup must not reject unrelated live records.
                if(entry==null){if(change.id()<nextIndex)continue;return Result.INVALID;}
                if(entry.acknowledged==null)return Result.INVALID;
                targets[i]=entry;previous[i]=entry.target.snapshot();
                if(change.mask()==PackageDeltaCodec.RELEASE) {
                    next[i]=new Snapshot(entry.lease.committed(),entry.flags);continue;
                }
                if(!entry.target.eligible())return Result.INVALID;
                var q=PackageDeltaCodec.merge(entry.acknowledged,change);Snapshot pose=decode(q,entry.lease.committed().yaw());
                double distance=distance(entry.lease.committed(),pose.pose());
                if(!region.contains(pose.pose()) || speed(pose.pose())>32
                        || staleMotion(entry,tick) || (entry.motionTick==tick?entry.motionUsed:0)+distance>maximumDisplacement
                        || !entry.lease.canCommit(sender,entry.lease.epoch(),sequence,tick,pose.pose(),maximumDisplacement))return Result.INVALID;
                next[i]=pose;quantized[i]=q;
            }
        }catch(IllegalArgumentException invalid){return Result.INVALID;}
        int applied=0;
        try {
            for(;applied<targets.length;applied++)if(targets[applied]!=null && !previous[applied].equals(next[applied]))targets[applied].target.apply(next[applied]);
        }catch(RuntimeException failed) {
            // Include the failing adapter: it may have thrown after changing its pose.
            for(int i=Math.min(applied,targets.length-1);i>=0;i--)
                try {if(targets[i]!=null)targets[i].target.apply(previous[i]);}catch(RuntimeException ignored){}
            for(Entry entry:targets)if(entry!=null)release(entry);return Result.FAILED;
        }
        for(int i=0;i<targets.length;i++) {
            Entry entry=targets[i];
            if(entry==null)continue;
            if(changes.get(i).mask()==PackageDeltaCodec.RELEASE){release(entry);continue;}
            double moved=distance(entry.lease.committed(),next[i].pose());
            if(!entry.lease.commit(sender,entry.lease.epoch(),sequence,tick,next[i].pose(),maximumDisplacement))
                throw new IllegalStateException("Package commit validation changed on the server thread");
            entry.acknowledged=quantized[i];entry.flags=next[i].flags();
            entry.stateReceipt=tick;entry.motionUsed=(entry.motionTick==tick?entry.motionUsed:0)+moved;entry.motionTick=tick;
        }
        lastSequence=sequence;receipt=tick;return Result.ACCEPTED;
    }
    public void tick(long tick) {
        if(closed)return;
        if(expired(tick)){close();return;}
        // Only bounded acquisitions need individual deadlines; active leases share the region clock.
        for(Iterator<Entry> it=pending.iterator();it.hasNext();) {
            Entry entry=it.next();
            if(!entry.target.eligible() || entry.lease.expired(tick)){it.remove();release(entry);}
        }
    }
    public boolean paused(PackageLease.Identity identity,long tick) {
        Entry entry=identities.get(identity);
        if(entry==null)return false;
        if(expired(tick) || !entry.target.eligible() || entry.lease.expired(tick) || staleMotion(entry,tick)) {release(entry);return false;}
        return entry.lease.physicsPaused();
    }
    public void release(PackageLease.Identity identity){Entry entry=identities.get(identity);if(entry!=null)release(entry);}
    public boolean release(UUID sender,long candidateEpoch,int index,PackageLease.Identity identity,long leaseEpoch,long tick) {
        Entry entry=find(sender,candidateEpoch,index,identity,tick);
        if(entry==null || entry.lease.epoch()!=leaseEpoch)return false;
        release(entry);return true;
    }
    public Baseline baseline(PackageLease.Identity identity) {Entry entry=identities.get(identity);return entry==null?null:baseline(entry);}
    private void release(Entry entry) {
        Baseline previous=baseline(entry);
        if(!identities.remove(entry.target.identity(),entry))return;
        entries.remove(entry.index);pending.remove(entry);
        var state=entry.lease.state();
        var checkpoint=entry.lease.release(entry.target.snapshot().pose());
        if(state==PackageLease.State.GPU_OWNED) {
            Snapshot restore=new Snapshot(checkpoint,entry.flags);
            if(!restore.equals(entry.target.snapshot()))entry.target.apply(restore);
        }
        entry.lease.restored();
        entry.target.released(previous);
    }
    private Entry find(UUID sender,long candidateEpoch,int index,PackageLease.Identity identity,long tick) {
        if(closed || !owner.equals(sender) || candidateEpoch!=epoch || expired(tick))return null;
        Entry entry=entries.get(index);return entry!=null && entry.target.identity().equals(identity)?entry:null;
    }
    private Baseline baseline(Entry entry) {return new Baseline(entry.index,entry.target.identity(),entry.lease.epoch(),
            entry.lease.baselineRevision(),new Snapshot(entry.lease.committed(),entry.flags));}
    private PackageDeltaCodec.Quantized quantize(Snapshot s){return PackageDeltaCodec.quantize(s.pose(),region.originX(),region.originY(),region.originZ(),s.flags());}
    private Snapshot decode(PackageDeltaCodec.Quantized q,float previousYaw) {
        // Keep the original winding/sign near the previous yaw. Create's machine-centering
        // integer division is sensitive to converting a negative yaw into [0,360).
        double targetYaw=Short.toUnsignedInt(q.yaw())*(360.0/65536);
        double delta=(targetYaw-previousYaw+180)%360;if(delta<0)delta+=360;
        float yaw=(float)(previousYaw+delta-180);
        return new Snapshot(new PackageLease.Pose(region.originX()+q.x()/PackageDeltaCodec.POSITION_SCALE,
                region.originY()+q.y()/PackageDeltaCodec.POSITION_SCALE,region.originZ()+q.z()/PackageDeltaCodec.POSITION_SCALE,
                q.vx()/PackageDeltaCodec.VELOCITY_SCALE,q.vy()/PackageDeltaCodec.VELOCITY_SCALE,q.vz()/PackageDeltaCodec.VELOCITY_SCALE,
                yaw),q.flags());
    }
    private static double speed(PackageLease.Pose p){return Math.hypot(Math.hypot(p.vx(),p.vy()),p.vz());}
    private static double distance(PackageLease.Pose a,PackageLease.Pose b){return Math.hypot(Math.hypot(a.x()-b.x(),a.y()-b.y()),a.z()-b.z());}
    private static boolean staleMotion(Entry entry,long tick) {
        if(entry.lease.state()!=PackageLease.State.GPU_OWNED)return false;
        boolean stationary=(entry.flags&(SLEEPING|GROUNDED))!=0 && speed(entry.lease.committed())<1e-5;
        return !stationary && (tick<entry.stateReceipt || tick-entry.stateReceipt>PackageLease.TIMEOUT_TICKS);
    }
    public boolean expired(long tick){return tick<receipt || tick-receipt>PackageLease.TIMEOUT_TICKS;}
    public void close(){if(closed)return;for(Entry entry:new ArrayList<>(entries.values()))release(entry);closed=true;}
    public PackageRegion region(){return region;}
    public UUID owner(){return owner;}
    public long epoch(){return epoch;}
    public long revision(){return revision;}
    public long lastSequence(){return lastSequence;}
    public int size(){return entries.size();}
}
