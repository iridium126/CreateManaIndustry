package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.BufferUtils;

/** Render-thread acquisition adapter. Owns only its admission ring; physics, meshes and delta
 * resources belong to the world runtime. Work queues contain transitions, never the active population. */
public final class PackageFreeAcquisitionGpu implements AutoCloseable {
    public interface Transport {
        void control(int action,ClientboundPackagePacket checkpoint);
        /** Visible admission and ACTIVE now refer to the same committed pool generation. */
        void activated(ClientboundPackagePacket offer,ClientboundPackagePacket active,int candidate,int slotPlusOne);
        /** The retired candidate has disappeared from a successfully committed pool. */
        void released(ClientboundPackagePacket offer,PackageAuthorityRegion.Baseline baseline);
        /** Visual handback before releasing the render claim; NONE allows native recovery.
         * Completed cached GPU state only, no fence wait, network ACK or gameplay transaction. */
        default void restore(ClientboundPackagePacket offer,PackagePoseQueryGpu.Result pose) {}
    }
    private enum Phase { RESOURCES, OFFER_ADMISSION, PREPARED, FINAL_UPLOAD, FINAL_ADMISSION,
        FINAL_READY, ACTIVATE, VISIBLE_ADMISSION, ACTIVE, RETIRE, RETIRED_ADMISSION, RELEASED }
    private static final class Entry {
        final ClientboundPackagePacket offer;
        ClientboundPackagePacket checkpoint,active;
        Phase phase=Phase.RESOURCES,capturedPhase;
        int body=-1,candidate=-1,delta=-1;
        boolean queued,captured,terminal,owned;
        Entry(ClientboundPackagePacket offer){this.offer=checkpoint=offer;}
    }
    private static final int MAX_TRANSITIONS=PackageAuthorityRegion.MAX_PENDING;
    private final PackageRegion region;
    private final long epoch,revision;
    private final double ox,oy,oz;
    private final PackageMixedPhysicsGpu physics;
    private final PackagePoolGpu pool;
    private final PackageDeltaGpu detector;
    private PackageDeltaChannel channel;
    private PackageFreeCheckpointGpu checkpoints;
    private final Map<ResourceLocation,PackageModelCache.Style> styles;
    private final Transport transport;
    private final ToIntFunction<ClientboundPackagePacket> light;
    private final PackageAdmissionTracker admissions;
    private final Map<Integer,Entry> entries=new HashMap<>(),byCandidate=new HashMap<>();
    private final Set<PackageLease.Identity> reservedIdentities=new HashSet<>();
    private final ArrayDeque<Entry> work=new ArrayDeque<>();
    private final LinkedHashSet<Entry> awaiting=new LinkedHashSet<>();
    private final ArrayList<Entry> captureEntries=new ArrayList<>(MAX_TRANSITIONS);
    private final ArrayList<PackageAdmissionTracker.Expected> captureExpected=new ArrayList<>(MAX_TRANSITIONS);
    private final ByteBuffer body=BufferUtils.createByteBuffer(64),emptyChain=BufferUtils.createByteBuffer(64),
            metadata=BufferUtils.createByteBuffer(80),delta=BufferUtils.createByteBuffer(32),baseline=BufferUtils.createByteBuffer(32);
    private final Thread owner=Thread.currentThread();
    private int transitions,activeCount;
    private long nextCapture,lastCommit=-1,lastCaptureVersion=-1,lastCaptureConfirmation=-1;
    private boolean closed;

