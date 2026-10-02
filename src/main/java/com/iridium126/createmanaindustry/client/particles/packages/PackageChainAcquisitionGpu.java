package com.iridium126.createmanaindustry.client.particles.packages;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.ToIntFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainInteraction;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainAuthority;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageLease;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundChainPackagePacket;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.BufferUtils;

/** Render-thread chain handshake. Queues only membership transitions, never scans the active
 * population. The channel owns tracks; the world runtime owns the shared physics/pool/models. */
public final class PackageChainAcquisitionGpu implements AutoCloseable {
    public interface Transport {
        void control(int action,ClientboundChainPackagePacket checkpoint,int chainCandidate);
        void activated(ClientboundChainPackagePacket offer,ClientboundChainPackagePacket active,
                       ClientboundChainPackagePacket.Track track,int poolCandidate,int slotPlusOne);
        void released(ClientboundChainPackagePacket offer,PackageChainAuthority.Baseline baseline);
        default void released(ClientboundChainPackagePacket offer,PackageChainAuthority.Baseline baseline,
                              PackagePoseQueryGpu.Result pose) { released(offer,baseline); }
    }
    private enum Phase { RESOURCES,OFFER_ADMISSION,PREPARED,FINAL_UPLOAD,FINAL_ADMISSION,FINAL_READY,
        ACTIVATE,VISIBLE_ADMISSION,ACTIVE,RETIRE,RETIRED_ADMISSION,RELEASED }
    private static final class Entry {
        final ClientboundChainPackagePacket offer;
        ClientboundChainPackagePacket checkpoint,active;
        Phase phase=Phase.RESOURCES,capturedPhase;
        int body=-1,candidate=-1;
        boolean queued,captured,terminal;
        long capturedSubmission,capturedCommit,visibleSince,retireBarrier,retireFence;
        boolean needsPose,poseRequested,retirementConfirmed;
        PackagePoseQueryGpu.Result pose;
        Entry(ClientboundChainPackagePacket offer){this.offer=checkpoint=offer;}
    }
    private static final int MAX_TRANSITIONS=256;
    private static final LongConsumer NO_INPUT=generation->{};
    private final long epoch;
    private final ResourceLocation dimension;
    private final double ox,oy,oz;
    private final PackageMixedPhysicsGpu physics;
    private final PackagePoolGpu pool;
    private final PackageChainTrackGpu tracks;
    private final PackageChainEventChannel channel;
    private final Map<ResourceLocation,PackageModelCache.Style> styles;
    private final ToIntFunction<ClientboundChainPackagePacket> light;
    private final ToDoubleFunction<ClientboundChainPackagePacket> hook;
    private final BiFunction<ClientboundChainPackagePacket,ClientboundChainPackagePacket,PackageChainUpload.Pendulum> pendulum;
    private final Transport transport;
    private final PackageAdmissionTracker admissions;
    private final Map<Integer,Entry> entries=new HashMap<>(),byCandidate=new HashMap<>();
    private final Map<Integer,ClientboundChainPackagePacket.Track> table=new HashMap<>();
    private final Set<PackageLease.Identity> reservedIdentities=new HashSet<>();
    private final ArrayDeque<Entry> retired=new ArrayDeque<>();
    private final ArrayDeque<Entry> work=new ArrayDeque<>();
    private final ArrayDeque<ClientboundChainPackagePacket.Track> trackWork=new ArrayDeque<>();
    private final LinkedHashSet<Entry> awaiting=new LinkedHashSet<>();
    private final LinkedHashSet<Entry> poseAwaiting=new LinkedHashSet<>();
    private PackagePoseQueryGpu queries;
    private java.util.function.Function<ClientboundChainPackagePacket.Track,net.minecraft.world.phys.Vec3> nativeOrigins;
    private Consumer<PackagePoseQueryGpu.Completed> picks;
    private final ArrayList<Entry> captureEntries=new ArrayList<>(MAX_TRANSITIONS);
    private final ArrayList<PackageAdmissionTracker.Expected> captureExpected=new ArrayList<>(MAX_TRANSITIONS);
    private final ByteBuffer body=BufferUtils.createByteBuffer(64),chain=BufferUtils.createByteBuffer(64),
            metadata=BufferUtils.createByteBuffer(80),events=BufferUtils.createByteBuffer(32),
            trackHeader=BufferUtils.createByteBuffer(64),nodes=BufferUtils.createByteBuffer(32*16);
    private final Thread owner=Thread.currentThread();
    private int transitions,activeCount,simulationCount;
    private long nextCapture,lastCommit=-1,lastCaptureVersion=-1,lastCaptureConfirmation=-1;
    private boolean closed;

