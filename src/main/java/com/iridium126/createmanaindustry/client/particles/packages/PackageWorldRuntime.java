package com.iridium126.createmanaindustry.client.particles.packages;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundChainPackagePacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ServerboundChainInteractionPacket;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.ClientboundChainInteractionPacket;
import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Engine-owned world resources. Network dispatch queues transitions; GL mutations, physics and
 * publication all run inside the engine boundary. Closing never waits for a worker or fence. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID,value=Dist.CLIENT)
public final class PackageWorldRuntime {
    private static final int MAX_REGIONS=8,MAX_QUEUED=1024,PACKETS_PER_FRAME=64;
    private static PackageWorldRuntime current;
    private final ArrayDeque<ClientboundPackagePacket> packets=new ArrayDeque<>();
    private final ArrayDeque<ClientboundChainPackagePacket> chainPackets=new ArrayDeque<>();
    private final Map<PackageRegion,PackageFreeAcquisitionGpu> regions=new HashMap<>();
    private final PackageSimulationClock clock=new PackageSimulationClock();
    private ClientLevel level;
    private Object shaderBoundary;
    private PackageMixedPhysicsGpu physics;
    private PackageWorldPrefetchGpu worldPrefetch;
    private PackageForceClient forceCapture;
    private PackageForceGpu forceGpu;
    private PackageLightObserverClient nativeObservers;
    private static final java.util.concurrent.atomic.AtomicLong nativeEpochs=new java.util.concurrent.atomic.AtomicLong(0x6000000000000000L);
    private PackagePoolGpu pool;
    private PackageChainTrackGpu chainTracks;
    private PackageChainFrameScene chainFrames;
    private PackageChainEventChannel chainChannel;
    private PackageChainAcquisitionGpu chainAcquisition;
    private PackagePoseQueryGpu chainQueries;
    private PackageChainCheckpointGpu chainCheckpoints;
    private PackageFreeCheckpointGpu freeCheckpoints;
    private PackageFreeInteractionClient freeInteraction;
    private long freeCheckpointVersion=-1;
    private int freeCheckpointCount=-1;
    private long checkpointVersion=-1;
    private int checkpointCount=-1;
    private PackageChainInteractionClient chainInteraction;
    private long renderFrame;
    private record ChainLightProbe(BlockPos block,net.minecraft.world.phys.AABB bounds) {}
    private final java.util.function.LongConsumer captureChainInput=generation->{if(chainInteraction!=null)chainInteraction.committed(generation);};
    private final java.util.function.Consumer<PackageCollisionCache.Section> requestLight=section->{
        if(!PackageCollisionRuntime.forLevel(level).requestLightSection(section))throw new IllegalStateException("Package light coverage capacity exhausted");
    };
    private final java.util.function.Consumer<PackageCollisionCache.Section> requestCollision=section->{
        if(level!=null)PackageCollisionRuntime.forLevel(level).requestCollisionSection(section);
    };
    private final PackageCollisionRequests.Usage collisionUsage=new PackageCollisionRequests.Usage() {
        @Override public void begin(long tableVersion) {
            if(level!=null)PackageCollisionRuntime.forLevel(level).beginPackageUsage(tableVersion);
        }
        @Override public void row(long tableVersion,int row) {
            if(level!=null)PackageCollisionRuntime.forLevel(level).touchPackageUsage(tableVersion,row);
        }
        @Override public void unsafeBody(int bodyIndex) {PackageAuthorityClient.requestReleaseBody(bodyIndex);}
    };
    private Map<ResourceLocation,PackageModelCache.Style> styles=Map.of();
    private double ox,oy,oz;
    private long heartbeatTick=Long.MIN_VALUE,retryAfter;
    private String failure,status="off";
    private int statusRegions=-1,statusBodies=-1,statusActive=-1;
    private float interpolation=1;

