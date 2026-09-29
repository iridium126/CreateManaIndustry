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
    private static boolean closing;
    private static final class Encoding {
        static final PackageDeltaJournal.Encoder SHARED=new PackageDeltaJournal.Encoder(java.util.concurrent.Executors.newFixedThreadPool(2,task->{
            Thread thread=new Thread(task,"CMI package delta encoder");thread.setDaemon(true);return thread;
        }),4);
    }
    private PackageAuthorityClient() {}
    /** Internal adapter entry, only AFTER production resources/admission/final baseline are verified. */
    public static PackageDeltaChannel open(PackageRegion region,int capacity,long epoch,long revision,
                                          PackageDeltaGpu detector,PackageDeltaChannel.Transport lifecycle) {
        java.util.Objects.requireNonNull(region);java.util.Objects.requireNonNull(detector);java.util.Objects.requireNonNull(lifecycle);
        if(closing)throw new IllegalStateException("Package authority is restoring Create");
        var previous=channels.get(region);if(previous!=null && !previous.closed())throw new IllegalStateException("Region authority already open");
        var transport=new PackageDeltaChannel.Transport() {
            @Override public Object prepare(long e,long r,long sequence,java.nio.ByteBuffer bytes) {
                byte[] body=new byte[bytes.remaining()];bytes.get(body);
                return new ServerboundPackagePacket(ServerboundPackagePacket.DELTA,0,region,e,0,null,0,r,sequence,body);
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
        if(channels.isEmpty())return false;
        // Rebuilt only when ownership changes; failure can close the map during this loop.
        for(var channel:activeChannels)if(!channel.closed()) {
            channel.profiling(com.iridium126.createmanaindustry.client.particles.engine.ParticleDiagnostics.INSTANCE.enabled());channel.pump(256);
        }
        return true;
    }
    public static String report() {
        if(channels.isEmpty())return "Package transport: active regions=0; production resource readiness is disabled.";
        var result=new StringBuilder("Package transport:");
        for(var entry:channels.entrySet()) {
            var channel=entry.getValue();var stats=channel.stats();var timing=channel.timings();
            result.append(String.format(java.util.Locale.ROOT,"%n%s epoch=%d; captures=%d skipped=%d; readback=%d B; wire bodies=%d B; packets sent/acked=%d/%d; ACK dispatches=%d; capture CPU p50/p95=%.3f/%.3f ms; pump CPU p50/p95=%.3f/%.3f ms; packet preparation p50/p95=%.3f/%.3f ms; ACK RTT p50/p95=%.3f/%.3f ms",
                    entry.getKey(),channel.epoch(),stats.captures(),stats.skippedCaptures(),stats.headerBytes()+stats.payloadBytes(),stats.wireBytes(),stats.sentPackets(),stats.ackedPackets(),stats.ackDispatches(),
                    timing.capture().p50Millis(),timing.capture().p95Millis(),timing.pump().p50Millis(),timing.pump().p95Millis(),timing.packetPreparation().p50Millis(),timing.packetPreparation().p95Millis(),timing.roundTrip().p50Millis(),timing.roundTrip().p95Millis()));
        }
        return result.toString();
    }
    public static void closeAll(String reason,boolean notifyServer) {
        if(channels.isEmpty() || closing)return;
        closing=true;
        try {
            var active=java.util.List.copyOf(channels.values());var listeners=java.util.List.copyOf(transports.values());
            channels.clear();transports.clear();activeChannels=new PackageDeltaChannel[0];for(var channel:active)channel.close();
            for(var listener:listeners)try{listener.failed(reason);}catch(RuntimeException failure){com.iridium126.createmanaindustry.CreateManaIndustry.LOGGER.error("[CMI packages] Create restore callback failed",failure);}
            if(notifyServer && Minecraft.getInstance().getConnection()!=null)PacketDistributor.sendToServer(ServerboundPackagePacket.capabilities(0));
        }finally{closing=false;}
    }
    public static void receive(ClientboundPackagePacket packet) {
        var level=Minecraft.getInstance().level;
        if(level==null || !level.dimension().location().equals(packet.dimension()))return;
        var channel=channels.get(packet.region());
        if(packet.action()==ClientboundPackagePacket.ACK) {
            if(channel!=null)channel.acknowledge(packet.epoch(),packet.regionRevision(),packet.sequence());return;
        }
        if(packet.action()==ClientboundPackagePacket.RELEASED) {
            if(channel!=null)channel.released(packet.epoch(),packet.baseline());return;
        }
        if(packet.action()==ClientboundPackagePacket.OFFER || packet.action()==ClientboundPackagePacket.FINAL_BASELINE
                || packet.action()==ClientboundPackagePacket.ACTIVE) {
            if(packet.action()==ClientboundPackagePacket.OFFER) {
                var pose=packet.baseline().snapshot().pose();
                var box=new net.minecraft.world.phys.AABB(pose.x()-packet.width()/2,pose.y(),pose.z()-packet.width()/2,
                        pose.x()+packet.width()/2,pose.y()+packet.height(),pose.z()+packet.width()/2);
                // Prepare while Create still owns the object. Never wait for the worker or
                // freeze an object whose confirmed collision coverage is incomplete.
                PackageCollisionRuntime.forLevel(level).request(box.expandTowards(pose.vx()*.15,pose.vy()*.15,pose.vz()*.15).inflate(2));
            }
            // Collision coverage, production model admission and observer
            // rendering are not wired yet. Explicit refusal avoids a silent acquisition timeout.
            PacketDistributor.sendToServer(ServerboundPackagePacket.control(ServerboundPackagePacket.RELEASE,
                    packet.region(),packet.epoch(),packet.baseline()));
        }
    }
}
