package com.iridium126.createmanaindustry.client.particles.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.*;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.BufferUtils;

/** Entity-free observer streams share the existing committed GPU admission boundary. */
public final class PackageLightObserverClient implements AutoCloseable,PackageObserverGpuController.Lifecycle {
    private static PackageLightObserverClient current;
    private enum Phase {HIDDEN,VISIBLE,ACTIVE,RETIRED,CLOSED}
    private static final class Entry {
        final PackageRegion region;final long epoch,stream;final PackageLease.Identity identity;final int local,candidate;
        Phase phase=Phase.HIDDEN,capturedPhase;boolean captured;
        Entry(PackageRegion r,long e,long s,PackageLease.Identity i,int l,int c){region=r;epoch=e;stream=s;identity=i;local=l;candidate=c;}
    }
    private final ClientLevel level;
    private final PackageMixedPhysicsGpu physics;
    private final PackagePoolGpu pool;
    private final Map<ResourceLocation,PackageModelCache.Style> styles;
    private final PackageObserverGpuController controller;
    private final PackageAdmissionTracker admissions;
    private final Consumer<String> failure;
    private final Set<PackageRegion> subscriptions;
    private final Map<Integer,Entry> locals=new HashMap<>(),candidates=new HashMap<>();
    private final LinkedHashSet<Entry> awaiting=new LinkedHashSet<>();
    private final java.nio.ByteBuffer metadata=BufferUtils.createByteBuffer(64*80);
    private final List<Entry> uploads=new ArrayList<>(64),captureEntries=new ArrayList<>(128);
    private final List<PackageAdmissionTracker.Expected> expected=new ArrayList<>(128);
    private long requestedTick=Long.MIN_VALUE,capture;
    private int active;
    private boolean closed;
    public PackageLightObserverClient(ClientLevel level,PackageMixedPhysicsGpu physics,PackagePoolGpu pool,Map<ResourceLocation,PackageModelCache.Style> styles,long resourceEpoch,double ox,double oy,double oz,Consumer<String> failure) {
        this.level=level;this.physics=physics;this.pool=pool;this.styles=Map.copyOf(styles);this.failure=failure;
        var p=Minecraft.getInstance().player.position();int x=(int)Math.floor(p.x/64-.5),y=(int)Math.floor(p.y/64-.5),z=(int)Math.floor(p.z/64-.5);var regions=new LinkedHashSet<PackageRegion>();
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)regions.add(new PackageRegion(x+dx,y+dy,z+dz));subscriptions=Set.copyOf(regions);
        controller=new PackageObserverGpuController(level.dimension().location(),subscriptions,physics.observers(),resourceEpoch,ox,oy,oz,this);admissions=new PackageAdmissionTracker(128,resourceEpoch);current=this;
    }
    public static void receive(ClientboundPackageObserverPacket packet){var c=current;if(c!=null&&!c.closed&&c.level==Minecraft.getInstance().level){var mc=Minecraft.getInstance();var info=mc.getConnection()==null||mc.player==null?null:mc.getConnection().getPlayerInfo(mc.player.getUUID());long latency=info==null?0:Math.clamp(info.getLatency()*500_000L,0,5_000_000_000L);c.controller.enqueue(packet,System.nanoTime(),latency);}}
    public void prepare(long now) {
        admissions.poll(result->{var e=candidates.get(result.candidate());if(e==null||!e.identity.equals(new PackageLease.Identity(result.id(),result.generation())))throw new IllegalStateException("Light observer admission identity");e.captured=false;if(e.phase!=e.capturedPhase)return;
            if(e.phase==Phase.RETIRED){if(result.accepted())throw new IllegalStateException("Retired observer still visible");pool.retireIdentity(e.candidate,e.identity.id(),e.identity.generation());PackageLightClient.observed(e.identity,false);e.phase=Phase.CLOSED;awaiting.remove(e);locals.remove(e.local);return;}
            if(!result.accepted()){retired(e.identity,e.local);return;}if(e.phase==Phase.HIDDEN){pool.setHidden(e.candidate,false);e.phase=Phase.VISIBLE;}else if(e.phase==Phase.VISIBLE){e.phase=Phase.ACTIVE;active++;PackageLightClient.observed(e.identity,true);awaiting.remove(e);}});
        var connection=Minecraft.getInstance().getConnection();long tick=level.getGameTime();if(connection!=null&&connection.hasChannel(ServerboundPackageObserverPacket.TYPE)&&(requestedTick==Long.MIN_VALUE||tick-requestedTick>=40)){requestedTick=tick;for(var r:subscriptions)if(controller.stream(r)==0)PacketDistributor.sendToServer(new ServerboundPackageObserverPacket(ServerboundPackageObserverPacket.SUBSCRIBE,r,0));}
        controller.prepare(now,64);
        flushUploads();
    }
    public void committed(long generation){
        controller.committed(generation);
        var iterator=awaiting.iterator();
        while(iterator.hasNext()&&admissions.pending()<4){captureEntries.clear();expected.clear();
            while(iterator.hasNext()&&captureEntries.size()<128){var e=iterator.next();if(e.captured)continue;if(!captureEntries.isEmpty()&&e.candidate!=captureEntries.getLast().candidate+1)break;captureEntries.add(e);expected.add(new PackageAdmissionTracker.Expected(e.identity.id(),e.identity.generation(),e.phase==Phase.VISIBLE?0:PackagePoolGpu.HIDDEN));}
            if(captureEntries.isEmpty())continue;
            if(!admissions.submit(pool,captureEntries.getFirst().candidate,expected,capture++))return;
            for(var e:captureEntries){e.captured=true;e.capturedPhase=e.phase;}
        }
    }
    @Override public int availableSlots(){return Math.min(physics.observerRemaining(),Math.min(pool.capacity(),131072)-pool.metadataCount()-uploads.size());}
    @Override public boolean supports(PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member){return styles.containsKey(member.metadata().model())&&!pool.reservesIdentity(member.identity().id(),member.identity().generation());}
    @Override public void uploaded(PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member,int local){throw new IllegalStateException("Observer scope missing");}
    @Override public void uploaded(PackageRegion region,long epoch,long stream,PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual> member,int local) {
        if(uploads.size()==64)flushUploads();
        int candidate=pool.metadataCount()+uploads.size();var e=new Entry(region,epoch,stream,member.identity(),local,candidate);locals.put(local,e);candidates.put(candidate,e);awaiting.add(e);uploads.add(e);
        var v=member.metadata();var style=styles.get(v.model());int offset=metadata.position();for(int i=0;i<80;i+=8)metadata.putLong(offset+i,0);
        metadata.putLong(offset,member.identity().id()).putLong(offset+8,member.identity().generation()).putInt(offset+16,physics.observerBodyIndex(local)).putInt(offset+20,style.box()).putInt(offset+24,PackagePoolGpu.NO_MESH).putInt(offset+28,PackagePoolGpu.HIDDEN).putInt(offset+60,0xf000f0).putFloat(offset+76,v.height()*.85f);metadata.position(offset+80);
    }
    private void flushUploads(){if(uploads.isEmpty())return;metadata.flip();pool.appendMetadata(metadata,uploads.size());metadata.clear();uploads.clear();}
    @Override public void retired(PackageLease.Identity identity,int local){flushUploads();var e=locals.get(local);if(e==null||!e.identity.equals(identity)||e.phase==Phase.RETIRED||e.phase==Phase.CLOSED)return;if(e.phase==Phase.ACTIVE)active--;pool.setHidden(e.candidate,true);e.phase=Phase.RETIRED;awaiting.add(e);}
    @Override public void namespaceRetired(PackageRegion region,long epoch,long stream){for(var e:new ArrayList<>(locals.values()))if(e.region.equals(region)&&e.epoch==epoch&&e.stream==stream)retired(e.identity,e.local);}
    @Override public void fallback(String reason){failure.accept(reason);}
    public void authority(ClientboundPackagePacket packet){if(packet.action()==ClientboundPackagePacket.OFFER)controller.suspend(packet.region(),System.nanoTime());}
    public int active(){return active;}
    @Override public void close(){if(closed)return;closed=true;if(current==this)current=null;var connection=Minecraft.getInstance().getConnection();if(connection!=null&&connection.hasChannel(ServerboundPackageObserverPacket.TYPE))for(var region:subscriptions){long stream=controller.stream(region);if(stream!=0)PacketDistributor.sendToServer(new ServerboundPackageObserverPacket(ServerboundPackageObserverPacket.UNSUBSCRIBE,region,stream));}for(var e:locals.values())PackageLightClient.observed(e.identity,false);admissions.close();controller.close();locals.clear();candidates.clear();awaiting.clear();}
}