    /** False means the caller must explicitly refuse the offered ownership. */
    public static boolean enqueue(ClientboundPackagePacket packet) {
        var runtime=current;
        if(runtime==null || runtime.failure!=null || runtime.level!=Minecraft.getInstance().level)return false;
        if(runtime.packets.size()>=MAX_QUEUED){runtime.failure="Package transition queue exhausted";return false;}
        runtime.packets.addLast(packet);return true;
    }
    public static boolean enqueueChain(ClientboundChainPackagePacket packet) {
        var runtime=current;
        if(runtime==null || runtime.failure!=null || runtime.level!=Minecraft.getInstance().level
                || runtime.physics.chainCapacity()==0)return false;
        if(runtime.chainPackets.size()>=MAX_QUEUED){runtime.failure="Chain package transition queue exhausted";return false;}
        runtime.chainPackets.addLast(packet);return true;
    }
    /** Engine success hook also runs when there are no free-package regions. */
    public static void committedChain(long generation) {
        var runtime=current;if(runtime==null || runtime.failure!=null)return;
        if(runtime.nativeObservers!=null)runtime.nativeObservers.committed(generation);
        if(runtime.freeInteraction!=null)runtime.freeInteraction.committed(generation,runtime.interpolation);
        if(runtime.freeCheckpoints!=null && runtime.physics.freeCount()>0) {
            if(!runtime.pool.sourceMatches(runtime.physics.bodyBuffer(),runtime.physics.chainBuffer(),runtime.physics.historyBuffer(),runtime.physics.bodyCount(),
                    (float)runtime.ox,(float)runtime.oy,(float)runtime.oz))throw new IllegalStateException("Free checkpoint uses a different publication");
            long version=runtime.physics.freePublicationVersion();int count=runtime.pool.admissionCount();
            if((version!=runtime.freeCheckpointVersion||count!=runtime.freeCheckpointCount)
                    && runtime.freeCheckpoints.captureAuthority(PackagePoseQueryGpu.Input.of(runtime.physics,runtime.pool),runtime.physics.freeCount(),generation)) {
                runtime.freeCheckpointVersion=version;runtime.freeCheckpointCount=count;
            }
        }
        if(runtime.chainAcquisition==null)return;
        runtime.chainAcquisition.committed(generation,runtime.captureChainInput);
        runtime.chainAcquisition.captureCommitted(generation);
        long version=runtime.physics.chainPublicationVersion();int count=runtime.pool.admissionCount();
        if((runtime.chainAcquisition.simulationCount()>0 || PackageChainClientOwnership.INSTANCE.ownedCount()>0)
                && (version!=runtime.checkpointVersion || count!=runtime.checkpointCount)
                && runtime.chainCheckpoints.capture(PackagePoseQueryGpu.Input.of(runtime.physics,runtime.pool),generation)) {
            runtime.checkpointVersion=version;runtime.checkpointCount=count;
        }
    }
    public static boolean chainUse(){var runtime=current;return runtime!=null && runtime.failure==null && runtime.chainInteraction!=null && runtime.chainInteraction.onUse();}
    public static boolean freeInputReady(){var r=current;return r!=null&&r.failure==null&&r.freeInteraction!=null&&PackageAuthorityClient.activePackages()>0;}
    public static void injectFreeCrosshairPick(Minecraft mc){var r=current;if(r!=null&&r.failure==null&&r.freeInteraction!=null)r.freeInteraction.injectCrosshairPick(mc,r.interpolation);}
    public static boolean freeUse(){var r=current;if(r!=null&&r.failure==null&&r.freeInteraction!=null&&r.freeInteraction.onInput(PackageFreePickQueue.Action.USE))return true;return PackageLightClient.input(false);}
    public static boolean freeAttack(){var r=current;if(r!=null&&r.failure==null&&r.freeInteraction!=null&&r.freeInteraction.onInput(PackageFreePickQueue.Action.ATTACK))return true;return PackageLightClient.input(true);}
    public static boolean freeInputPending(){var r=current;return r!=null&&r.freeInteraction!=null&&r.freeInteraction.pending();}
    public static boolean forceReady(){var r=current;return r!=null&&r.forceCapture!=null&&r.forceCapture.ready();}
    public static void chainInteraction(ClientboundChainInteractionPacket packet){var runtime=current;if(runtime!=null && runtime.chainInteraction!=null)runtime.chainInteraction.acknowledge(packet);}
    public static String status(){var runtime=current;return runtime==null?"off":runtime.status
            +(runtime.chainInteraction==null?"":"; pickup ACK round trip="+runtime.chainInteraction.networkNanos()/1_000_000.0+"ms")
            +(runtime.pool==null?"":"; light "+runtime.pool.lightFeedbackReport())
            +(runtime.freeCheckpoints==null?"":"; free checkpoint bytes="+runtime.freeCheckpoints.readbackBytes()+", pending="+runtime.freeCheckpoints.pending()
                    +", skipped="+runtime.freeCheckpoints.skipped()+", latency="+runtime.freeCheckpoints.lastLatencyNanos()/1_000_000.0+"ms")
            +(runtime.chainCheckpoints==null?"":"; chain checkpoint bytes="+runtime.chainCheckpoints.readbackBytes()+", pending="+runtime.chainCheckpoints.pending()
                    +", skipped="+runtime.chainCheckpoints.skipped()+", latency="+runtime.chainCheckpoints.lastLatencyNanos()/1_000_000.0+"ms")
            +(runtime.worldPrefetch==null?"":"; "+runtime.worldPrefetch.stats())
            +(runtime.forceGpu==null?"":"; force upload bytes="+runtime.forceGpu.uploadedBytes());}
    public float interpolation(){return interpolation;}
    /** The master switch can prevent the engine from submitting any more frames. Revoke its
     * claims here too, so disabling rendering cannot leave invisible retained Create entities. */
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var runtime=current;if(runtime==null)return;
        var mc=Minecraft.getInstance();
        if(mc.level!=runtime.level || mc.getConnection()==null || !ClientConfig.particleEnabled
                || !ClientConfig.packageGpuAuthority || (dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse()
                && (!CreateManaIndustry.IRIS_ACTIVE || !ClientConfig.shaderPackIntegration
                    || com.iridium126.createmanaindustry.client.particles.shaderpack.PackageShaderHook.terminalFailure())))
            PackageAuthorityClient.closeAll("Package runtime disabled or world/render boundary changed",true);
        if(current==runtime && runtime.chainInteraction!=null)runtime.chainInteraction.tick();
        if(current==runtime && runtime.freeInteraction!=null)runtime.freeInteraction.tick();
        if(current==runtime && runtime.forceCapture!=null && !mc.isPaused()) {
            try{runtime.forceCapture.prepare(runtime.ox,runtime.oy,runtime.oz,runtime.regions.keySet());}
            catch(RuntimeException | LinkageError error){runtime.failure="Package force tick capture failed: "+error.getMessage();}
        }
    }

    /** Called after collision uploads, before package pool staging. Return value invalidates the
     * particle engine's GL binding cache, including on shutdown or failed initialization. */
    public boolean prepare(Supplier<PackagePoolGpu> poolFactory,Function<String,String> sources,boolean preview) {
        RenderSystem.assertOnRenderThread();
        var mc=Minecraft.getInstance();var connection=mc.getConnection();long now=System.nanoTime();
        Object nextShaderBoundary=CreateManaIndustry.IRIS_ACTIVE
                ? com.iridium126.createmanaindustry.client.particles.shaderpack.PackageShaderHook.pipelineBoundary() : null;
        // Package physics/storage are independent of an Iris pipeline object. The draw hook
        // recompiles its pipeline-bound program; replacing that program must not revoke every lease.
        if(physics!=null && shaderBoundary!=nextShaderBoundary)shaderBoundary=nextShaderBoundary;
        boolean shaderPack=dev.engine_room.flywheel.lib.util.ShadersModHelper.isShaderPackInUse();
        boolean shaderReady=!shaderPack || (CreateManaIndustry.IRIS_ACTIVE
                && com.iridium126.createmanaindustry.client.particles.shaderpack.PackageShaderHook.prepare());
        boolean enabled=ClientConfig.particleEnabled && ClientConfig.packageGpuAuthority && !preview && mc.level!=null && mc.player!=null
                && connection!=null && connection.hasChannel(ServerboundPackagePacket.TYPE)
                && (!shaderPack || (CreateManaIndustry.IRIS_ACTIVE && ClientConfig.shaderPackIntegration));
        if(physics!=null && (!enabled || level!=mc.level || failure!=null)) {
            PackageAuthorityClient.closeAll(failure==null?"Package runtime world/render boundary changed":failure,true);
            return true;
        }
        if(!enabled || now<retryAfter || physics==null&&!shaderReady)return false;
        try {
            if(physics==null)initialize(mc.level,poolFactory,sources);
            renderFrame=Math.incrementExact(renderFrame);
            if(chainFrames!=null)chainFrames.beginFrame(renderFrame);
            if(!regions.isEmpty()||!packets.isEmpty()||PackageAuthorityClient.activePackages()>0){
                if(forceCapture==null){forceCapture=new PackageForceClient(level);forceGpu=new PackageForceGpu();}
                forceCapture.prepare(ox,oy,oz,regions.keySet());
            }
            if(freeCheckpoints!=null) {
                freeCheckpoints.poll();
            }
            if(freeInteraction!=null)freeInteraction.prepare();
            if(chainCheckpoints!=null) {
                chainCheckpoints.poll();
            }
            pool.pollLightRequests(requestLight);
            if(worldPrefetch!=null)worldPrefetch.poll(requestCollision,collisionUsage);
            // Lightweight observer streams prepare baselines and
            // retirement before authority acquisition can use the same shared reservation.
            if(nativeObservers!=null)nativeObservers.prepare(System.nanoTime());
            if(failure!=null)throw new IllegalStateException(failure);
            if(!drain(sources))return true;
            if(!drainChains(sources))return true;
            // An OFFER may add interest within this frame. An empty/old-region capture cannot
            // authorize acquisition; invalidate it and retry before pumping readiness.
            if(forceCapture!=null)forceCapture.prepare(ox,oy,oz,regions.keySet());
            if(chainAcquisition!=null) {
                var nativeOwnership=PackageChainClientOwnership.INSTANCE;nativeOwnership.prepare();
                chainAcquisition.pump(64,this::chainCovered);nativeOwnership.publishRenderMembership();chainChannel.pump(256);
                if(failure!=null)throw new IllegalStateException(failure);
            }
            PackageAuthorityClient.pump();
            // A channel failure closes all resources synchronously. Do not step or publish them.
            if(physics==null)return true;
            int freeActive=PackageAuthorityClient.activePackages(),chainActive=chainAcquisition==null?0:chainAcquisition.simulationCount();
            if(freeActive==0)PackageCollisionRuntime.releasePackageUsage(level);
            int active=freeActive+chainActive;
            if(active==0)clock.reset();
            // Initialization and acquisition can be expensive. Start an acquired body's clock
            // here, rather than charging work done before it owned any simulation time.
            var advance=active==0 ? PackageSimulationClock.Advance.IDLE
                    : clock.advance(System.nanoTime(),level.getGameTime(),mc.isPaused() || !shaderReady,freeActive==0 || forceCapture.ready());
            if(active>0 && advance==PackageSimulationClock.Advance.STEP) {
                if(freeActive>0) {
                    var collision=PackageCollisionRuntime.forLevel(level);
                    try(var world=collision.view((int)(ox/16),(int)(oy/16),(int)(oz/16));
                        var moving=collision.movingView((int)(ox/16),(int)(oy/16),(int)(oz/16))) {
                        // A frame-level atlas/pose-bank miss is temporary resource pressure. Do
                        // not run prefetch or advance forces; resume from the unchanged state when
                        // both immutable views are ready. Per-body missing geometry freezes only
                        // the affected bodies inside the collision shaders.
                        if(world.ready() && moving.ready())try(var forces=forceGpu.view(forceCapture.snapshot(),level.getGameTime())) {
                            physics.applyFreeForces(forces,.05f);
                            if(worldPrefetch!=null)worldPrefetch.capture(physics.freeStateBuffer(),physics.freeCount(),.5f,.15f,world);
                            physics.stepFreeMoving(world,PackagePhysicsGpu.ITERATIONS,PackagePhysicsGpu.IndexMode.LINKED,moving.views());
                        }
                    }
                }
                if(chainActive>0)physics.stepChains(.05f,chainTracks);
            }
            physics.publish();physics.source(pool,(float)ox,(float)oy,(float)oz);
            if(chainFrames!=null)chainFrames.prepare(pool);
            pool.lightSource(PackageCollisionRuntime.forLevel(level).lightGpu());
            interpolation=clock.interpolation();
            if(!mc.isPaused() && heartbeatTick!=level.getGameTime()) {
                heartbeatTick=level.getGameTime();
                for(var acquisition:regions.values())PacketDistributor.sendToServer(new ServerboundPackagePacket(
                        ServerboundPackagePacket.HEARTBEAT,0,acquisition.region(),acquisition.epoch(),0,null,0,0,0,new byte[0]));
                if(chainAcquisition!=null)PacketDistributor.sendToServer(new ServerboundChainPackagePacket(
                        ServerboundChainPackagePacket.HEARTBEAT,chainAcquisition.epoch(),0,null,0,0,0,0,new byte[0]));
            }
            int nativeActive=nativeObservers==null?0:nativeObservers.active();
            if(statusRegions!=regions.size() || statusBodies!=physics.freeCount()+physics.chainCount()+physics.observerCount() || statusActive!=active+nativeActive) {
                statusRegions=regions.size();statusBodies=physics.freeCount()+physics.chainCount()+physics.observerCount();statusActive=active+nativeActive;
                status="free ready; physics backend=OpenGL compute; regions="+statusRegions+"; body indices="+statusBodies+"; active="+statusActive
                        +"; chains="+(chainAcquisition==null?"interaction/checkpoint readiness pending":chainAcquisition.activeCount())+"; lightweight observers="+nativeActive;
            }
            return true;
        }catch(RuntimeException | LinkageError error) {
            CreateManaIndustry.LOGGER.error("[CMI packages] world runtime failed; releasing GPU ownership",error);
            failure="Package world runtime failed: "+error.getMessage();
            PackageAuthorityClient.closeAll(failure,true);
            // Initialization can fail before the runtime has been attached globally.
            dispose(failure);return true;
        }
    }
    private void initialize(ClientLevel nextLevel,Supplier<PackagePoolGpu> poolFactory,Function<String,String> sources) {
        var baked=PackageModelCache.bake();
        if(baked.styles().isEmpty())throw new IllegalStateException("No compatible Create package meshes");
        pool=java.util.Objects.requireNonNull(poolFactory.get(),"Package pool unavailable");
        if(pool.metadataCount()!=0)throw new IllegalStateException("Package pool belongs to another runtime");
        // Chain state reserves no extra generic particle slots. Chain acquisition is attached separately.
        int capacity=Math.min(131072,pool.capacity());
        boolean chainProtocol=Minecraft.getInstance().getConnection().hasChannel(ServerboundChainPackagePacket.TYPE)
                && Minecraft.getInstance().getConnection().hasChannel(ClientboundChainPackagePacket.TYPE)
                && Minecraft.getInstance().getConnection().hasChannel(ServerboundChainInteractionPacket.TYPE)
                && Minecraft.getInstance().getConnection().hasChannel(ClientboundChainInteractionPacket.TYPE);
        baked.upload(pool);styles=baked.styles();level=nextLevel;
        shaderBoundary=CreateManaIndustry.IRIS_ACTIVE
                ? com.iridium126.createmanaindustry.client.particles.shaderpack.PackageShaderHook.pipelineBoundary() : null;
        var position=Minecraft.getInstance().player.position();
        ox=Math.floor(position.x/16)*16;oy=Math.floor(position.y/16)*16;oz=Math.floor(position.z/16)*16;
        physics=new PackageMixedPhysicsGpu(capacity,chainProtocol?capacity:0,capacity,2,sources);
        worldPrefetch=new PackageWorldPrefetchGpu(nativeEpochs.incrementAndGet(),sources);
        nativeObservers=new PackageLightObserverClient(level,physics,pool,styles,nativeEpochs.incrementAndGet(),ox,oy,oz,reason->failure=reason);
        physics.sampleObservers(0);
        physics.publish();physics.source(pool,(float)ox,(float)oy,(float)oz);
        clock.reset();heartbeatTick=Long.MIN_VALUE;failure=null;current=this;
        // Shared GPU/model resources exist before readiness is advertised. TRACK then builds
        // epoch-specific query/checkpoint resources before a following OFFER can freeze Create.
        PackageAuthorityClient.capabilities(PackageAuthorityClient.readyFlags(true,chainProtocol));
    }
    private boolean drain(Function<String,String> sources) {
        for(int n=0;n<PACKETS_PER_FRAME && !packets.isEmpty();n++) {
            var packet=packets.removeFirst();var acquisition=regions.get(packet.region());
            if(nativeObservers!=null&&(packet.action()==ClientboundPackagePacket.OFFER||packet.action()==ClientboundPackagePacket.RELEASED))
                nativeObservers.authority(packet);
            if(acquisition!=null && (packet.epoch()!=acquisition.epoch() || packet.regionRevision()!=acquisition.revision())) {
                if(packet.action()!=ClientboundPackagePacket.OFFER)continue;
                // Migration/recycling needs a fresh namespace; discard every old flight before rebuilding.
                PackageAuthorityClient.closeAll("Package authority namespace changed",true);return false;
            }
            if(acquisition==null && packet.action()==ClientboundPackagePacket.OFFER) {
                if(regions.size()>=MAX_REGIONS) {refuse(packet);continue;}
                if(freeCheckpoints==null)freeCheckpoints=new PackageFreeCheckpointGpu(Math.min(131072,pool.capacity()),nativeEpochs.incrementAndGet(),sources);
                if(freeInteraction==null){freeInteraction=new PackageFreeInteractionClient(physics,pool,nativeEpochs.incrementAndGet(),ox,oy,oz,sources);freeInteraction.chainInteraction(chainInteraction);}
                var detector=new PackageDeltaGpu(Math.min(131072,pool.capacity()),sources,true);
                try {
                    acquisition=PackageAuthorityClient.openFreeAcquisition(packet.region(),packet.epoch(),packet.regionRevision(),
                            ox,oy,oz,physics,pool,detector,styles,this::light,reason->failure=reason);
                    acquisition.emergencyCheckpoints(freeCheckpoints);
                }catch(RuntimeException error){detector.close();throw error;}
                regions.put(packet.region(),acquisition);
            }
            if(acquisition!=null)acquisition.receive(packet);else if(packet.baseline()!=null)refuse(packet);
        }
        return true;
    }
    private int light(ClientboundPackagePacket offer) {
        var pose=offer.baseline().snapshot().pose();
        var pos=BlockPos.containing(pose.x(),pose.y()+offer.height()*.85f,pose.z());
        return net.minecraft.client.renderer.LightTexture.pack(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,pos),
                level.getBrightness(net.minecraft.world.level.LightLayer.SKY,pos));
    }
    private boolean drainChains(Function<String,String> sources) {
        for(int n=0;n<PACKETS_PER_FRAME && !chainPackets.isEmpty();n++) {
            var packet=chainPackets.removeFirst();
            if(chainAcquisition!=null && packet.epoch()!=chainAcquisition.epoch()) {
                if(packet.action()!=ClientboundChainPackagePacket.TRACK)continue;
                PackageAuthorityClient.closeAll("Chain authority namespace changed",true);return false;
            }
            if(packet.action()==ClientboundChainPackagePacket.CLOSE) {
                if(chainAcquisition!=null){PackageAuthorityClient.closeAll("Chain authority closed by server",false);return false;}
                continue;
            }
            if(chainAcquisition==null && packet.action()==ClientboundChainPackagePacket.TRACK)openChains(packet.epoch(),sources);
            if(chainAcquisition==null){if(packet.action()==ClientboundChainPackagePacket.OFFER)refuseChain(packet);continue;}
            if(packet.action()==ClientboundChainPackagePacket.TRACK)PackageChainClientOwnership.INSTANCE.track(packet.track());
            if(packet.action()==ClientboundChainPackagePacket.RELEASED)PackageChainClientOwnership.INSTANCE.terminalNotice(packet);
            chainAcquisition.receive(packet);
        }
        return true;
    }
    private void openChains(long epoch,Function<String,String> sources) {
        int capacity=Math.min(131072,pool.capacity());
        chainFrames=new PackageChainFrameScene(level,131072,ox,oy,oz,sources,net.neoforged.fml.ModList.get().isLoaded("sable"));
        chainFrames.beginFrame(renderFrame);
        chainTracks=new PackageChainTrackGpu(capacity,131072,1_048_576,sources);
        chainChannel=new PackageChainEventChannel(chainTracks,epoch,1,PackageAuthorityClient.encoder(),new PackageChainEventChannel.Transport() {
            @Override public Object prepare(long e,long r,long sequence,java.nio.ByteBuffer bytes) {
                var body=new byte[bytes.remaining()];bytes.get(body);
                return new ServerboundChainPackagePacket(ServerboundChainPackagePacket.EVENTS,e,0,null,0,0,0,sequence,body);
            }
            @Override public boolean send(long e,long r,long sequence,java.nio.ByteBuffer bytes){throw new IllegalStateException("Chain transport requires worker preparation");}
            @Override public boolean sendPrepared(long e,long r,long sequence,Object prepared,java.nio.ByteBuffer bytes) {
                if(Minecraft.getInstance().getConnection()==null)return false;
                PacketDistributor.sendToServer((ServerboundChainPackagePacket)prepared);return true;
            }
            @Override public void failed(String reason){failure=reason;}
        });
        var nativeOwnership=PackageChainClientOwnership.INSTANCE;
        chainAcquisition=new PackageChainAcquisitionGpu(level.dimension().location(),epoch,ox,oy,oz,physics,pool,chainTracks,chainChannel,
                styles,this::chainLight,
                nativeOwnership::hook,nativeOwnership::pendulum,new PackageChainAcquisitionGpu.Transport() {
            @Override public void control(int action,ClientboundChainPackagePacket checkpoint,int candidate) {
                if(Minecraft.getInstance().getConnection()!=null)PacketDistributor.sendToServer(ServerboundChainPackagePacket.control(action,epoch,checkpoint.baseline(),candidate));
            }
            @Override public void activated(ClientboundChainPackagePacket offer,ClientboundChainPackagePacket active,ClientboundChainPackagePacket.Track track,int candidate,int slot) {
                nativeOwnership.activated(offer,active,track,candidate);
            }
            @Override public void released(ClientboundChainPackagePacket offer,com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainAuthority.Baseline baseline) {
                nativeOwnership.released(offer,baseline);
            }
            @Override public void released(ClientboundChainPackagePacket offer,com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageChainAuthority.Baseline baseline,PackagePoseQueryGpu.Result pose) {
                nativeOwnership.released(offer,baseline,pose,ox,oy,oz);
            }
        });
        chainAcquisition.nativeOrigins(chainFrames::origin);
        chainQueries=new PackagePoseQueryGpu(capacity,epoch,sources);chainAcquisition.poseQueries(chainQueries);
        chainCheckpoints=new PackageChainCheckpointGpu(capacity,epoch,sources);checkpointVersion=-1;checkpointCount=-1;
        chainInteraction=new PackageChainInteractionClient(chainAcquisition,ox,oy,oz);
        if(freeInteraction!=null)freeInteraction.chainInteraction(chainInteraction);
        nativeOwnership.attach(chainAcquisition,chainCheckpoints,ox,oy,oz,reason->failure=reason);
    }
    private boolean chainCovered(ClientboundChainPackagePacket offer,ClientboundChainPackagePacket checkpoint) {
        if(!PackageChainClientOwnership.INSTANCE.covered(offer,checkpoint))return false;
        var probe=chainLightProbe(offer);if(probe==null)return false;
        var collisions=PackageCollisionRuntime.forLevel(level);
        return collisions.requestLight(probe.bounds())&&collisions.gpuLightCovered(probe.bounds());
    }
    private ChainLightProbe chainLightProbe(ClientboundChainPackagePacket packet) {
        var pendulum=PackageChainClientOwnership.INSTANCE.pendulum(packet,packet);
        var pose=packet.baseline().state().pose();
        var local=pendulum==null?new net.minecraft.world.phys.Vec3(pose.x(),pose.y()-9./16,pose.z()):new net.minecraft.world.phys.Vec3(pendulum.x(),pendulum.y(),pendulum.z());
        var world=chainFrames==null?null:chainFrames.worldPosition(packet.baseline().track(),local);
        if(world==null)return null;
        var pos=BlockPos.containing(world.x,world.y,world.z);
        return new ChainLightProbe(pos,new net.minecraft.world.phys.AABB(pos).inflate(3));
    }
    private int chainLight(ClientboundChainPackagePacket packet) {
        var probe=chainLightProbe(packet);
        if(probe==null)throw new IllegalArgumentException("Chain light frame unavailable");
        var collisions=PackageCollisionRuntime.forLevel(level);
        if(!collisions.requestLight(probe.bounds())||!collisions.gpuLightCovered(probe.bounds()))
            throw new IllegalArgumentException("Chain world light coverage unavailable");
        var pos=probe.block();
        return net.minecraft.client.renderer.LightTexture.pack(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,pos),
                level.getBrightness(net.minecraft.world.level.LightLayer.SKY,pos));
    }
    private static void refuseChain(ClientboundChainPackagePacket packet) {
        PacketDistributor.sendToServer(ServerboundChainPackagePacket.control(ServerboundChainPackagePacket.RELEASE,packet.epoch(),packet.baseline(),0));
    }
    private static void refuse(ClientboundPackagePacket packet) {
        PacketDistributor.sendToServer(ServerboundPackagePacket.control(ServerboundPackagePacket.RELEASE,
                packet.region(),packet.epoch(),packet.baseline()));
    }
    /** AuthorityClient closes admission and delta rings FIRST, then calls this from every exit path. */
    public static void revoked(String reason){if(current!=null)current.dispose(reason);}
    private void dispose(String reason) {
        if(current==this)current=null;
        if(forceCapture!=null)forceCapture.close();forceCapture=null;
        if(forceGpu!=null)forceGpu.close();forceGpu=null;
        if(nativeObservers!=null)nativeObservers.close();nativeObservers=null;
        if(chainInteraction!=null)chainInteraction.close();chainInteraction=null;
        if(freeInteraction!=null)freeInteraction.close();freeInteraction=null;
        PackageChainClientOwnership.INSTANCE.close();
        if(freeCheckpoints!=null)freeCheckpoints.close();freeCheckpoints=null;freeCheckpointVersion=-1;freeCheckpointCount=-1;
        if(chainCheckpoints!=null)chainCheckpoints.close();chainCheckpoints=null;checkpointVersion=-1;checkpointCount=-1;
        if(chainAcquisition!=null)chainAcquisition.close();chainAcquisition=null;
        if(chainQueries!=null)chainQueries.close();chainQueries=null;
        if(chainChannel!=null)chainChannel.close();else if(chainTracks!=null)chainTracks.close();chainChannel=null;chainTracks=null;
        if(pool!=null)pool.chainFrames(0,0);
        if(chainFrames!=null)chainFrames.close();chainFrames=null;
        packets.clear();chainPackets.clear();regions.clear();clock.reset();styles=Map.of();level=null;shaderBoundary=null;interpolation=1;
        if(pool!=null)pool.reset();pool=null;
        if(worldPrefetch!=null)worldPrefetch.close();worldPrefetch=null;
        if(physics!=null)physics.close();physics=null;
        failure=null;status=reason;retryAfter=System.nanoTime()+5_000_000_000L;
        statusRegions=statusBodies=statusActive=-1;
    }
}