    public PackageChainAcquisitionGpu(ResourceLocation dimension,long epoch,double ox,double oy,double oz,
            PackageMixedPhysicsGpu physics,PackagePoolGpu pool,PackageChainTrackGpu tracks,PackageChainEventChannel channel,
            Map<ResourceLocation,PackageModelCache.Style> styles,ToIntFunction<ClientboundChainPackagePacket> light,ToDoubleFunction<ClientboundChainPackagePacket> hook,
            BiFunction<ClientboundChainPackagePacket,ClientboundChainPackagePacket,PackageChainUpload.Pendulum> pendulum,Transport transport) {
        this.dimension=Objects.requireNonNull(dimension);this.physics=Objects.requireNonNull(physics);this.pool=Objects.requireNonNull(pool);
        this.tracks=Objects.requireNonNull(tracks);this.channel=Objects.requireNonNull(channel);
        this.styles=Map.copyOf(styles);this.light=Objects.requireNonNull(light);this.hook=Objects.requireNonNull(hook);this.pendulum=Objects.requireNonNull(pendulum);this.transport=Objects.requireNonNull(transport);
        if(epoch<=0 || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz) || tracks.count()!=0 || tracks.trackCount()!=0
                || physics.chainCount()!=0 || !channel.uses(tracks) || channel.epoch()!=epoch || channel.revision()!=1)
            throw new IllegalArgumentException("Chain acquisition namespace/origin");
        this.epoch=epoch;this.ox=ox;this.oy=oy;this.oz=oz;admissions=new PackageAdmissionTracker(MAX_TRANSITIONS,epoch);
    }
    public long epoch(){return epoch;}
    /** Production uses conveyor-local bodies for static and moving tracks alike. The provider
     * registers each exact parent/origin once; null defers the track without freezing Create. */
    public void nativeOrigins(java.util.function.Function<ClientboundChainPackagePacket.Track,net.minecraft.world.phys.Vec3> origins) {
        open();if(nativeOrigins!=null||!entries.isEmpty()||!table.isEmpty())throw new IllegalStateException("Chain native origin attachment order");
        nativeOrigins=Objects.requireNonNull(origins);
    }
    /** Optional for isolated admission tests; the world runtime always supplies a query ring.
     * Ownership remains with the caller and it must close the ring with this namespace. */
    public void poseQueries(PackagePoseQueryGpu queries) {
        open();if(this.queries!=null || !entries.isEmpty())throw new IllegalStateException("Chain pose query attachment order");
        if(Objects.requireNonNull(queries).epoch()!=epoch)throw new IllegalArgumentException("Chain pose query epoch");
        this.queries=queries;
    }
    public int activeCount(){open();return activeCount;}
    public int simulationCount(){open();return simulationCount;}
    public int pendingCount(){open();return transitions;}
    public void pickResults(Consumer<PackagePoseQueryGpu.Completed> consumer){open();picks=Objects.requireNonNull(consumer);}
    public boolean pick(PackageChainUseQueue.Use use,long generation) {
        open();if(queries==null || use.epoch()!=epoch || generation!=lastCommit)return false;
        return queries.pick(PackagePoseQueryGpu.Input.of(physics,pool),use.ray(),generation,use);
    }
    public PackageChainInteraction interaction(PackagePoseQueryGpu.Result result,long transaction){return interaction(result,transaction,Long.MAX_VALUE);}
    public PackageChainInteraction interaction(PackagePoseQueryGpu.Result result,long transaction,long submission) {
        open();if(result==null || !result.present() || !result.chain() || result.state()<0 || (result.flags()&PackagePoolGpu.HIDDEN)!=0)return null;
        var entry=byCandidate.get(result.candidate());
        if(entry==null || entry.phase!=Phase.ACTIVE || entry.terminal || submission<entry.visibleSince || result.body()!=physics.freeCapacity()+entry.body)return null;
        var b=entry.active.baseline();
        if(b.identity().id()!=result.id() || b.identity().generation()!=result.generation() || b.track()!=result.track())return null;
        return new PackageChainInteraction(epoch,b.identity(),b.leaseEpoch(),b.revision(),b.track(),b.trackRevision(),transaction,result.progress());
    }
    /** Main/render-thread packet receipt has no GL mutations. Ordered transport keeps track
     * indices append-only; a conflicting header requires a fresh world resource namespace. */
    public boolean receive(ClientboundChainPackagePacket packet) {
        open();if(packet.epoch()!=epoch || !dimension.equals(packet.dimension()))return false;
        if(packet.action()==ClientboundChainPackagePacket.ACK)return channel.acknowledge(epoch,1,packet.sequence());
        if(packet.action()==ClientboundChainPackagePacket.CLOSE)return false;
        if(packet.action()==ClientboundChainPackagePacket.TRACK) {
            var t=packet.track();var previous=table.get(t.index());
            if(previous!=null){if(!previous.equals(t))throw new IllegalStateException("Conflicting chain table namespace");return true;}
            if(t.index()!=table.size() || table.size()>=tracks.trackCapacity() || trackWork.size()>=MAX_TRANSITIONS)
                throw new IllegalStateException("Chain table order/capacity");
            table.put(t.index(),t);trackWork.addLast(t);return true;
        }
        var b=packet.baseline();Entry entry=entries.get(b.index());
        if(packet.action()==ClientboundChainPackagePacket.OFFER) {
            if(entry!=null){if(!entry.offer.equals(packet))transport.control(ServerboundChainPackagePacket.RELEASE,packet,0);return true;}
            var style=styles.get(packet.model());
            if(transitions>=MAX_TRANSITIONS || style==null || style.rig()<0 || !reservedIdentities.add(b.identity())) {
                transport.control(ServerboundChainPackagePacket.RELEASE,packet,0);return true;
            }
            entry=new Entry(packet);entries.put(b.index(),entry);transitions++;queue(entry);return true;
        }
        if(entry==null || entry.terminal || !entry.offer.baseline().identity().equals(b.identity()))return true;
        if(packet.action()==ClientboundChainPackagePacket.RELEASED) {
            if(b.leaseEpoch()>=entry.checkpoint.baseline().leaseEpoch() && b.revision()>=entry.checkpoint.baseline().revision())release(entry,b,false);
            return true;
        }
        if(b.leaseEpoch()!=entry.offer.baseline().leaseEpoch() || b.track()!=entry.offer.baseline().track()
                || b.trackRevision()!=entry.offer.baseline().trackRevision())return true;
        if(packet.action()==ClientboundChainPackagePacket.FINAL && entry.phase==Phase.PREPARED && b.revision()>entry.offer.baseline().revision()) {
            entry.checkpoint=packet;entry.phase=Phase.FINAL_UPLOAD;queue(entry);
        }else if(packet.action()==ClientboundChainPackagePacket.ACTIVE && entry.phase==Phase.FINAL_READY
                && b.revision()==entry.checkpoint.baseline().revision() && b.state().pose().equals(entry.checkpoint.baseline().state().pose())
                && Float.floatToIntBits(b.state().progress())==Float.floatToIntBits(entry.checkpoint.baseline().state().progress())
                && b.eligibility()==entry.checkpoint.baseline().eligibility()) {
            entry.active=packet;entry.phase=Phase.ACTIVATE;queue(entry);
        }
        return true;
    }
    public void requestRelease(int serverIndex) {
        open();var entry=entries.get(serverIndex);if(entry!=null && !entry.terminal)release(entry,entry.checkpoint.baseline(),true);
    }
    private void release(Entry entry,PackageChainAuthority.Baseline baseline,boolean notify) {
        if(entry.phase==Phase.RETIRE || entry.phase==Phase.RETIRED_ADMISSION)return;
        if(notify)transport.control(ServerboundChainPackagePacket.RELEASE,entry.checkpoint,Math.max(0,entry.body));
        entry.checkpoint=new ClientboundChainPackagePacket(ClientboundChainPackagePacket.RELEASED,dimension,epoch,null,baseline,null,0,0,0);
        if(entry.body<0){finish(entry);transport.released(entry.offer,baseline);return;}
        if(entry.phase==Phase.ACTIVE){activeCount--;transitions++;}
        if(entry.phase==Phase.ACTIVE || entry.phase==Phase.VISIBLE_ADMISSION)simulationCount--;
        entry.phase=Phase.RETIRE;queue(entry);
    }
    private void queue(Entry entry){if(!entry.queued){entry.queued=true;work.addLast(entry);}}
    public void pump(int maximum,BiPredicate<ClientboundChainPackagePacket,ClientboundChainPackagePacket> covered) {
        open();if(maximum<0 || maximum>MAX_TRANSITIONS)throw new IllegalArgumentException("Chain work budget");
        if(queries!=null)queries.poll(c->{if(c.kind()==PackagePoseQueryGpu.Kind.PICK){if(picks==null)throw new IllegalStateException("Missing chain pick consumer");picks.accept(c);}else poseConfirmed(c);});
        admissions.poll(this::confirmed);
        for(int r=0;r<maximum&&!retired.isEmpty();r++){
            var entry=retired.getFirst();int fence=org.lwjgl.opengl.GL32.glClientWaitSync(entry.retireFence,0,0);
            if(fence==org.lwjgl.opengl.GL32.GL_WAIT_FAILED)throw new IllegalStateException("Chain retirement fence failed");
            if(fence==org.lwjgl.opengl.GL32.GL_TIMEOUT_EXPIRED||!channel.recyclable(entry.body,entry.retireBarrier)||queries!=null&&queries.pending()>0)break;
            retired.removeFirst();org.lwjgl.opengl.GL32.glDeleteSync(entry.retireFence);channel.recycle(entry.body);pool.makeReusable(entry.candidate);physics.recycleChain(entry.body);
        }
        for(int n=0;n<maximum && !trackWork.isEmpty();n++) {
            var t=trackWork.getFirst();
            var origin=nativeOrigins==null?null:nativeOrigins.apply(t);
            if(nativeOrigins!=null&&origin==null)break;
            if(t.index()!=tracks.trackCount() || tracks.nodeCount()+t.nodes().size()>tracks.nodeCapacity())
                throw new IllegalStateException("Chain geometry capacity/order");
            nodes.clear().limit(t.nodes().size()*16);PackageChainUpload.track(t,tracks.nodeCount(),
                    origin==null?ox:origin.x,origin==null?oy:origin.y,origin==null?oz:origin.z,trackHeader,nodes);
            tracks.appendTables(trackHeader,nodes);trackWork.removeFirst();
        }
        int n=Math.min(maximum,work.size());
        for(int i=0;i<n;i++) {
            Entry entry=work.removeFirst();entry.queued=false;if(entry.terminal)continue;
            switch(entry.phase) {
                case RESOURCES -> {
                    if(entry.offer.baseline().track()>=tracks.trackCount() || !covered.test(entry.offer,entry.checkpoint)){queue(entry);continue;}
                    var identity=entry.offer.baseline().identity();
                    if(pool.reservesIdentity(identity.id(),identity.generation())){queue(entry);continue;}
                    if(physics.nextChainBody()<0 || physics.freeLiveCount()+physics.chainLiveCount()+physics.observerLiveCount()>=131072
                            ||pool.nextCandidate()<0||tracks.nextCandidate()<0) {
                        release(entry,entry.checkpoint.baseline(),true);continue;
                    }
                    int local=physics.nextChainBody();
                    try{encode(entry,local);}catch(IllegalArgumentException unsupported){release(entry,entry.checkpoint.baseline(),true);continue;}
                    if(tracks.nextCandidate()!=local)throw new IllegalStateException("Chain body/journal candidate order");
                    entry.body=local;entry.candidate=pool.nextCandidate();
                    physics.writeChain(local,body,chain);pool.writeMetadata(entry.candidate,metadata);channel.write(local,events);
                    byCandidate.put(entry.candidate,entry);entry.phase=Phase.OFFER_ADMISSION;awaiting.add(entry);
                }
                case FINAL_UPLOAD -> {
                    if(!covered.test(entry.offer,entry.checkpoint)){release(entry,entry.checkpoint.baseline(),true);continue;}
                    try{encode(entry,entry.body);}catch(IllegalArgumentException unsupported){release(entry,entry.checkpoint.baseline(),true);continue;}
                    physics.replaceChains(entry.body,body,chain,1);tracks.rebasePrepared(entry.body,events);
                    entry.phase=Phase.FINAL_ADMISSION;awaiting.add(entry);
                }
                case ACTIVATE -> {
                    if(!covered.test(entry.offer,entry.checkpoint)){release(entry,entry.checkpoint.baseline(),true);continue;}
                    physics.activatePreparedChain(entry.body);tracks.activate(entry.body);pool.setHidden(entry.candidate,false);
                    entry.needsPose=queries!=null;
                    simulationCount++;entry.phase=Phase.VISIBLE_ADMISSION;awaiting.add(entry);
                }
                case RETIRE -> {
                    physics.retireChain(entry.body);tracks.retire(entry.body);pool.setHidden(entry.candidate,true);
                    entry.phase=Phase.RETIRED_ADMISSION;awaiting.add(entry);
                    if(entry.needsPose)poseAwaiting.add(entry);
                }
                default -> {}
            }
        }
    }
    private void encode(Entry entry,int localBody) {
        var track=table.get(entry.offer.baseline().track());
        var origin=nativeOrigins==null?null:nativeOrigins.apply(track);
        if(nativeOrigins!=null&&origin==null)throw new IllegalArgumentException("Chain native parent/origin unavailable");
        PackageChainUpload.prepared(entry.offer,entry.checkpoint,track,styles.get(entry.offer.model()),physics.freeCapacity()+localBody,
                light.applyAsInt(entry.offer),(float)hook.applyAsDouble(entry.offer),origin==null?ox:origin.x,origin==null?oy:origin.y,origin==null?oz:origin.z,
                pendulum.apply(entry.offer,entry.checkpoint),body,chain,metadata,events,nativeOrigins!=null);
    }
    /** Engine success-only commit hook, after both the shared pool and indirect commands publish. */
    public void committed(long generation) {
        committed(generation,NO_INPUT);
    }
    /** Give queued user input one capture opportunity before the retirement gather batches. */
    public void committed(long generation,LongConsumer inputCapture) {
        open();if(generation<0 || generation<lastCommit)throw new IllegalArgumentException("Chain commit generation");
        if(generation==lastCommit)return;lastCommit=generation;
        if(!pool.sourceMatches(physics.bodyBuffer(),physics.chainBuffer(),physics.historyBuffer(),physics.bodyCount(),(float)ox,(float)oy,(float)oz))
            throw new IllegalStateException("Chain admission uses a different physics publication");
        captureAdmissions();Objects.requireNonNull(inputCapture).accept(generation);capturePoses(generation);
    }
    private void captureAdmissions() {
        var batch=captureEntries;var expected=captureExpected;batch.clear();expected.clear();
        for(Entry entry:awaiting)if(!entry.captured && entry.phase!=Phase.RETIRE) {
            if(!batch.isEmpty() && entry.candidate!=batch.getLast().candidate+1) {
                if(!submit(batch,expected))return;batch.clear();expected.clear();
            }
            batch.add(entry);var identity=entry.offer.baseline().identity();
            var t=table.get(entry.offer.baseline().track());
            int flags=PackagePoolGpu.CHAIN|(t.geometry().reversed()?PackagePoolGpu.FLIPPED:0)
                    |(entry.phase==Phase.VISIBLE_ADMISSION?0:PackagePoolGpu.HIDDEN)|(nativeOrigins==null?0:PackagePoolGpu.FRAMED);
            expected.add(new PackageAdmissionTracker.Expected(identity.id(),identity.generation(),flags));
            if(batch.size()==MAX_TRANSITIONS){if(!submit(batch,expected))return;batch.clear();expected.clear();}
        }
        if(!batch.isEmpty())submit(batch,expected);
    }
    private void capturePoses(long generation) {
        if(queries==null || poseAwaiting.isEmpty())return;
        var batch=new ArrayList<Entry>(MAX_TRANSITIONS);
        var requests=new ArrayList<PackagePoseQueryGpu.Request>(MAX_TRANSITIONS);
        for(Entry entry:poseAwaiting)if(!entry.poseRequested) {
            var id=entry.offer.baseline().identity();batch.add(entry);
            requests.add(new PackagePoseQueryGpu.Request(id.id(),id.generation(),entry.candidate,true));
            if(batch.size()==MAX_TRANSITIONS)break;
        }
        if(!batch.isEmpty() && queries.poses(PackagePoseQueryGpu.Input.of(physics,pool),requests,generation,List.copyOf(batch)))
            for(Entry entry:batch)entry.poseRequested=true;
    }
    private void poseConfirmed(PackagePoseQueryGpu.Completed completed) {
        if(completed.kind()!=PackagePoseQueryGpu.Kind.POSES || !(completed.tag() instanceof List<?> batch)
                || batch.size()!=completed.results().size())throw new IllegalStateException("Chain pose query tag");
        for(int i=0;i<batch.size();i++) {
            if(!(batch.get(i) instanceof Entry entry) || byCandidate.get(entry.candidate)!=entry)
                throw new IllegalStateException("Chain pose query owner");
            if(entry.terminal || entry.phase!=Phase.RETIRED_ADMISSION)continue;
            var result=completed.results().get(i);var id=entry.offer.baseline().identity();
            if(!entry.poseRequested || !result.present() || !result.chain() || !result.retired()
                    || result.id()!=id.id() || result.generation()!=id.generation() || result.candidate()!=entry.candidate
                    || result.body()!=physics.freeCapacity()+entry.body || result.track()!=entry.offer.baseline().track())
                throw new IllegalStateException("Chain pose checkpoint identity/publication");
            PackageChainUpload.checkpoint(result,id,table.get(result.track()),ox,oy,oz);
            entry.pose=result;
        }
        // Validate the whole batch before any native ownership or renderer mutation.
        for(Object value:batch) {var entry=(Entry)value;if(!entry.terminal)completeRetirement(entry);}
    }
    private void completeRetirement(Entry entry) {
        if(!entry.retirementConfirmed || entry.needsPose && entry.pose==null)return;
        finish(entry);transport.released(entry.offer,entry.checkpoint.baseline(),entry.pose);
    }
    public boolean captureCommitted(long generation) {
        open();if(generation!=lastCommit)throw new IllegalArgumentException("Chain event/admission generation");
        long version=physics.publicationVersion(),confirmation=channel.confirmationVersion();
        if(simulationCount==0 || version==lastCaptureVersion && confirmation==lastCaptureConfirmation)return false;
        boolean captured=channel.capture();if(captured){lastCaptureVersion=version;lastCaptureConfirmation=confirmation;}return captured;
    }
    private boolean submit(ArrayList<Entry> batch,ArrayList<PackageAdmissionTracker.Expected> expected) {
        if(!admissions.submit(pool,batch.getFirst().candidate,expected,nextCapture++))return false;
        for(Entry entry:batch){entry.captured=true;entry.capturedPhase=entry.phase;entry.capturedSubmission=nextCapture-1;entry.capturedCommit=lastCommit;}return true;
    }
    private void confirmed(PackageAdmissionTracker.Outcome result) {
        Entry entry=byCandidate.get(result.candidate());
        if(entry==null||!entry.captured||entry.capturedSubmission!=result.submission()||entry.offer.baseline().identity().id()!=result.id()||entry.offer.baseline().identity().generation()!=result.generation())return;
        entry.captured=false;if(entry.terminal || entry.phase!=entry.capturedPhase)return;
        awaiting.remove(entry);
        if(entry.phase==Phase.RETIRED_ADMISSION) {
            if(result.accepted())throw new IllegalStateException("Retired chain package still admitted");
            entry.retirementConfirmed=true;completeRetirement(entry);return;
        }
        if(!result.accepted()){release(entry,entry.checkpoint.baseline(),true);return;}
        switch(entry.phase) {
            case OFFER_ADMISSION -> {entry.phase=Phase.PREPARED;transport.control(ServerboundChainPackagePacket.PREPARED,entry.checkpoint,entry.body);}
            case FINAL_ADMISSION -> {entry.phase=Phase.FINAL_READY;transport.control(ServerboundChainPackagePacket.FINAL_READY,entry.checkpoint,entry.body);}
            case VISIBLE_ADMISSION -> {
                entry.phase=Phase.ACTIVE;entry.visibleSince=entry.capturedCommit;transitions--;activeCount++;
                transport.activated(entry.offer,entry.active,table.get(entry.offer.baseline().track()),entry.candidate,result.slotPlusOne());
            }
            default -> throw new IllegalStateException("Unexpected chain admission "+entry.phase);
        }
    }
    private void finish(Entry entry) {
        var identity=entry.offer.baseline().identity();
        if(entry.body>=0){pool.retireIdentity(entry.candidate,identity.id(),identity.generation());tracks.retireIdentity(entry.body,identity.id(),identity.generation());}
        entry.phase=Phase.RELEASED;entry.terminal=true;transitions--;awaiting.remove(entry);poseAwaiting.remove(entry);
        reservedIdentities.remove(identity);entries.remove(entry.offer.baseline().index(),entry);byCandidate.remove(entry.candidate,entry);
        if(entry.body>=0){entry.retireBarrier=tracks.captureBarrier();entry.retireFence=org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE,0);if(entry.retireFence==0)throw new IllegalStateException("Chain retirement fence unavailable");retired.addLast(entry);}
    }
    private void open(){if(closed || Thread.currentThread()!=owner)throw new IllegalStateException("Chain acquisition closed/off render thread");}
    @Override public void close() {
        if(closed)return;closed=true;admissions.close();for(var entry:retired)if(entry.retireFence!=0)org.lwjgl.opengl.GL32.glDeleteSync(entry.retireFence);retired.clear();work.clear();trackWork.clear();awaiting.clear();poseAwaiting.clear();entries.clear();byCandidate.clear();
        table.clear();reservedIdentities.clear();captureEntries.clear();captureExpected.clear();
    }
}