    public PackageFreeAcquisitionGpu(PackageRegion region,long epoch,long revision,double ox,double oy,double oz,
                                    PackageMixedPhysicsGpu physics,PackagePoolGpu pool,PackageDeltaGpu detector,
                                    Map<ResourceLocation,PackageModelCache.Style> styles,ToIntFunction<ClientboundPackagePacket> light,Transport transport) {
        this.region=java.util.Objects.requireNonNull(region);this.physics=java.util.Objects.requireNonNull(physics);
        this.pool=java.util.Objects.requireNonNull(pool);this.detector=java.util.Objects.requireNonNull(detector);
        this.styles=Map.copyOf(styles);this.transport=java.util.Objects.requireNonNull(transport);
        this.light=java.util.Objects.requireNonNull(light);
        if(epoch<=0 || revision<=0 || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz))
            throw new IllegalArgumentException("Package acquisition envelope/origin");
        this.epoch=epoch;this.revision=revision;this.ox=ox;this.oy=oy;this.oz=oz;
        admissions=new PackageAdmissionTracker(MAX_TRANSITIONS,epoch);
    }
    public PackageRegion region(){return region;}
    public long epoch(){return epoch;}
    public long revision(){return revision;}
    public int activeCount(){open();return activeCount;}
    /** Resolve one GPU result through this confirmed lease; a pool index alone never selects
     * a gameplay entity. Terminal/retiring/hidden/observer results cannot enter native input. */
    public ClientboundPackagePacket activeOffer(PackagePoseQueryGpu.Result result) {
        open();if(result==null||!result.present()||result.chain()||result.flags()!=0||result.state()<0)return null;
        var entry=byCandidate.get(result.candidate());
        if(entry==null||entry.terminal||entry.phase!=Phase.ACTIVE||entry.body!=result.body())return null;
        var identity=entry.offer.baseline().identity();
        return identity.id()==result.id()&&identity.generation()==result.generation()
                &&result.halfHeight()==entry.offer.height()*.5f?entry.offer:null;
    }
    public int pendingCount(){open();return transitions;}
    public void emergencyCheckpoints(PackageFreeCheckpointGpu checkpoints){open();if(this.checkpoints!=null||!entries.isEmpty())throw new IllegalStateException("Free checkpoint attachment");this.checkpoints=java.util.Objects.requireNonNull(checkpoints);}
    /** Attach before accepting any offers: detector and CPU journal must share candidate order. */
    public void attachChannel(PackageDeltaChannel channel) {
        open();
        if(this.channel!=null || !entries.isEmpty() || detector.metadataCount()!=0 || !channel.uses(detector)
                || channel.epoch()!=epoch || channel.revision()!=revision)
            throw new IllegalArgumentException("Package acquisition channel attachment");
        this.channel=channel;
    }
    /** Network dispatch only queues mutations. It does not upload or query the world. */
    public boolean receive(ClientboundPackagePacket packet) {
        open();
        if(!region.equals(packet.region()) || packet.epoch()!=epoch || packet.regionRevision()!=revision)return false;
        if(packet.action()==ClientboundPackagePacket.ACK)return false;
        var b=packet.baseline();Entry entry=entries.get(b.index());
        if(packet.action()==ClientboundPackagePacket.OFFER) {
            if(entry!=null) {
                if(!entry.offer.equals(packet))transport.control(ServerboundPackagePacket.RELEASE,packet);
                return true;
            }
            if(transitions>=MAX_TRANSITIONS || !styles.containsKey(packet.model()) || !reservedIdentities.add(b.identity())) {
                transport.control(ServerboundPackagePacket.RELEASE,packet);return true;
            }
            entry=new Entry(packet);entries.put(b.index(),entry);transitions++;queue(entry);return true;
        }
        if(entry==null || entry.terminal || !sameIdentity(entry,packet))return true;
        if(packet.action()==ClientboundPackagePacket.RELEASED) {
            if(b.leaseEpoch()>=entry.checkpoint.baseline().leaseEpoch() && b.revision()>=entry.checkpoint.baseline().revision())
                release(entry,b,false);
            return true;
        }
        if(b.leaseEpoch()!=entry.offer.baseline().leaseEpoch())return true;
        if(packet.action()==ClientboundPackagePacket.FINAL_BASELINE) {
            if(entry.phase==Phase.PREPARED && b.revision()>entry.offer.baseline().revision()) {
                entry.checkpoint=packet;entry.phase=Phase.FINAL_UPLOAD;queue(entry);
            } // Duplicate/late checkpoints never rewind a confirmed transition.
            return true;
        }
        if(packet.action()==ClientboundPackagePacket.ACTIVE && entry.phase==Phase.FINAL_READY
                && b.revision()==entry.checkpoint.baseline().revision()) {
            entry.active=packet;entry.phase=Phase.ACTIVATE;queue(entry);
        }
        return true;
    }
    private static boolean sameIdentity(Entry entry,ClientboundPackagePacket packet) {
        return entry.offer.dimension().equals(packet.dimension())
                && entry.offer.baseline().identity().equals(packet.baseline().identity());
    }
    /** Lifecycle callback for an exact terminal delta ACK; indices alone cannot retire another identity. */
    public void serverReleased(int localId,long id,long generation) {
        open();Entry entry=entries.get(localId);
        if(entry!=null && !entry.terminal && entry.offer.baseline().identity().id()==id
                && entry.offer.baseline().identity().generation()==generation)
            release(entry,entry.checkpoint.baseline(),false);
    }
    public void requestRelease(int localId) {
        open();Entry entry=entries.get(localId);if(entry!=null && !entry.terminal)release(entry,entry.checkpoint.baseline(),true);
    }
    private void release(Entry entry,PackageAuthorityRegion.Baseline baseline,boolean notify) {
        if(entry.phase==Phase.RETIRE || entry.phase==Phase.RETIRED_ADMISSION)return;
        if(notify)transport.control(ServerboundPackagePacket.RELEASE,entry.checkpoint);
        // Keep the exact terminal baseline for the eventual render handback callback.
        entry.checkpoint=new ClientboundPackagePacket(ClientboundPackagePacket.RELEASED,entry.offer.dimension(),region,epoch,
                revision,0,baseline,0,null,null,0,0);
        if(entry.body<0){finish(entry);transport.released(entry.offer,baseline);return;}
        if(entry.phase==Phase.ACTIVE){activeCount--;transitions++;}
        entry.phase=Phase.RETIRE;queue(entry);
    }
    private void queue(Entry entry){if(!entry.queued){entry.queued=true;work.addLast(entry);}}
    /** Called inside the engine GL boundary. Coverage predicate reads already prepared resources;
     * it must not scan sections, bake shapes or wait for workers/fences. */
    public void pump(int maxTransitions,BiPredicate<ClientboundPackagePacket,ClientboundPackagePacket> covered) {
        open();if(maxTransitions<0 || maxTransitions>MAX_TRANSITIONS)throw new IllegalArgumentException("Acquisition work budget");
        if(channel==null)throw new IllegalStateException("Package acquisition has no identity journal");
        admissions.poll(this::confirmed);
        int n=Math.min(maxTransitions,work.size());
        for(int i=0;i<n;i++) {
            Entry entry=work.removeFirst();entry.queued=false;if(entry.terminal)continue;
            switch(entry.phase) {
                case RESOURCES -> {
                    if(!covered.test(entry.offer,entry.checkpoint)){queue(entry);continue;}
                    if(physics.freeCount()>=physics.freeCapacity() || physics.freeCount()+physics.chainCount()+physics.observerCount()>=131072
                            || pool.metadataCount()>=Math.min(pool.capacity(),131072) || detector.metadataCount()>=detector.capacity()) {
                        release(entry,entry.checkpoint.baseline(),true);continue;
                    }
                    int local=physics.freeCount();
                    try{encode(entry,local);}catch(IllegalArgumentException unsupported){release(entry,entry.checkpoint.baseline(),true);continue;}
                    entry.body=local;entry.candidate=pool.metadataCount();entry.delta=detector.metadataCount();
                    physics.appendFree(body,emptyChain,1);pool.appendMetadata(metadata,1);channel.append(delta,baseline,1);
                    byCandidate.put(entry.candidate,entry);entry.phase=Phase.OFFER_ADMISSION;awaiting.add(entry);
                }
                case FINAL_UPLOAD -> {
                    if(!covered.test(entry.offer,entry.checkpoint)){release(entry,entry.checkpoint.baseline(),true);continue;}
                    try{encode(entry,entry.body);}catch(IllegalArgumentException unsupported){release(entry,entry.checkpoint.baseline(),true);continue;}
                    physics.replaceFree(entry.body,body,emptyChain,1);
                    detector.rebasePrepared(entry.delta,baseline);entry.phase=Phase.FINAL_ADMISSION;awaiting.add(entry);
                }
                case ACTIVATE -> {
                    if(!covered.test(entry.offer,entry.checkpoint)){release(entry,entry.checkpoint.baseline(),true);continue;}
                    physics.activatePreparedFree(entry.body);detector.activate(entry.delta);pool.setHidden(entry.candidate,false);
                    entry.phase=Phase.VISIBLE_ADMISSION;awaiting.add(entry);
                }
                case RETIRE -> {
                    physics.retireFree(entry.body);pool.setHidden(entry.candidate,true);
                    entry.phase=Phase.RETIRED_ADMISSION;awaiting.add(entry);
                }
                default -> throw new IllegalStateException("Unexpected queued acquisition phase "+entry.phase);
            }
        }
    }
    private void encode(Entry entry,int bodyIndex) {
        PackageFreeUpload.prepared(entry.offer,entry.checkpoint,styles.get(entry.offer.model()),bodyIndex,light.applyAsInt(entry.offer),ox,oy,oz,
                body,metadata,delta,baseline);
    }
    /** Only call AFTER the common pool and all package commands successfully commit. */
    public void committed(long generation) {
        open();if(generation<0 || generation<lastCommit)throw new IllegalArgumentException("Package commit generation");
        if(generation==lastCommit)return;lastCommit=generation;
        if(!pool.sourceMatches(physics.bodyBuffer(),physics.chainBuffer(),physics.historyBuffer(),physics.bodyCount(),
                (float)ox,(float)oy,(float)oz))throw new IllegalStateException("Package admission uses a different physics publication");
        if(awaiting.isEmpty())return;
        var batch=captureEntries;var expected=captureExpected;batch.clear();expected.clear();
        for(Entry entry:awaiting)if(!entry.captured) {
            if(!batch.isEmpty() && entry.candidate!=batch.getLast().candidate+1) {
                if(!submit(batch,expected))return;batch.clear();expected.clear();
            }
            batch.add(entry);int flags=entry.phase==Phase.VISIBLE_ADMISSION?0:PackagePoolGpu.HIDDEN;
            var identity=entry.offer.baseline().identity();expected.add(new PackageAdmissionTracker.Expected(identity.id(),identity.generation(),flags));
            if(batch.size()==MAX_TRANSITIONS){if(!submit(batch,expected))return;batch.clear();expected.clear();}
        }
        if(!batch.isEmpty())submit(batch,expected);
    }
    /** Network dirty capture is tied to the same successful pool submission as the draw. */
    public boolean captureCommitted(PackageDeltaChannel channel,long generation) {
        open();if(channel!=this.channel || generation!=lastCommit || channel.epoch()!=epoch || channel.revision()!=revision)
            throw new IllegalArgumentException("Package delta/admission generation");
        // Observer interpolation may publish at display cadence without changing any authority
        // body. It must not schedule extra authority detection/readback work between steps.
        long version=physics.freePublicationVersion();
        long confirmation=channel.confirmationVersion();
        if(activeCount==0 || (version==lastCaptureVersion && confirmation==lastCaptureConfirmation))return false;
        boolean captured=channel.capture(physics.bodyBuffer(),physics.bodyCount(),
                (float)(ox-region.originX()),(float)(oy-region.originY()),(float)(oz-region.originZ()));
        if(captured){lastCaptureVersion=version;lastCaptureConfirmation=confirmation;}return captured;
    }
    private boolean submit(ArrayList<Entry> batch,ArrayList<PackageAdmissionTracker.Expected> expected) {
        if(!admissions.submit(pool,batch.getFirst().candidate,expected,nextCapture++))return false;
        for(Entry entry:batch){entry.captured=true;entry.capturedPhase=entry.phase;}return true;
    }
    private void confirmed(PackageAdmissionTracker.Outcome result) {
        Entry entry=byCandidate.get(result.candidate());
        if(entry==null || !entry.captured)throw new IllegalStateException("Unknown acquisition admission");
        entry.captured=false;
        if(entry.terminal || entry.phase!=entry.capturedPhase)return; // Superseded by a terminal notice.
        awaiting.remove(entry);
        if(entry.phase==Phase.RETIRED_ADMISSION) {
            if(result.accepted())throw new IllegalStateException("Retired package still admitted");
            restoreOwned(entry);
            finish(entry);transport.released(entry.offer,entry.checkpoint.baseline());return;
        }
        if(!result.accepted()){release(entry,entry.checkpoint.baseline(),true);return;}
        switch(entry.phase) {
            case OFFER_ADMISSION -> {entry.phase=Phase.PREPARED;transport.control(ServerboundPackagePacket.PREPARED,entry.checkpoint);}
            case FINAL_ADMISSION -> {entry.phase=Phase.FINAL_READY;transport.control(ServerboundPackagePacket.FINAL_READY,entry.checkpoint);}
            case VISIBLE_ADMISSION -> {
                entry.phase=Phase.ACTIVE;entry.owned=true;transitions--;activeCount++;
                transport.activated(entry.offer,entry.active,entry.candidate,result.slotPlusOne());
            }
            default -> throw new IllegalStateException("Unexpected acquisition admission phase "+entry.phase);
        }
    }
    private void finish(Entry entry){
        entry.phase=Phase.RELEASED;entry.terminal=true;entry.owned=false;transitions--;awaiting.remove(entry);
        if(entry.body<0)reservedIdentities.remove(entry.offer.baseline().identity());
    }
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Acquisition closed/off render thread");}
    private void restoreOwned(Entry entry) {
        if(!entry.owned||entry.candidate<0)return;
        var identity=entry.offer.baseline().identity();
        transport.restore(entry.offer,checkpoints==null?PackagePoseQueryGpu.Result.NONE:
                checkpoints.find(entry.candidate,identity.id(),identity.generation()));
    }
    @Override public void close(){
        if(closed)return;
        try {
            if(checkpoints!=null)try{checkpoints.poll();}catch(RuntimeException failed){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] final free checkpoint poll failed",failed);}
            for(var e:entries.values())if(e.owned)try {
                restoreOwned(e);
            }catch(RuntimeException failed){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] free checkpoint restore failed; retaining native state",failed);}
        }finally{closed=true;admissions.close();work.clear();awaiting.clear();entries.clear();byCandidate.clear();reservedIdentities.clear();captureEntries.clear();captureExpected.clear();checkpoints=null;}
    }
}
