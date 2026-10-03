package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageRegion;
import java.util.HashMap;
import java.util.Map;

/** Client transport boundary. Never advertises readiness before every production GPU prerequisite exists. */
public final class PackageAuthorityClient {
    private static final Map<PackageRegion,PackageDeltaChannel> channels=new HashMap<>();
    private static final Map<PackageRegion,PackageDeltaChannel.Transport> transports=new HashMap<>();
    private static PackageDeltaChannel[] activeChannels=new PackageDeltaChannel[0];
    private static final Map<PackageRegion,PackageFreeAcquisitionGpu> acquisitions=new HashMap<>();
    private static PackageFreeAcquisitionGpu[] activeAcquisitions=new PackageFreeAcquisitionGpu[0];
    private static final java.util.function.BiPredicate<ClientboundPackagePacket,ClientboundPackagePacket> COVERAGE=PackageAuthorityClient::covered;
    private static final PackageControlQueue controls=new PackageControlQueue();
    private static final PackageControlQueue.Sender CONTROL_SENDER=message->{
        var connection=Minecraft.getInstance().getConnection();if(connection==null||!connection.hasChannel(ServerboundPackagePacket.TYPE))return false;
        var namespace=message.namespace();
        PacketDistributor.sendToServer(message.single()==null?ServerboundPackagePacket.controls(namespace.region(),namespace.epoch(),namespace.revision(),message.body()):
                ServerboundPackagePacket.control(message.action(),namespace.region(),namespace.epoch(),message.single()));return true;
    };
    private static boolean closing;
    private static int advertised;
    private static final class Encoding {
        static final PackageDeltaJournal.Encoder SHARED=new PackageDeltaJournal.Encoder(java.util.concurrent.Executors.newFixedThreadPool(2,task->{
            Thread thread=new Thread(task,"CMI package delta encoder");thread.setDaemon(true);return thread;
        }),4);
    }
    private PackageAuthorityClient() {}
    static int readyFlags(boolean freeReady,boolean chainProtocol) {
        if(!freeReady)return 0;
        return ServerboundPackagePacket.FREE_READY|(chainProtocol?ServerboundPackagePacket.CHAIN_READY:0);
    }
    static PackageDeltaJournal.Encoder encoder(){return Encoding.SHARED;}
    public static void capabilities(int flags) {
        var connection=Minecraft.getInstance().getConnection();
        if(connection==null || !connection.hasChannel(ServerboundPackagePacket.TYPE))return;
        PacketDistributor.sendToServer(ServerboundPackagePacket.capabilities(flags));advertised=flags;
    }
    public static int activePackages() {
        int count=0;for(var acquisition:activeAcquisitions)count+=acquisition.activeCount();return count;
    }
    public static void retireRegion(PackageFreeAcquisitionGpu acquisition){
        controls.clear(new PackageControlQueue.Namespace(acquisition.region(),acquisition.epoch(),acquisition.revision()));
        acquisition.beginClose();
    }
    public static void closeRegion(PackageFreeAcquisitionGpu acquisition){
        if(!acquisition.replacementReady()||!acquisitions.remove(acquisition.region(),acquisition))throw new IllegalStateException("Region retirement incomplete");
        acquisition.close();var channel=channels.remove(acquisition.region());if(channel!=null)channel.close();transports.remove(acquisition.region());
        activeAcquisitions=acquisitions.values().toArray(PackageFreeAcquisitionGpu[]::new);activeChannels=channels.values().toArray(PackageDeltaChannel[]::new);
    }
    /** Resolve a GPU pick through the exact admitted record identity. */
    public static ClientboundPackagePacket freePickOffer(PackagePoseQueryGpu.Result result){return freePickOffer(result,Long.MAX_VALUE);}
    public static ClientboundPackagePacket freePickOffer(PackagePoseQueryGpu.Result result,long submission) {
        for(var acquisition:activeAcquisitions){var offer=acquisition.activeOffer(result,submission);if(offer!=null)return offer;}return null;
    }
    /** Internal world-runtime entry. It does not advertise capabilities; the resource owner must
     * also provide physics publication, observer rendering, reload and failure cleanup. */
    public static PackageFreeAcquisitionGpu openFreeAcquisition(PackageRegion region,long epoch,long revision,
            double ox,double oy,double oz,PackageMixedPhysicsGpu physics,PackagePoolGpu pool,PackageDeltaGpu detector,
            Map<net.minecraft.resources.ResourceLocation,PackageModelCache.Style> styles,
            java.util.function.ToIntFunction<ClientboundPackagePacket> light,java.util.function.Consumer<String> failed) {
        if(acquisitions.containsKey(region))throw new IllegalStateException("Package acquisition already attached");
        java.util.Objects.requireNonNull(failed);
        var controlNamespace=new PackageControlQueue.Namespace(region,epoch,revision);
        var acquisition=new PackageFreeAcquisitionGpu(region,epoch,revision,ox,oy,oz,physics,pool,detector,styles,light,
                new PackageFreeAcquisitionGpu.Transport() {
                    @Override public void control(int action,ClientboundPackagePacket checkpoint) {
                        controls.offer(controlNamespace,action,checkpoint.baseline(),System.nanoTime());
                    }
                    @Override public void activated(ClientboundPackagePacket offer,ClientboundPackagePacket active,int candidate,int slot) {
                        PackageRenderOwnership.claimLightAfterAdmission(offer,active,slot);
                        control(ServerboundPackagePacket.VISIBLE_READY,active);
                    }

                    @Override public void released(ClientboundPackagePacket offer,
                            com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityRegion.Baseline baseline) {
                        PackageRenderOwnership.released(region,epoch,baseline);
                    }

                });
        try {
            var channel=open(region,detector.capacity(),epoch,revision,detector,new PackageDeltaChannel.Transport() {
                @Override public boolean send(long e,long r,long sequence,java.nio.ByteBuffer bytes){throw new IllegalStateException("Unwrapped package transport");}
                @Override public void released(int localId,long id,long generation){acquisition.serverReleased(localId,id,generation);}
                @Override public void failed(String reason){failed.accept(reason);}
            });
            acquisition.attachChannel(channel);
            acquisitions.put(region,acquisition);activeAcquisitions=acquisitions.values().toArray(PackageFreeAcquisitionGpu[]::new);
            return acquisition;
        }catch(RuntimeException failure){acquisition.close();throw failure;}
    }
    /** Internal adapter entry, only AFTER production resources/admission/final baseline are verified. */
    public static PackageDeltaChannel open(PackageRegion region,int capacity,long epoch,long revision,
                                          PackageDeltaGpu detector,PackageDeltaChannel.Transport lifecycle) {
        java.util.Objects.requireNonNull(region);java.util.Objects.requireNonNull(detector);java.util.Objects.requireNonNull(lifecycle);
        if(closing)throw new IllegalStateException("Package authority is restoring Create");
        var previous=channels.get(region);if(previous!=null && !previous.closed())throw new IllegalStateException("Region authority already open");
        boolean relativePositions=detector.relativePositions();
        boolean predictedPositions=detector.predictedPositions();
        var transport=new PackageDeltaChannel.Transport() {
            @Override public boolean batchEncoded(){return true;}
            @Override public boolean relativePositions(){return relativePositions;}
            @Override public boolean predictedPositions(){return predictedPositions;}
            @Override public Object prepare(long e,long r,long sequence,java.nio.ByteBuffer bytes) {
                byte[] body=new byte[bytes.remaining()];bytes.get(body);
                return new ServerboundPackagePacket(predictedPositions?ServerboundPackagePacket.PREDICTED_DELTA:
                        relativePositions?ServerboundPackagePacket.RELATIVE_DELTA:ServerboundPackagePacket.BATCH_DELTA,
                        0,region,e,0,null,0,r,sequence,body);
            }
            @Override public Object prepare(long e,long r,long sequence,long step,java.nio.ByteBuffer bytes){
                byte[] body=new byte[bytes.remaining()];bytes.get(body);
                return new ServerboundPackagePacket(predictedPositions?ServerboundPackagePacket.PREDICTED_DELTA:
                        relativePositions?ServerboundPackagePacket.RELATIVE_DELTA:ServerboundPackagePacket.BATCH_DELTA,
                        0,region,e,0,null,0,r,sequence,body,step);
            }
            @Override public boolean send(long e,long r,long sequence,java.nio.ByteBuffer bytes) {
                throw new IllegalStateException("Package transport requires worker-prepared packets");
            }
            @Override public boolean sendPrepared(long e,long r,long sequence,Object prepared,java.nio.ByteBuffer bytes) {
                if(Minecraft.getInstance().getConnection()==null)return false;
                PacketDistributor.sendToServer((ServerboundPackagePacket)prepared);return true;
            }
            @Override public void released(int localId,long id,long generation){lifecycle.released(localId,id,generation);}
            @Override public void failed(String reason){closeAll(reason,true);}
        };
        var channel=new PackageDeltaChannel(detector,capacity,epoch,revision,Encoding.SHARED,transport);
        channels.put(region,channel);transports.put(region,lifecycle);activeChannels=channels.values().toArray(PackageDeltaChannel[]::new);return channel;
    }
    /** Called inside the particle engine's GL state boundary, once per submitted main frame. */
    public static boolean pump() {
        if(channels.isEmpty() && acquisitions.isEmpty())return false;
        try {
            for(var acquisition:activeAcquisitions)acquisition.pump(64,COVERAGE);
            var controlResult=controls.flush(System.nanoTime(),CONTROL_SENDER,Long.MAX_VALUE);
            if(controlResult==PackageControlQueue.Result.TIMED_OUT)
                throw new IllegalStateException("Package monotonic clock reversed during control preparation");
            // Do not let a later delta overtake a queued release/activation transition.
            if(controlResult==PackageControlQueue.Result.BLOCKED)return true;
        }catch(RuntimeException failure) {
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] acquisition failed",failure);
            closeAll("Package acquisition failed: "+failure.getMessage(),true);return true;
        }
        // Rebuilt only when ownership changes; failure can close the map during this loop.
        for(var channel:activeChannels)if(!channel.closed()) {
            channel.profiling(com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.enabled());channel.pump(256);
        }
        return true;
    }
    private static boolean covered(ClientboundPackagePacket offer,ClientboundPackagePacket checkpoint) {
        if(!PackageWorldRuntime.forceReady())return false;
        var level=Minecraft.getInstance().level;
        if(level==null || !level.dimension().location().equals(offer.dimension()))return false;
        var p=checkpoint.baseline().snapshot().pose();double half=offer.width()*.5;
        var bounds=new net.minecraft.world.phys.AABB(p.x()-half,p.y(),p.z()-half,p.x()+half,p.y()+offer.height(),p.z()+half);
        return PackageCollisionRuntime.forLevel(level).gpuCovered(bounds.expandTowards(p.vx()*.15,p.vy()*.15,p.vz()*.15).inflate(2));
    }
    /** Success-only pool hook. Failure here revokes ownership without throwing after the engine swap. */
    public static void committed(long generation) {
        try{for(var acquisition:activeAcquisitions) {
            var channel=channels.get(acquisition.region());if(channel==null || channel.closed())continue;
            acquisition.committed(generation);acquisition.captureCommitted(channel,generation);
        }PackageWorldRuntime.committedChain(generation);}
        catch(RuntimeException failure) {
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] admission capture failed",failure);
            closeAll("Package admission capture failed: "+failure.getMessage(),true);
        }
    }
    public static String report() {
        if(channels.isEmpty())return "Package transport: active regions=0; world runtime="+PackageWorldRuntime.status()+"; capabilities="+advertised
                +"; "+com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageNetworkBudget.status();
        var result=new StringBuilder("Package transport:");
        for(var entry:channels.entrySet()) {
            var channel=entry.getValue();if(channel.closed()){result.append("\n").append(entry.getKey()).append(" retiring");continue;}
            var stats=channel.stats();var timing=channel.timings();
            var acquisition=acquisitions.get(entry.getKey());
            if(acquisition!=null)result.append(String.format(java.util.Locale.ROOT,"%nAcquisition %s: active=%d pending=%d",
                    entry.getKey(),acquisition.activeCount(),acquisition.pendingCount()));
            result.append(String.format(java.util.Locale.ROOT,"%n%s epoch=%d; captures=%d skipped=%d; readback=%d B; wire bodies=%d B; packets sent/acked=%d/%d; ACK dispatches=%d; capture CPU p50/p95=%.3f/%.3f ms; pump CPU p50/p95=%.3f/%.3f ms; packet preparation p50/p95=%.3f/%.3f ms; ACK RTT p50/p95=%.3f/%.3f ms",
                    entry.getKey(),channel.epoch(),stats.captures(),stats.skippedCaptures(),stats.headerBytes()+stats.payloadBytes(),stats.wireBytes(),stats.sentPackets(),stats.ackedPackets(),stats.ackDispatches(),
                    timing.capture().p50Millis(),timing.capture().p95Millis(),timing.pump().p50Millis(),timing.pump().p95Millis(),timing.packetPreparation().p50Millis(),timing.packetPreparation().p95Millis(),timing.roundTrip().p50Millis(),timing.roundTrip().p95Millis()));
        }
        return result.append("\nControl batching: ").append(controls.stats()).append("\nWorld runtime: ").append(PackageWorldRuntime.status())
                .append("\n").append(com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageNetworkBudget.status()).toString();
    }
    public static void closeAll(String reason,boolean notifyServer) {
        if(closing)return;
        if(advertised!=0 || !channels.isEmpty())
            com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.info("[CMI packages] pausing GPU authority: {}",reason);
        PackageChainClientOwnership.INSTANCE.close();
        boolean hadClaims=false;
        closing=true;
        try {
            controls.clear();
            var active=java.util.List.copyOf(channels.values());var listeners=java.util.List.copyOf(transports.values());
            var preparing=java.util.List.copyOf(acquisitions.values());acquisitions.clear();activeAcquisitions=new PackageFreeAcquisitionGpu[0];
            for(var acquisition:preparing)acquisition.close();
            hadClaims=PackageRenderOwnership.clear();
            channels.clear();transports.clear();activeChannels=new PackageDeltaChannel[0];for(var channel:active)channel.close();
            for(var listener:listeners)try{listener.failed(reason);}catch(RuntimeException failure){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] Package shutdown callback failed",failure);}
            if(notifyServer && (advertised!=0 || !active.isEmpty() || hadClaims))capabilities(0);
        }finally {
            controls.clear();
            PackageRenderOwnership.clear();
            advertised=0;
            try{PackageWorldRuntime.revoked(reason);}finally{closing=false;}
        }
    }
    public static void receive(ClientboundPackagePacket packet) {
        if(packet.action()==ClientboundPackagePacket.ENVIRONMENT_ACK){PackageWorldRuntime.enqueue(packet);return;}
        var level=Minecraft.getInstance().level;
        if(level==null || !level.dimension().location().equals(packet.dimension()))return;
        var channel=channels.get(packet.region());
        if(packet.action()==ClientboundPackagePacket.ACK) {
            if(channel!=null)channel.acknowledge(packet.epoch(),packet.regionRevision(),packet.sequence());return;
        }
        if(packet.action()==ClientboundPackagePacket.RELEASED) {
            var acquisition=acquisitions.get(packet.region());
            if(acquisition==null) {
                PackageRenderOwnership.released(packet.region(),packet.epoch(),packet.baseline());
                PackageWorldRuntime.enqueue(packet);
            }
            else acquisition.receive(packet);
            if(channel!=null)channel.released(packet.epoch(),packet.baseline());return;
        }
        if(packet.action()==ClientboundPackagePacket.ENVIRONMENT_ACK || packet.action()==ClientboundPackagePacket.OFFER || packet.action()==ClientboundPackagePacket.FINAL_BASELINE
                || packet.action()==ClientboundPackagePacket.ACTIVE) {
            if(packet.action()==ClientboundPackagePacket.OFFER) {
                var pose=packet.baseline().snapshot().pose();
                var box=new net.minecraft.world.phys.AABB(pose.x()-packet.width()/2,pose.y(),pose.z()-packet.width()/2,
                        pose.x()+packet.width()/2,pose.y()+packet.height(),pose.z()+packet.width()/2);
                // Prepare while Create still owns the object. Never wait for the worker or
                // freeze an object whose confirmed collision coverage is incomplete.
                PackageCollisionRuntime.forLevel(level).request(box.expandTowards(pose.vx()*.15,pose.vy()*.15,pose.vz()*.15).inflate(2));
            }
            var acquisition=acquisitions.get(packet.region());
            if(acquisition!=null && acquisition.receive(packet))return;
            if(PackageWorldRuntime.enqueue(packet))return;
            // No ready world resource owner: refuse before Create is frozen.
            PacketDistributor.sendToServer(ServerboundPackagePacket.control(ServerboundPackagePacket.RELEASE,
                    packet.region(),packet.epoch(),packet.baseline()));
        }
    }
    public static void receiveAcks(ClientboundPackageAckPacket packet) {
        var level=Minecraft.getInstance().level;
        if(level==null || !level.dimension().location().equals(packet.dimension()))return;
        var channel=channels.get(packet.region());
        if(channel!=null)channel.acknowledge(packet.epoch(),packet.revision(),packet.ranges());
    }
    /** Queue all GL work at the engine boundary; package ownership changes only after committed admission. */
    public static void receiveChain(ClientboundChainPackagePacket packet) {
        var level=Minecraft.getInstance().level;
        if(level==null || !level.dimension().location().equals(packet.dimension()))return;
        if(PackageWorldRuntime.enqueueChain(packet))return;
        if(packet.action()==ClientboundChainPackagePacket.OFFER && Minecraft.getInstance().getConnection()!=null)
            PacketDistributor.sendToServer(ServerboundChainPackagePacket.control(ServerboundChainPackagePacket.RELEASE,packet.epoch(),packet.baseline(),0));
    }
}
