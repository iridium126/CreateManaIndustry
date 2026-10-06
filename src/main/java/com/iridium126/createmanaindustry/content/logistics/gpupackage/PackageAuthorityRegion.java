package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.util.*;

/** Server-thread protocol and pose commit boundary, independent of Minecraft and GPU code. */
public final class PackageAuthorityRegion {
    public static final int GROUNDED=1,SLEEPING=2,STATE_FLAGS=3,MAX_PENDING=256;
    /** Network range for packages flung by fast Create contraptions. */
    public static final double MAX_PACKAGE_SPEED=2048.0;
    private static final double SIMULATION_STEP_SECONDS=.05, VELOCITY_WINDOW_TOLERANCE_SECONDS=.01,
            MOTION_TOLERANCE=1.25, STEPPED_BASE_TOLERANCE=.25;
    /** Matches dynamic_sweep.glsl: 16 two-block cells per axis, plus motion tolerance. */
    private static final double MAX_SWEEP_AXIS_DISPLACEMENT=32.0, SWEEP_AXIS_TOLERANCE=MOTION_TOLERANCE;
    private static final double MAX_SWEEP_STEP_DISTANCE=Math.sqrt(3.0)*(MAX_SWEEP_AXIS_DISPLACEMENT+SWEEP_AXIS_TOLERANCE);
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
        int displacementX,displacementY,displacementZ;
        int flags;
        long motionTick=-1,simulationStep,activatedTick;
        double motionUsed,committedSpeed,acknowledgedSpeed;
        Entry(int index,Target target,PackageLease lease){this.index=index;this.target=target;this.lease=lease;}
    }
    /** Server-thread scratch shared by sequential delta packets for one world runtime. */
    public static final class DeltaWorkspace {
        private Entry[] targets=new Entry[0];
        private Snapshot[] next=new Snapshot[0],previous=new Snapshot[0];
        private double[] allowances=new double[0],distances=new double[0],speeds=new double[0];
        private PackageDeltaCodec.Quantized[] quantized=new PackageDeltaCodec.Quantized[0];
        private void ensure(int count) {
            if(targets.length>=count)return;
            int capacity=Math.max(count,Math.max(16,targets.length*2));
            targets=Arrays.copyOf(targets,capacity);next=Arrays.copyOf(next,capacity);previous=Arrays.copyOf(previous,capacity);
            allowances=Arrays.copyOf(allowances,capacity);distances=Arrays.copyOf(distances,capacity);speeds=Arrays.copyOf(speeds,capacity);
            quantized=Arrays.copyOf(quantized,capacity);
        }
        private void clear(int count) {
            Arrays.fill(targets,0,count,null);Arrays.fill(next,0,count,null);Arrays.fill(previous,0,count,null);
            Arrays.fill(quantized,0,count,null);
        }
    }
    private final PackageRegion region;
    private final UUID owner;
    private final long epoch,revision;
    private final Map<Integer,Entry> entries=new LinkedHashMap<>();
    private final Map<PackageLease.Identity,Entry> identities=new HashMap<>();
    private final Set<Entry> pending=new LinkedHashSet<>();
    private final PackageObserverFeed<Target> observers=new PackageObserverFeed<>();
    private long receipt,lastSequence=-1,stepOrigin=-1,stepOriginTick;
    private int nextIndex;
    private int frozenPending;
    private boolean closed;
    private String lastDeltaRejection="none";
    private final java.util.function.DoubleSupplier tickRate;
    private int historyTicks=20;
    public int historyTicks(){return historyTicks=Math.max(historyTicks,PackageTickTiming.historyTicks(tickRate.getAsDouble()));}

    public PackageAuthorityRegion(PackageRegion region,UUID owner,long epoch,long revision,long tick) {
        this(region,owner,epoch,revision,tick,PackageTickTiming.DEFAULT_RATE);
    }
    public PackageAuthorityRegion(PackageRegion region,UUID owner,long epoch,long revision,long tick,java.util.function.DoubleSupplier tickRate) {
        this.region=Objects.requireNonNull(region);this.owner=Objects.requireNonNull(owner);
        if(epoch<=0 || revision<=0)throw new IllegalArgumentException("Region epoch/revision");
        this.epoch=epoch;this.revision=revision;receipt=tick;
        this.tickRate=Objects.requireNonNull(tickRate);
    }
    public Baseline offer(Target target,long tick) {
        if(closed || expired(tick) || pending.size()>=MAX_PENDING || identities.containsKey(target.identity())
                || !target.eligible() || !region.contains(target.snapshot().pose()) || nextIndex==Integer.MAX_VALUE)return null;
        Snapshot snapshot=target.snapshot();
        PackageLease lease=new PackageLease(target.identity(),snapshot.pose(),()->receipt,
                PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS,PackageLease.FINAL_BASELINE_TIMEOUT_TICKS,tickRate);
        lease.acquire(owner,snapshot.pose(),tick);
        Entry entry=new Entry(nextIndex++,target,lease);entry.flags=snapshot.flags();
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
        frozenPending++;entry.flags=current.flags();receipt=tick;return baseline(entry);
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
        entry.acknowledged=quantized;entry.committedSpeed=speed(current.pose());
        entry.acknowledgedSpeed=speed(decode(quantized,current.pose().yaw()).pose());
        entry.activatedTick=tick;receipt=tick;pending.remove(entry);frozenPending--;
        observers.activate(new PackageObserverFeed.Member<>(entry.index,entry.target.identity(),entry.lease.epoch(),
                entry.lease.baselineRevision(),quantized,entry.target,tick));return true;
    }
    /** Visible publication is a later client confirmation, never inferred from FINAL_READY.
     * The world adapter may suppress native motion only for this exact live GPU lease. */
    public boolean visibleReady(UUID sender,long candidateEpoch,int index,PackageLease.Identity identity,
                                long leaseEpoch,long finalRevision,long tick) {
        Entry entry=find(sender,candidateEpoch,index,identity,tick);
        return entry!=null&&entry.lease.epoch()==leaseEpoch&&entry.lease.baselineRevision()==finalRevision
                &&simulated(identity,tick);
    }
    /** One heartbeat renews every stationary package without per-package iteration or position writes. */
    public boolean heartbeat(UUID sender,long candidateEpoch,long tick) {
        if(closed || !owner.equals(sender) || candidateEpoch!=epoch || expired(tick))return false;
        receipt=tick;return true;
    }
    public Result delta(UUID sender,long candidateEpoch,long candidateRevision,long sequence,long tick,
                        List<PackageDeltaCodec.Entry> changes,double maximumDisplacement) {
        return delta(sender,candidateEpoch,candidateRevision,sequence,tick,changes,maximumDisplacement,0,0);
    }
    public Result deltaRelative(UUID sender,long candidateEpoch,long candidateRevision,long sequence,long tick,
                        List<PackageDeltaCodec.Entry> changes,double maximumDisplacement) {
        return delta(sender,candidateEpoch,candidateRevision,sequence,tick,changes,maximumDisplacement,1,0);
    }
    public Result deltaPredicted(UUID sender,long candidateEpoch,long candidateRevision,long sequence,long tick,
                        List<PackageDeltaCodec.Entry> changes,double maximumDisplacement) {
        return delta(sender,candidateEpoch,candidateRevision,sequence,tick,changes,maximumDisplacement,2,0);
    }
    public Result deltaStepped(UUID sender,long epoch,long revision,long sequence,long tick,List<PackageDeltaCodec.Entry> changes,double maximumDisplacement,int mode,long simulationStep){
        return deltaStepped(sender,epoch,revision,sequence,tick,changes,maximumDisplacement,mode,simulationStep,new DeltaWorkspace());
    }
    public Result deltaStepped(UUID sender,long epoch,long revision,long sequence,long tick,List<PackageDeltaCodec.Entry> changes,double maximumDisplacement,int mode,long simulationStep,DeltaWorkspace workspace){
        if(simulationStep<1||mode<0||mode>2){lastDeltaRejection="invalid step/mode";return Result.INVALID;}
        return delta(sender,epoch,revision,sequence,tick,changes,maximumDisplacement,mode,simulationStep,workspace);
    }
    private Result delta(UUID sender,long candidateEpoch,long candidateRevision,long sequence,long tick,
                        List<PackageDeltaCodec.Entry> changes,double maximumDisplacement,int positionMode,long simulationStep) {
        return delta(sender,candidateEpoch,candidateRevision,sequence,tick,changes,maximumDisplacement,positionMode,simulationStep,new DeltaWorkspace());
    }
    private Result delta(UUID sender,long candidateEpoch,long candidateRevision,long sequence,long tick,
                        List<PackageDeltaCodec.Entry> changes,double maximumDisplacement,int positionMode,long simulationStep,DeltaWorkspace workspace) {
        if(closed || !owner.equals(sender) || candidateEpoch!=epoch || candidateRevision!=revision
                || expired(tick) || sequence<0 || sequence<=lastSequence)return Result.STALE;
        lastDeltaRejection="none";
        if(simulationStep>0&&stepOrigin>=0&&(simulationStep<stepOrigin||simulationStep-stepOrigin>tick-stepOriginTick+historyTicks())){lastDeltaRejection="simulation step outside retained history";return Result.INVALID;}
        if(changes.isEmpty() || changes.size()>PackageDeltaCodec.MAX_ENTRIES){lastDeltaRejection="empty/oversized delta";return Result.INVALID;}
        Objects.requireNonNull(workspace).ensure(changes.size());
        Entry[] targets=workspace.targets;Snapshot[] next=workspace.next,previous=workspace.previous;
        double[] allowances=workspace.allowances,distances=workspace.distances,speeds=workspace.speeds;
        PackageDeltaCodec.Quantized[] quantized=workspace.quantized;
        try {
            int lastIndex=-1;
            try {
                for(int i=0;i<changes.size();i++) {
                    var change=changes.get(i);Entry entry=entries.get(change.id());
                    if(change.id()<=lastIndex){lastDeltaRejection="unsorted delta indices";return Result.INVALID;}
                    lastIndex=change.id();
                    // Dense, never-reused server baseline indices prove this is an already retired
                    // identity. In-flight updates after pickup must not reject unrelated live records.
                    if(entry==null){if(change.id()<nextIndex)continue;lastDeltaRejection="unknown future baseline index";return Result.INVALID;}
                    if(entry.acknowledged==null){lastDeltaRejection="baseline not acknowledged";return Result.INVALID;}
                    targets[i]=entry;previous[i]=entry.target.snapshot();
                    if(change.mask()==PackageDeltaCodec.RELEASE) {
                        next[i]=new Snapshot(entry.lease.committed(),entry.flags);continue;
                    }
                    if(!entry.target.eligible()){lastDeltaRejection="target no longer eligible";return Result.INVALID;}
                    var q=positionMode==2?PackageDeltaCodec.mergePredictedPosition(entry.acknowledged,change,entry.displacementX,entry.displacementY,entry.displacementZ):
                            positionMode==1?PackageDeltaCodec.mergeRelativePosition(entry.acknowledged,change):PackageDeltaCodec.merge(entry.acknowledged,change);
                    Snapshot pose=decode(q,entry.lease.committed().yaw());
                    double distance=distance(entry.lease.committed(),pose.pose());distances[i]=distance;
                    long steps=motionSteps(entry,tick,simulationStep);
                    if(steps<0){lastDeltaRejection="simulation step moved backwards";return Result.INVALID;}
                    // Keep the original allowance for ordinary motion, with confirmed 50 ms
                    // velocity steps covering legitimate fast Create contraption impacts.
                    double candidateSpeed=(change.mask()&PackageDeltaCodec.VELOCITY)!=0
                            ?speed(pose.pose()):entry.acknowledgedSpeed;
                    speeds[i]=candidateSpeed;
                    double speed=Math.max(entry.committedSpeed,candidateSpeed);
                    double minimumSteppedAllowance=maximumDisplacement+STEPPED_BASE_TOLERANCE;
                    double velocityAllowance=speed*(SIMULATION_STEP_SECONDS+VELOCITY_WINDOW_TOLERANCE_SECONDS)+MOTION_TOLERANCE;
                    double perStepAllowance=Math.max(Math.max(minimumSteppedAllowance,velocityAllowance),MAX_SWEEP_STEP_DISTANCE);
                    double allowance=perStepAllowance*steps;allowances[i]=allowance;
                    double axisAllowance=(MAX_SWEEP_AXIS_DISPLACEMENT+SWEEP_AXIS_TOLERANCE)*steps;
                    double dx=Math.abs(pose.pose().x()-entry.lease.committed().x());
                    double dy=Math.abs(pose.pose().y()-entry.lease.committed().y());
                    double dz=Math.abs(pose.pose().z()-entry.lease.committed().z());
                    boolean outsideSweep=dx>axisAllowance||dy>axisAllowance||dz>axisAllowance;
                    // A delayed GPU confirmation can change flags/velocity while the physical
                    // step stays unchanged. It receives no additional position allowance.
                    if(candidateSpeed>MAX_PACKAGE_SPEED){lastDeltaRejection="package speed exceeds negotiated range: "+candidateSpeed;return Result.INVALID;}
                    if((simulationStep==0&&(entry.motionTick==tick?entry.motionUsed:0)+distance>maximumDisplacement)
                            ||outsideSweep||!entry.lease.canCommitMeasured(sender,entry.lease.epoch(),sequence,tick,pose.pose(),allowance,distance)){
                        lastDeltaRejection="displacement exceeds swept/velocity allowance: distance="+distance+", allowance="+allowance+", axisAllowance="+axisAllowance+", speed="+speed+", steps="+steps+", minimum="+minimumSteppedAllowance+", velocityAllowance="+velocityAllowance+", simulationStep="+simulationStep;
                        return Result.INVALID;
                    }
                    next[i]=pose;quantized[i]=q;
                }
            }catch(IllegalArgumentException | ArithmeticException invalid){lastDeltaRejection=invalid.getMessage()==null?"invalid delta value":invalid.getMessage();return Result.INVALID;}
            int applied=0;
            try {
                for(;applied<changes.size();applied++)if(targets[applied]!=null && !previous[applied].equals(next[applied]))targets[applied].target.apply(next[applied]);
            }catch(RuntimeException failed) {
                // Include the failing adapter: it may have thrown after changing its pose.
                for(int i=Math.min(applied,changes.size()-1);i>=0;i--)
                    try {if(targets[i]!=null)targets[i].target.apply(previous[i]);}catch(RuntimeException ignored){}
                for(int i=0;i<changes.size();i++)if(targets[i]!=null)release(targets[i]);return Result.FAILED;
            }
            for(int i=0;i<changes.size();i++) {
                Entry entry=targets[i];
                if(entry==null)continue;
                if(changes.get(i).mask()==PackageDeltaCodec.RELEASE){release(entry);continue;}
                double moved=distances[i];
                if(!entry.lease.commitMeasured(sender,entry.lease.epoch(),sequence,tick,next[i].pose(),allowances[i],moved))
                    throw new IllegalStateException("Package commit validation changed on the server thread");
                if((changes.get(i).mask()&PackageDeltaCodec.POSITION)!=0) {
                    entry.displacementX=quantized[i].x()-entry.acknowledged.x();
                    entry.displacementY=quantized[i].y()-entry.acknowledged.y();
                    entry.displacementZ=quantized[i].z()-entry.acknowledged.z();
                }
                entry.acknowledged=quantized[i];entry.flags=next[i].flags();
                entry.committedSpeed=entry.acknowledgedSpeed=speeds[i];
                entry.motionUsed=(entry.motionTick==tick?entry.motionUsed:0)+moved;entry.motionTick=tick;if(simulationStep>0)entry.simulationStep=simulationStep;
                // Commit the bounded crossing before retiring this region's authority.
                // Discovery can now acquire the same record in its destination region.
                // Releasing an uncommitted crossing would replay the old side forever.
                if(!region.contains(next[i].pose()))release(entry);
            }
            // Publish resolved server poses directly; relative modes need no wrapper entries
            // and observers need no second per-record merge.
            observers.acceptedResolved(changes,quantized,tick);
            if(simulationStep>0&&stepOrigin<0){stepOrigin=simulationStep;stepOriginTick=tick;}
            lastSequence=sequence;receipt=tick;return Result.ACCEPTED;
        }finally {workspace.clear(changes.size());}
    }
    public void tick(long tick) {
        if(closed)return;
        if(expired(tick)){close();return;}
        // Only bounded acquisitions need individual deadlines; active leases share the region clock.
        for(Iterator<Entry> it=pending.iterator();it.hasNext();) {
            Entry entry=it.next();
            if(!entry.target.eligible() || entry.lease.expired(tick)){
                if(entry.lease.physicsPaused())frozenPending--;
                it.remove();release(entry);
            }
        }
    }
    public boolean paused(PackageLease.Identity identity,long tick) {
        Entry entry=identities.get(identity);
        if(closed||entry==null)return false;
        // Position deltas are sparse: quantized motion can remain unchanged for many ticks.
        // The region heartbeat is the liveness signal; a quiet package is not a failed lease.
        if(expired(tick) || !entry.target.eligible() || entry.lease.expired(tick)) {release(entry);return false;}
        return entry.lease.physicsPaused();
    }
    /** A frozen FINAL handshake is not a simulated contact body. Stale or newly ineligible
     * leases are released before suppressing any native pair response. */
    public boolean simulated(PackageLease.Identity identity,long tick) {
        Entry entry=identities.get(identity);
        return entry!=null&&entry.lease.state()==PackageLease.State.GPU_OWNED&&paused(identity,tick);
    }
    public boolean coSimulates(PackageLease.Identity identity,PackageAuthorityRegion other,PackageLease.Identity otherIdentity,long tick) {
        return other!=null&&owner.equals(other.owner)&&simulated(identity,tick)&&other.simulated(otherIdentity,tick);
    }
    public void release(PackageLease.Identity identity){Entry entry=identities.get(identity);if(entry!=null)release(entry);}
    public boolean release(UUID sender,long candidateEpoch,int index,PackageLease.Identity identity,long leaseEpoch,long tick) {
        Entry entry=find(sender,candidateEpoch,index,identity,tick);
        if(entry==null || entry.lease.epoch()!=leaseEpoch)return false;
        release(entry);return true;
    }
    public Baseline baseline(PackageLease.Identity identity) {Entry entry=identities.get(identity);return entry==null?null:baseline(entry);}
    /** Diagnostic timestamp of the latest confirmed pose; absent identities have no step. */
    public long confirmedSimulationStep(PackageLease.Identity identity){Entry entry=identities.get(identity);return entry==null?-1:entry.simulationStep;}
    /** A contact belongs to a past/future GPU step, not necessarily the latest pose
     * packet. Bound its reach by the same retained steps and per-axis CCD cap. */
    public boolean environmentReachable(PackageLease.Identity identity,long step,long tick,PackageLease.Pose contact){
        Entry entry=identities.get(identity);if(entry==null||step<1||!simulated(identity,tick))return false;
        if(stepOrigin>=0&&step-stepOrigin>tick-stepOriginTick+historyTicks())return false;
        long steps;
        if(entry.simulationStep==0)steps=motionSteps(entry,tick,step);
        else {
            long lag=step>=entry.simulationStep?step-entry.simulationStep:entry.simulationStep-step;
            if(lag>historyTicks()) {
                // Sparse poses and the reliable environment journal have independent
                // delivery. The journal is bounded by sample count, not elapsed steps:
                // an old unacknowledged contact can outlive this pose-history window.
                // In either direction grant only one sweep of spatial reach, rather
                // than expiring a nearby contact or multiplying reach by a long delay.
                // The manager still validates serials, lifecycle and event intervals.
                steps=1;
            }else steps=lag+1; // Contact may occur before the endpoint of its own step.
        }
        double allowance=(MAX_SWEEP_AXIS_DISPLACEMENT+SWEEP_AXIS_TOLERANCE)*steps+.0021;
        var pose=entry.lease.committed();
        return Math.abs(contact.x()-pose.x())<=allowance&&Math.abs(contact.y()-pose.y())<=allowance&&Math.abs(contact.z()-pose.z())<=allowance;
    }
    private void release(Entry entry) {
        Baseline previous=baseline(entry);
        if(!identities.remove(entry.target.identity(),entry))return;
        entries.remove(entry.index);
        observers.retire(entry.index,entry.target.identity());
        if(pending.remove(entry) && entry.lease.physicsPaused())frozenPending--;
        var state=entry.lease.state();
        var checkpoint=entry.lease.release(entry.target.snapshot().pose());
        if(state==PackageLease.State.GPU_OWNED) {
            Snapshot restore=new Snapshot(checkpoint,entry.flags);
            if(!restore.equals(entry.target.snapshot()))entry.target.apply(restore);
        }
        entry.lease.finishRelease();
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
    private long motionSteps(Entry entry,long tick,long step){
        return step==0?1:entry.simulationStep==0?
                Math.clamp(tick-entry.activatedTick+PackageTickTiming.stepsPerFrame(tickRate.getAsDouble()),1,historyTicks()):
                Math.min(historyTicks(),step-entry.simulationStep);
    }
    private static double distance(PackageLease.Pose a,PackageLease.Pose b){return Math.hypot(Math.hypot(a.x()-b.x(),a.y()-b.y()),a.z()-b.z());}
    public boolean expired(long tick){
        long allowed=!entries.isEmpty() && entries.size()==pending.size() && frozenPending==0
                ?PackageLease.ACQUISITION_TIMEOUT_TICKS:PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS;
        return tick<receipt || tick-receipt>PackageTickTiming.deadlineTicks(allowed,tickRate.getAsDouble());
    }
    /** Constant-time ownership revocation; world adapters drain notifications under their tick budget. */
    public void beginClose(){closed=true;}
    public boolean closed(){return closed;}
    public int drainClose(int budget){
        if(!closed||budget<0)throw new IllegalStateException("Region is not closing");int retired=0;
        while(retired<budget&&!entries.isEmpty()){release(entries.values().iterator().next());retired++;}return retired;
    }
    public void close(){if(closed)return;for(Entry entry:new ArrayList<>(entries.values()))release(entry);closed=true;}
    public PackageRegion region(){return region;}
    public UUID owner(){return owner;}
    public long epoch(){return epoch;}
    public long revision(){return revision;}
    public long lastSequence(){return lastSequence;}
    public String lastDeltaRejection(){return lastDeltaRejection;}
    public int size(){return entries.size();}
    public int simulatedCount(){return entries.size()-pending.size();}
    public PackageObserverFeed<Target> observers(){return observers;}
}
