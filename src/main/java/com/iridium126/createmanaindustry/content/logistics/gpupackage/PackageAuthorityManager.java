package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import java.nio.ByteBuffer;
import java.util.*;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorPackage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-only world adapter. Never links Minecraft client classes or accepts inventory operations. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID)
public final class PackageAuthorityManager {
    private static final String ID="CMIGpuPackageId",GENERATION="CMIGpuPackageGeneration";
    private static final long OFFER_BUDGET_NANOS=250_000,RETRY_TICKS=40;
    private static final int MAX_OFFERS_PER_TICK=64,MAX_MESSAGES_PER_TICK=512;
    private static final Map<ServerLevel,Runtime> WORLDS=new IdentityHashMap<>();
    private static final class Peer {int flags,messages;long tick=-1;}
    private static final class Runtime {
        final ServerLevel level;
        final LinkedHashMap<UUID,Peer> peers=new LinkedHashMap<>();
        final Map<PackageRegion,PackageAuthorityRegion> regions=new HashMap<>();
        final IdentityHashMap<PackageEntity,EntityTarget> entities=new IdentityHashMap<>();
        final Map<PackageLease.Identity,EntityTarget> identities=new HashMap<>();
        final LinkedHashMap<PackageEntity,EntityTarget> discovery=new LinkedHashMap<>();
        boolean closing;
        Runtime(ServerLevel level){this.level=level;}
    }
    private static final class EntityTarget implements PackageAuthorityRegion.Target {
        final PackageEntity entity;
        final Runtime runtime;
        final PackageLease.Identity identity;
        PackageAuthorityRegion region;
        PackageAuthorityRegion.Snapshot checkpoint;
        long retry;
        EntityTarget(Runtime runtime,PackageEntity entity,PackageLease.Identity identity){this.runtime=runtime;this.entity=entity;this.identity=identity;}
        @Override public PackageLease.Identity identity(){return identity;}
        @Override public PackageAuthorityRegion.Snapshot snapshot() {
            Vec3 v=entity.getDeltaMovement();
            return new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(entity.getX(),entity.getY(),entity.getZ(),
                    (float)(v.x*20),(float)(v.y*20),(float)(v.z*20),entity.getYRot()),entity.onGround()?PackageAuthorityRegion.GROUNDED:0);
        }
        @Override public boolean eligible() {
            return !entity.isRemoved() && entity.isAlive() && !entity.isPassenger() && !entity.isVehicle()
                    && !entity.noPhysics && !entity.isInWater() && !entity.isInLava() && !entity.isOnFire()
                    && entity.insertionDelay>=20 && PackageItem.isPackage(entity.box);
        }
        @Override public void apply(PackageAuthorityRegion.Snapshot state) {
            var p=state.pose();
            if(entity.getX()!=p.x() || entity.getY()!=p.y() || entity.getZ()!=p.z())entity.setPos(p.x(),p.y(),p.z());
            Vec3 velocity=entity.getDeltaMovement();
            double vx=p.vx()/20.0,vy=p.vy()/20.0,vz=p.vz()/20.0;
            if(velocity.x!=vx || velocity.y!=vy || velocity.z!=vz)entity.setDeltaMovement(vx,vy,vz);
            if(entity.getYRot()!=p.yaw())entity.setYRot(p.yaw());
            boolean grounded=(state.flags()&PackageAuthorityRegion.GROUNDED)!=0;
            if(entity.onGround()!=grounded)entity.setOnGround(grounded);
            checkpoint=state;
        }
        @Override public void released(PackageAuthorityRegion.Baseline baseline) {
            var previous=region;region=null;checkpoint=null;retry=runtime.level.getGameTime()+RETRY_TICKS;
            if(!runtime.closing && runtime.entities.get(entity)==this && !entity.isRemoved())runtime.discovery.put(entity,this);
            if(previous!=null)send(runtime.level,previous,ClientboundPackagePacket.RELEASED,baseline,this);
        }
    }
    private PackageAuthorityManager() {}
    private static Runtime runtime(ServerLevel level){return WORLDS.computeIfAbsent(level,Runtime::new);}
    public static PackageLease.Identity identity(PackageEntity entity,ServerLevel level) {
        var tag=entity.getPersistentData();var data=PackageIdentityData.get(level);
        long id=tag.getLong(ID),generation=tag.getLong(GENERATION);
        if(id<=0 || generation<=0){id=data.identity();generation=1;tag.putLong(ID,id);tag.putLong(GENERATION,generation);}
        else data.observe(id);
        return new PackageLease.Identity(id,generation);
    }
    public static void identifyChain(ChainConveyorBlockEntity conveyor) {
        if(!(conveyor.getLevel() instanceof ServerLevel level))return;
        PackageIdentityData data=PackageIdentityData.get(level);
        boolean changed=false;
        for(var box:conveyor.getLoopingPackages())changed|=identify(box,data);
        for(var list:conveyor.getTravellingPackages().values())for(var box:list)changed|=identify(box,data);
        if(changed)conveyor.setChanged();
    }
    public static void identify(ChainConveyorPackage box,ServerLevel level){identify(box,PackageIdentityData.get(level));}
    private static boolean identify(ChainConveyorPackage box,PackageIdentityData data) {
        var identified=(PackageIdentified)box;
        if(identified.cmi$packageId()<=0 || identified.cmi$packageGeneration()<=0){identified.cmi$packageIdentity(data.identity(),1);return true;}
        data.observe(identified.cmi$packageId());return false;
    }
    @SubscribeEvent public static void onJoin(EntityJoinLevelEvent event) {
        if(event.getEntity() instanceof PackageEntity entity && event.getLevel() instanceof ServerLevel level) {
            var identity=identity(entity,level);
            if(ServerConfig.packageGpuAuthority){Runtime rt=runtime(level);
                // A duplicated external save tag gets a fresh identity, never aliases another live entity.
                if(rt.identities.containsKey(identity) && rt.identities.get(identity).entity!=entity) {
                    var tag=entity.getPersistentData();tag.putLong(ID,PackageIdentityData.get(level).identity());identity=identity(entity,level);
                }
                if(rt.entities.containsKey(entity))return;
                var target=new EntityTarget(rt,entity,identity);rt.entities.put(entity,target);rt.identities.put(identity,target);rt.discovery.put(entity,target);}
        }
    }
    @SubscribeEvent public static void onLeave(EntityLeaveLevelEvent event) {
        if(event.getEntity() instanceof PackageEntity entity && event.getLevel() instanceof ServerLevel level) {
            Runtime rt=WORLDS.get(level);if(rt==null)return;
            EntityTarget target=rt.entities.remove(entity);
            if(target!=null){rt.identities.remove(target.identity(),target);release(target,level.getGameTime());rt.discovery.remove(entity);}
        }
    }
    @SubscribeEvent public static void onTick(LevelTickEvent.Pre event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;
        Runtime rt=WORLDS.get(level);if(rt==null)return;
        if(!ServerConfig.packageGpuAuthority){close(rt);WORLDS.remove(level);return;}
        long tick=level.getGameTime();
        for(Iterator<PackageAuthorityRegion> it=rt.regions.values().iterator();it.hasNext();) {
            var region=it.next();region.tick(tick);
            var authority=level.getServer().getPlayerList().getPlayer(region.owner());
            if(region.expired(tick) || !rt.peers.containsKey(region.owner()) || !subscribed(authority,level,region.region())){region.close();it.remove();}
        }
        if(rt.peers.isEmpty())return;
        long deadline=System.nanoTime()+OFFER_BUDGET_NANOS;int attempts=0;
        int available=rt.discovery.size();
        while(attempts++<MAX_OFFERS_PER_TICK && available-->0 && System.nanoTime()<deadline) {
            var iterator=rt.discovery.values().iterator();EntityTarget target=iterator.next();iterator.remove();
            if(target.entity.isRemoved())continue;
            rt.discovery.put(target.entity,target);
            if(target.region!=null && target.region.baseline(target.identity())!=null)continue;
            target.region=null;
            if(target.retry>tick || !target.eligible())continue;
            PackageRegion key=PackageRegion.at(target.snapshot().pose());
            var region=rt.regions.get(key);
            if(region==null) {
                ServerPlayer authority=elect(rt,key);if(authority==null)continue;
                region=new PackageAuthorityRegion(key,authority.getUUID(),PackageIdentityData.get(level).epoch(),1,tick);
                rt.regions.put(key,region);
            }
            var baseline=region.offer(target,tick);if(baseline==null)continue;
            target.region=region;target.checkpoint=baseline.snapshot();target.retry=tick+RETRY_TICKS;
            rt.discovery.remove(target.entity);
            send(level,region,ClientboundPackagePacket.OFFER,baseline,target);
        }
    }
    private static ServerPlayer elect(Runtime rt,PackageRegion key) {
        // Evaluate subscribers once per region election, not once per package/frame.
        for(var peer:rt.peers.entrySet()) {
            if((peer.getValue().flags&ServerboundPackagePacket.FREE_READY)==0)continue;
            ServerPlayer player=rt.level.getServer().getPlayerList().getPlayer(peer.getKey());
            if(subscribed(player,rt.level,key))return player;
        }
        return null;
    }
    private static boolean subscribed(ServerPlayer player,ServerLevel level,PackageRegion key) {
        if(player==null || player.serverLevel()!=level || player.isSpectator())return false;
        double range=Math.min(256,level.getServer().getPlayerList().getViewDistance()*16);
        return Math.abs(player.getX()-(key.originX()+32))<=range && Math.abs(player.getZ()-(key.originZ()+32))<=range;
    }
    public static void receive(ServerboundPackagePacket packet,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player) || !ServerConfig.packageGpuAuthority)return;
        Runtime rt=runtime(player.serverLevel());long tick=rt.level.getGameTime();
        Peer peer=rt.peers.computeIfAbsent(player.getUUID(),id->new Peer());
        if(peer.tick!=tick){peer.tick=tick;peer.messages=0;}
        if(++peer.messages>MAX_MESSAGES_PER_TICK)return;
        if(packet.action()==ServerboundPackagePacket.CAPABILITIES) {
            peer.flags=packet.capabilities();
            if(peer.flags==0){rt.peers.remove(player.getUUID());for(var region:rt.regions.values())if(region.owner().equals(player.getUUID()))region.close();}
            return;
        }
        var region=rt.regions.get(packet.region());
        if(region==null || !region.owner().equals(player.getUUID()) || region.epoch()!=packet.epoch())return;
        if(!subscribed(player,rt.level,region.region())){region.close();return;}
        if(packet.action()==ServerboundPackagePacket.HEARTBEAT){region.heartbeat(player.getUUID(),packet.epoch(),tick);return;}
        if(packet.action()==ServerboundPackagePacket.DELTA) {
            PackageAuthorityRegion.Result result;
            try {
                ByteBuffer bytes=ByteBuffer.wrap(packet.changes());var changes=PackageDeltaCodec.decode(bytes);
                if(bytes.hasRemaining())return;
                result=region.delta(player.getUUID(),packet.epoch(),packet.revision(),packet.sequence(),tick,changes,4);
            }catch(RuntimeException invalid){return;}
            if(result==PackageAuthorityRegion.Result.ACCEPTED || result==PackageAuthorityRegion.Result.STALE
                    && packet.revision()==region.revision() && packet.sequence()==region.lastSequence())
                PacketDistributor.sendToPlayer(player,new ClientboundPackagePacket(ClientboundPackagePacket.ACK,rt.level.dimension().location(),
                        region.region(),region.epoch(),region.revision(),packet.sequence(),null,0,null,null,0,0));
            else if(result!=PackageAuthorityRegion.Result.STALE)region.close();
            return;
        }
        if(packet.action()==ServerboundPackagePacket.RELEASE) {
            region.release(player.getUUID(),packet.epoch(),packet.index(),packet.identity(),packet.leaseEpoch(),tick);return;
        }
        var previous=region.baseline(packet.identity());
        if(previous==null || previous.index()!=packet.index())return;
        var entity=findTarget(rt,packet.identity());if(entity==null)return;
        if(packet.action()==ServerboundPackagePacket.PREPARED) {
            var finalBaseline=region.prepared(player.getUUID(),packet.epoch(),packet.index(),packet.identity(),packet.leaseEpoch(),packet.revision(),tick);
            if(finalBaseline!=null){entity.checkpoint=finalBaseline.snapshot();send(rt.level,region,ClientboundPackagePacket.FINAL_BASELINE,finalBaseline,entity);}
        } else if(packet.action()==ServerboundPackagePacket.FINAL_READY) {
            try {
                if(region.finalReady(player.getUUID(),packet.epoch(),packet.index(),packet.identity(),packet.leaseEpoch(),packet.revision(),tick))
                    send(rt.level,region,ClientboundPackagePacket.ACTIVE,region.baseline(packet.identity()),entity);
            }catch(IllegalArgumentException invalid){release(entity,tick);}
        }
    }
    private static EntityTarget findTarget(Runtime rt,PackageLease.Identity identity) {
        return rt.identities.get(identity);
    }
    private static void send(ServerLevel level,PackageAuthorityRegion region,int action,PackageAuthorityRegion.Baseline baseline,EntityTarget target) {
        ServerPlayer owner=level.getServer().getPlayerList().getPlayer(region.owner());if(owner==null)return;
        PacketDistributor.sendToPlayer(owner,new ClientboundPackagePacket(action,level.dimension().location(),region.region(),region.epoch(),
                region.revision(),0,baseline,target.entity.getId(),target.entity.getUUID(),BuiltInRegistries.ITEM.getKey(target.entity.box.getItem()),
                target.entity.getBbWidth(),target.entity.getBbHeight()));
    }
    public static boolean paused(PackageEntity entity) {
        if(!(entity.level() instanceof ServerLevel level))return false;
        Runtime rt=WORLDS.get(level);EntityTarget target=rt==null?null:rt.entities.get(entity);
        if(target==null || target.region==null)return false;
        if(!ServerConfig.packageGpuAuthority){release(target,level.getGameTime());return false;}
        return target.region.paused(target.identity(),level.getGameTime());
    }
    /** Preserve the exact final checkpoint while super.tick runs server-owned lifecycle callbacks. */
    public static void maintainCheckpoint(PackageEntity entity) {
        if(!paused(entity))return;
        var target=WORLDS.get((ServerLevel)entity.level()).entities.get(entity);
        if(target.checkpoint!=null)target.apply(target.checkpoint);
    }
    public static void release(PackageEntity entity) {
        if(!(entity.level() instanceof ServerLevel level))return;
        Runtime rt=WORLDS.get(level);var target=rt==null?null:rt.entities.get(entity);
        if(target!=null)release(target,level.getGameTime());
    }
    private static void release(EntityTarget target,long tick) {
        var region=target.region;if(region==null)return;
        region.release(target.identity());
    }
    private static void close(Runtime rt){rt.closing=true;for(EntityTarget target:rt.entities.values())release(target,rt.level.getGameTime());rt.regions.values().forEach(PackageAuthorityRegion::close);}
    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id=event.getEntity().getUUID();for(Runtime rt:WORLDS.values()){
            rt.peers.remove(id);for(var region:rt.regions.values())if(region.owner().equals(id))region.close();}
    }
    @SubscribeEvent public static void onUnload(LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level){Runtime rt=WORLDS.remove(level);if(rt!=null)close(rt);}
    }
    @SubscribeEvent public static void onStopped(ServerStoppedEvent event){WORLDS.clear();}
}
