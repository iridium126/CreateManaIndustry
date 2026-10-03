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
    }
    private enum Phase { RESOURCES, OFFER_ADMISSION, PREPARED, FINAL_UPLOAD, FINAL_ADMISSION,
        FINAL_READY, ACTIVATE, VISIBLE_ADMISSION, ACTIVE, RETIRE, RETIRED_ADMISSION, RELEASED }
    private static final class Entry {
        final ClientboundPackagePacket offer;
        ClientboundPackagePacket checkpoint,active;
        Phase phase=Phase.RESOURCES,capturedPhase;
        int body=-1,candidate=-1,delta=-1;
        boolean queued,captured,terminal,owned;long environmentAck,capturedSubmission,capturedCommit,visibleSince,retireBarrier,environmentBarrier,retireFence;
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
    private final Map<ResourceLocation,PackageModelCache.Style> styles;
    private final Transport transport;
    private final ToIntFunction<ClientboundPackagePacket> light;
    private final PackageAdmissionTracker admissions;
    private final Map<Integer,Entry> entries=new HashMap<>(),byCandidate=new HashMap<>(),byBody=new HashMap<>();
    private final Set<PackageLease.Identity> reservedIdentities=new HashSet<>();
    private final ArrayDeque<Entry> retired=new ArrayDeque<>();
    private final ArrayDeque<Entry> work=new ArrayDeque<>();
    private final LinkedHashSet<Entry> awaiting=new LinkedHashSet<>();
    private final ArrayList<Entry> captureEntries=new ArrayList<>(MAX_TRANSITIONS);
    private final ArrayList<PackageAdmissionTracker.Expected> captureExpected=new ArrayList<>(MAX_TRANSITIONS);
    private final ByteBuffer body=BufferUtils.createByteBuffer(64),emptyChain=BufferUtils.createByteBuffer(64),
            metadata=BufferUtils.createByteBuffer(80),delta=BufferUtils.createByteBuffer(32),baseline=BufferUtils.createByteBuffer(32);
    private final Thread owner=Thread.currentThread();
    private int transitions,activeCount;
    private long nextCapture,lastCommit=-1,lastCaptureVersion=-1,lastCaptureConfirmation=-1;
    private boolean closed,closing;
    private java.util.Iterator<Entry> closingEntries;

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
    public ClientboundPackagePacket activeOffer(PackagePoseQueryGpu.Result result){return activeOffer(result,Long.MAX_VALUE);}
    public ClientboundPackagePacket activeOffer(PackagePoseQueryGpu.Result result,long submission) {
        open();if(closing||result==null||!result.present()||result.chain()||result.flags()!=PackagePoolGpu.ACTIVE_AUTHORITY||(result.state()<0&&result.state()!=PackagePhysicsGpu.COLLISION_FROZEN))return null;
        var entry=byCandidate.get(result.candidate());
        if(entry==null||entry.terminal||entry.phase!=Phase.ACTIVE||entry.body!=result.body()||submission<entry.visibleSince)return null;
        var identity=entry.offer.baseline().identity();
        return identity.id()==result.id()&&identity.generation()==result.generation()
                &&result.halfHeight()==entry.offer.height()*.5f?entry.offer:null;
    }
    public int pendingCount(){open();return transitions;}
    /** Namespace replacement drains only this region, under the normal transition budget. */
    public void beginClose(){open();if(closing)return;closing=true;closingEntries=entries.values().iterator();channel.stop();}
    public boolean replacementReady(){open();return closing&&!closingEntries.hasNext()&&transitions==0&&retired.isEmpty();}
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
        if(closing)return true;
        if(packet.action()==ClientboundPackagePacket.ACK)return false;
        if(packet.action()==ClientboundPackagePacket.ENVIRONMENT_ACK){environmentAck(packet);return true;}
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
        // Keep the exact terminal baseline for the exact retirement callback.
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
        if(closing)for(int i=0;i<maxTransitions&&closingEntries.hasNext();i++){
            var entry=closingEntries.next();closingEntries.remove();if(!entry.terminal)release(entry,entry.checkpoint.baseline(),false);
        }
        admissions.poll(this::confirmed);
        int recycling=Math.min(maxTransitions,retired.size());
        for(int i=0;i<recycling;i++){
            var entry=retired.removeFirst();int status=org.lwjgl.opengl.GL32.glClientWaitSync(entry.retireFence,org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT,0);
            if(status==org.lwjgl.opengl.GL32.GL_WAIT_FAILED)throw new IllegalStateException("Package retirement fence failed");
            if(status==org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED||!closing&&!channel.recyclable(entry.delta,entry.retireBarrier)
                    ||physics.environment()!=null&&!physics.environment().drained(entry.environmentBarrier)) {retired.addLast(entry);continue;}
            org.lwjgl.opengl.GL32.glDeleteSync(entry.retireFence);entry.retireFence=0;
            channel.recycle(entry.delta);pool.makeReusable(entry.candidate);physics.recycleFree(entry.body);
        }
        int n=Math.min(maxTransitions,work.size());
        for(int i=0;i<n;i++) {
            Entry entry=work.removeFirst();entry.queued=false;if(entry.terminal)continue;
            if(closing&&entry.phase!=Phase.RETIRE)continue;
            switch(entry.phase) {
                case RESOURCES -> {
                    if(!covered.test(entry.offer,entry.checkpoint)){queue(entry);continue;}
                    var identity=entry.offer.baseline().identity();
                    if(pool.reservesIdentity(identity.id(),identity.generation())){queue(entry);continue;}
                    if(physics.nextFreeBody()<0||physics.freeLiveCount()+physics.chainLiveCount()+physics.observerLiveCount()>=131072
                            ||pool.nextCandidate()<0||detector.nextCandidate()<0) {
                        release(entry,entry.checkpoint.baseline(),true);continue;
                    }
                    int local=physics.nextFreeBody();
                    try{encode(entry,local);}catch(IllegalArgumentException unsupported){release(entry,entry.checkpoint.baseline(),true);continue;}
                    entry.body=local;entry.candidate=pool.nextCandidate();entry.delta=detector.nextCandidate();
                    physics.writeFree(local,body,emptyChain);byBody.put(local,entry);pool.writeMetadata(entry.candidate,metadata);channel.write(entry.delta,delta,baseline);
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
                    physics.activatePreparedFree(entry.body);detector.activate(entry.delta);pool.setAuthorityActive(entry.candidate,true);pool.setHidden(entry.candidate,false);
                    entry.phase=Phase.VISIBLE_ADMISSION;awaiting.add(entry);
                }
                case RETIRE -> {
                    physics.retireFree(entry.body);if(physics.environment()!=null)physics.environment().retire(entry.body);pool.setAuthorityActive(entry.candidate,false);pool.setHidden(entry.candidate,true);
                    entry.phase=Phase.RETIRED_ADMISSION;awaiting.add(entry);
                }
                default -> throw new IllegalStateException("Unexpected queued acquisition phase "+entry.phase);
            }
        }
    }
    public ClientboundPackagePacket environmentOffer(int bodyIndex,long id,long generation,long lease,int index,long revision){
        open();var entry=byBody.get(bodyIndex);if(closing||entry==null||entry.terminal||entry.phase!=Phase.ACTIVE)return null;
        var b=entry.checkpoint.baseline();return b.identity().id()==id&&b.identity().generation()==generation&&b.leaseEpoch()==lease&&b.index()==index&&b.revision()==revision?entry.checkpoint:null;
    }
    public void environmentAck(ClientboundPackagePacket packet){
        open();if(packet.epoch()!=epoch||packet.regionRevision()!=revision||!packet.region().equals(region))return;
        var entry=entries.get(packet.baseline().index());
        if(entry==null||entry.terminal||entry.phase!=Phase.ACTIVE||!sameIdentity(entry,packet)
                ||entry.checkpoint.baseline().leaseEpoch()!=packet.baseline().leaseEpoch()||entry.checkpoint.baseline().revision()!=packet.baseline().revision()||packet.sequence()<=entry.environmentAck)return;
        entry.environmentAck=packet.sequence();if(physics.environment()!=null)physics.environment().acknowledge(entry.body,packet.sequence(),packet.fireTicks(),packet.health(),packet.environmentPermissions());
    }
    private void encode(Entry entry,int bodyIndex) {
        if(physics.environment()!=null){var b=entry.checkpoint.baseline();physics.environment().reset(bodyIndex,b.identity().id(),b.identity().generation(),b.leaseEpoch(),b.index(),b.revision(),entry.checkpoint.fireTicks(),entry.checkpoint.health(),entry.checkpoint.environmentPermissions());entry.environmentAck=0;}
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
        for(Entry entry:awaiting)if(!entry.captured && entry.phase!=Phase.RETIRE) {
            if(!batch.isEmpty() && entry.candidate!=batch.getLast().candidate+1) {
                if(!submit(batch,expected))return;batch.clear();expected.clear();
            }
            batch.add(entry);int flags=entry.phase==Phase.VISIBLE_ADMISSION?PackagePoolGpu.ACTIVE_AUTHORITY:PackagePoolGpu.HIDDEN;
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
        if(activeCount==0 || physics.freeSimulationStep()==0 || (version==lastCaptureVersion && confirmation==lastCaptureConfirmation))return false;
        boolean captured=channel.capture(physics.bodyBuffer(),physics.bodyCount(),
                (float)(ox-region.originX()),(float)(oy-region.originY()),(float)(oz-region.originZ()),physics.freeSimulationStep());
        if(captured){lastCaptureVersion=version;lastCaptureConfirmation=confirmation;}return captured;
    }
    private boolean submit(ArrayList<Entry> batch,ArrayList<PackageAdmissionTracker.Expected> expected) {
        if(!admissions.submit(pool,batch.getFirst().candidate,expected,nextCapture++))return false;
        for(Entry entry:batch){entry.captured=true;entry.capturedPhase=entry.phase;entry.capturedSubmission=nextCapture-1;entry.capturedCommit=lastCommit;}return true;
    }
    private void confirmed(PackageAdmissionTracker.Outcome result) {
        Entry entry=byCandidate.get(result.candidate());
        if(entry==null||!entry.captured||entry.capturedSubmission!=result.submission()||entry.offer.baseline().identity().id()!=result.id()||entry.offer.baseline().identity().generation()!=result.generation())return;
        entry.captured=false;
        if(entry.terminal || entry.phase!=entry.capturedPhase)return; // Superseded by a terminal notice.
        awaiting.remove(entry);
        if(entry.phase==Phase.RETIRED_ADMISSION) {
            if(result.accepted())throw new IllegalStateException("Retired package still admitted");
            finish(entry);transport.released(entry.offer,entry.checkpoint.baseline());return;
        }
        if(!result.accepted()){release(entry,entry.checkpoint.baseline(),true);return;}
        switch(entry.phase) {
            case OFFER_ADMISSION -> {entry.phase=Phase.PREPARED;transport.control(ServerboundPackagePacket.PREPARED,entry.checkpoint);}
            case FINAL_ADMISSION -> {entry.phase=Phase.FINAL_READY;transport.control(ServerboundPackagePacket.FINAL_READY,entry.checkpoint);}
            case VISIBLE_ADMISSION -> {
                entry.phase=Phase.ACTIVE;entry.owned=true;entry.visibleSince=entry.capturedCommit;transitions--;activeCount++;
                transport.activated(entry.offer,entry.active,entry.candidate,result.slotPlusOne());
            }
            default -> throw new IllegalStateException("Unexpected acquisition admission phase "+entry.phase);
        }
    }
    private void finish(Entry entry){
        var identity=entry.offer.baseline().identity();
        if(entry.body>=0){pool.retireIdentity(entry.candidate,identity.id(),identity.generation());detector.retireIdentity(entry.delta,identity.id(),identity.generation());}
        entry.phase=Phase.RELEASED;entry.terminal=true;entry.owned=false;transitions--;awaiting.remove(entry);
        // A closing map is drained only through its bounded iterator.
        if(!closing)entries.remove(entry.offer.baseline().index(),entry);byCandidate.remove(entry.candidate,entry);
        if(entry.body>=0){byBody.remove(entry.body,entry);entry.retireBarrier=detector.captureBarrier();entry.environmentBarrier=physics.environment()==null?-1:physics.environment().barrier();
            entry.retireFence=org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(entry.retireFence==0)throw new IllegalStateException("Retirement fence unavailable");retired.addLast(entry);}
        reservedIdentities.remove(identity);
    }
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Acquisition closed/off render thread");}
    @Override public void close(){
        if(closed)return;closed=true;admissions.close();
        for(var entry:retired)if(entry.retireFence!=0)org.lwjgl.opengl.GL32.glDeleteSync(entry.retireFence);
        retired.clear();work.clear();awaiting.clear();entries.clear();byCandidate.clear();byBody.clear();reservedIdentities.clear();captureEntries.clear();captureExpected.clear();
    }
}
