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
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
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
    private static final int MAX_CONTROL_RECORDS_PER_TICK=4096;
    private static final int MAX_OBSERVER_REGIONS=8,OBSERVER_RECORDS_PER_BATCH=64,MAX_OBSERVER_POLLS=64;
    private static final long OBSERVER_BUDGET_NANOS=250_000;
    private static final Map<ServerLevel,Runtime> WORLDS=new IdentityHashMap<>();
    private static final class Peer {int flags,messages,controlRecords;long tick=-1;}
    private record ObserverKey(UUID player,PackageRegion region) {}
    private static final class Observer {
        final PackageAuthorityRegion authority;
        final boolean membershipOnly;
        PackageObserverFeed.Cursor cursor;
        Observer(PackageAuthorityRegion authority,long stream,boolean membershipOnly){this.authority=authority;this.membershipOnly=membershipOnly;reset(stream);}
        void reset(long stream){cursor=membershipOnly?authority.observers().subscribeMembership(stream):authority.observers().subscribe(stream);}
    }
    private static final class Runtime {
        final ServerLevel level;
        final LinkedHashMap<UUID,Peer> peers=new LinkedHashMap<>();
        final Map<PackageRegion,PackageAuthorityRegion> regions=new HashMap<>();
        final IdentityHashMap<PackageEntity,EntityTarget> entities=new IdentityHashMap<>();
        final Map<PackageLease.Identity,EntityTarget> identities=new HashMap<>();
        final LinkedHashMap<PackageEntity,EntityTarget> discovery=new LinkedHashMap<>();
        final LinkedHashMap<ObserverKey,Observer> observers=new LinkedHashMap<>();
        final Map<PackageAuthorityRegion,PackageAckRanges.Builder> pendingAcks=new LinkedHashMap<>();
        final ArrayDeque<PackageAckRanges.Builder> spareAcks=new ArrayDeque<>();
        boolean closing;
        Runtime(ServerLevel level){this.level=level;}
    }
    private static final class EntityTarget implements PackageAuthorityRegion.Target {
        final PackageEntity entity;
        final Runtime runtime;
        final PackageLease.Identity identity;
        PackageAuthorityRegion region;
        PackageAuthorityRegion.Snapshot checkpoint;
        ServerPlayerConnection nativeOwner;
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
                    && entity.getTeam()==null && !entity.isShiftKeyDown()
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
            ServerPlayerConnection recipient=nativeOwner;nativeOwner=null;
            var previous=region;region=null;checkpoint=null;retry=runtime.level.getGameTime()+RETRY_TICKS;
            if(!runtime.closing && runtime.entities.get(entity)==this && !entity.isRemoved())runtime.discovery.put(entity,this);
            try {if(previous!=null)send(runtime.level,previous,ClientboundPackagePacket.RELEASED,baseline,this);}
            finally {
                // Immediate recovery must not await the next 3-tick/60-tick vanilla update.
                // Do NOT modify the shared server codec here: the native tracker replaces
                // this connection's next relative position with an absolute rebase.
                if(recipient!=null&&!entity.isRemoved()&&recipient.getPlayer().serverLevel()==runtime.level
                        &&!recipient.getPlayer().hasDisconnected()) {
                    recipient.send(new ClientboundTeleportEntityPacket(entity));
                    recipient.send(new ClientboundSetEntityMotionPacket(entity));
                }
            }
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
        pumpObservers(rt);
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
    /** Flush only exact successful sequences at this level's tick end. No per-package scan;
     * normal batches wait less than one tick, and bounded overflow flushes before accepting
     * another sequence. Failed transport restores Create instead of dropping confirmations. */
    @SubscribeEvent public static void onTickPost(LevelTickEvent.Post event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;
        Runtime rt=WORLDS.get(level);if(rt==null || rt.pendingAcks.isEmpty())return;
        for(var iterator=rt.pendingAcks.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();var region=entry.getKey();
            var player=level.getServer().getPlayerList().getPlayer(region.owner());
            if(player!=null && player.serverLevel()==level)flushAcks(rt,region,player,entry.getValue());
            else region.close();
            iterator.remove();
            entry.getValue().clear();if(rt.spareAcks.size()<64)rt.spareAcks.addLast(entry.getValue());
        }
    }
    private static void queueAck(Runtime rt,PackageAuthorityRegion region,ServerPlayer player,long sequence) {
        if(!player.connection.hasChannel(ClientboundPackageAckPacket.TYPE)) {
            PacketDistributor.sendToPlayer(player,new ClientboundPackagePacket(ClientboundPackagePacket.ACK,rt.level.dimension().location(),
                    region.region(),region.epoch(),region.revision(),sequence,null,0,null,null,0,0));return;
        }
        var builder=rt.pendingAcks.get(region);
        if(builder==null){builder=rt.spareAcks.pollFirst();if(builder==null)builder=new PackageAckRanges.Builder();rt.pendingAcks.put(region,builder);}
        if(!builder.add(sequence)) {
            if(!flushAcks(rt,region,player,builder))return;
            if(!builder.add(sequence))throw new IllegalStateException("Package ACK cannot fit an empty batch");
        }
    }
    private static boolean flushAcks(Runtime rt,PackageAuthorityRegion region,ServerPlayer player,PackageAckRanges.Builder builder) {
        if(builder.empty())return true;
        try {
            PacketDistributor.sendToPlayer(player,new ClientboundPackageAckPacket(rt.level.dimension().location(),region.region(),
                    region.epoch(),region.revision(),builder.snapshot()));
            builder.clear();return true;
        }catch(RuntimeException failure) {
            CreateManaIndustry.LOGGER.warn("[CMI packages] ACK enqueue failed; restoring Create",failure);
            region.close();return false;
        }
    }
    /** Chain authority shares the same spatial election, capability and subscription boundary. */
    public static boolean chainPeer(ServerLevel level,UUID owner,PackageRegion region) {
        Runtime rt=WORLDS.get(level);Peer peer=rt==null?null:rt.peers.get(owner);
        ServerPlayer player=level.getServer().getPlayerList().getPlayer(owner);
        return peer!=null && (peer.flags&ServerboundPackagePacket.CHAIN_READY)!=0 && player!=null && player.serverLevel()==level
                && player.connection.hasChannel(ServerboundChainPackagePacket.TYPE) && (region==null || subscribed(player,level,region));
    }
    public static boolean hasChainPeer(ServerLevel level) {
        Runtime rt=WORLDS.get(level);if(rt==null)return false;
        for(UUID owner:rt.peers.keySet())if(chainPeer(level,owner,null))return true;return false;
    }
    public static ServerPlayer electChain(ServerLevel level,PackageRegion region) {
        Runtime rt=WORLDS.get(level);if(rt==null)return null;
        for(UUID owner:rt.peers.keySet())if(chainPeer(level,owner,region))return level.getServer().getPlayerList().getPlayer(owner);return null;
    }
    private static boolean subscribed(ServerPlayer player,ServerLevel level,PackageRegion key) {
        if(player==null || player.serverLevel()!=level || player.isSpectator())return false;
        double range=Math.min(256,level.getServer().getPlayerList().getViewDistance()*16);
        return Math.abs(player.getX()-(key.originX()+32))<=range && Math.abs(player.getZ()-(key.originZ()+32))<=range;
    }
    /** Independent opt-in; observing never makes the sender an authority candidate. An old stream's
     * unsubscribe cannot revoke a newer baseline after a slow-reader reset or resubscription. */
    public static void observe(ServerboundPackageObserverPacket packet,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player) || !ServerConfig.packageGpuAuthority
                || !player.connection.hasChannel(ClientboundPackageObserverPacket.TYPE))return;
        Runtime rt=runtime(player.serverLevel());long tick=rt.level.getGameTime();
        Peer peer=rt.peers.computeIfAbsent(player.getUUID(),id->new Peer());
        if(peer.tick!=tick){peer.tick=tick;peer.messages=0;peer.controlRecords=0;}
        if(++peer.messages>MAX_MESSAGES_PER_TICK)return;
        var key=new ObserverKey(player.getUUID(),packet.region());var previous=rt.observers.get(key);
        if(packet.action()==ServerboundPackageObserverPacket.UNSUBSCRIBE) {
            if(previous!=null && previous.cursor.stream()==packet.stream()) {
                previous.authority.observers().unsubscribe(previous.cursor);rt.observers.remove(key);
            }return;
        }
        var region=rt.regions.get(packet.region());
        // Production consumes vanilla poses. The pose-stream variant remains a harness path;
        // subscribing it here would add a second downlink for every moving package.
        if(packet.action()!=ServerboundPackageObserverPacket.SUBSCRIBE_NATIVE)return;
        if(region==null || region.owner().equals(player.getUUID()) || !subscribed(player,rt.level,packet.region()))return;
        int regions=0;for(var observer:rt.observers.keySet())if(observer.player().equals(player.getUUID()))regions++;
        if(previous==null && regions>=MAX_OBSERVER_REGIONS)return;
        if(previous!=null)previous.authority.observers().unsubscribe(previous.cursor);
        rt.observers.put(key,new Observer(region,PackageIdentityData.get(rt.level).epoch(),true));
    }
    private static void pumpObservers(Runtime rt) {
        if(rt.observers.isEmpty())return;
        long deadline=System.nanoTime()+OBSERVER_BUDGET_NANOS;
        int idle=0,polls=0;
        while(!rt.observers.isEmpty() && idle<rt.observers.size() && polls++<MAX_OBSERVER_POLLS && System.nanoTime()<deadline) {
            var iterator=rt.observers.entrySet().iterator();var entry=iterator.next();
            var key=entry.getKey();var observer=entry.getValue();iterator.remove();
            var player=rt.level.getServer().getPlayerList().getPlayer(key.player());
            if(!rt.peers.containsKey(key.player()) || rt.regions.get(key.region())!=observer.authority
                    || observer.authority.expired(rt.level.getGameTime()) || !subscribed(player,rt.level,key.region())
                    || !player.connection.hasChannel(ClientboundPackageObserverPacket.TYPE)) {
                observer.authority.observers().unsubscribe(observer.cursor);
                if(player!=null && player.serverLevel()==rt.level && player.connection.hasChannel(ClientboundPackageObserverPacket.TYPE))
                    closeObserver(rt,player,observer);
                continue;
            }
            try {
                PackageObserverFeed.Batch<PackageAuthorityRegion.Target> batch;
                try {batch=observer.authority.observers().poll(observer.cursor,OBSERVER_RECORDS_PER_BATCH);}
                catch(PackageObserverFeed.LaggedException lagged) {
                    // Reliable stream reset invalidates all previous GPU members. The simulation
                    // continues; no journal slot or network observer can hold up the authority.
                    observer.authority.observers().unsubscribe(observer.cursor);
                    observer.reset(PackageIdentityData.get(rt.level).epoch());
                    batch=observer.authority.observers().poll(observer.cursor,OBSERVER_RECORDS_PER_BATCH);
                }
                if(batch!=null) {
                    idle=0;
                    var members=new ArrayList<PackageObserverFeed.Member<ClientboundPackageObserverPacket.Visual>>(batch.baselines().size());
                    for(var member:batch.baselines()) {
                        var target=(EntityTarget)member.metadata();
                        var visual=new ClientboundPackageObserverPacket.Visual(target.entity.getId(),target.entity.getUUID(),
                                BuiltInRegistries.ITEM.getKey(target.entity.box.getItem()),target.entity.getBbWidth(),target.entity.getBbHeight());
                        members.add(new PackageObserverFeed.Member<>(member.index(),member.identity(),member.leaseEpoch(),member.revision(),
                                observer.membershipOnly?ClientboundPackageObserverPacket.NO_POSE:member.state(),visual,member.stateTick()));
                    }
                    PacketDistributor.sendToPlayer(player,new ClientboundPackageObserverPacket(rt.level.dimension().location(),key.region(),
                            observer.authority.epoch(),observer.authority.revision(),batch.stream(),batch.sequence(),
                            (batch.reset()?ClientboundPackageObserverPacket.RESET:0)|(batch.complete()?ClientboundPackageObserverPacket.COMPLETE:0)
                                    |(observer.membershipOnly?ClientboundPackageObserverPacket.MEMBERSHIP_ONLY:0),members,batch.changes(),rt.level.getGameTime(),batch.stateTicks()));
                }else idle++;
                // Rotate even idle subscriptions so a large population cannot starve the tail.
                rt.observers.put(key,observer);
            }catch(RuntimeException failure) {
                observer.authority.observers().unsubscribe(observer.cursor);
                CreateManaIndustry.LOGGER.warn("[CMI packages] observer stream failed; retaining Create replication",failure);
                closeObserver(rt,player,observer);
            }
        }
    }
    private static void closeObserver(Runtime rt,ServerPlayer player,Observer observer) {
        try {PacketDistributor.sendToPlayer(player,new ClientboundPackageObserverPacket(rt.level.dimension().location(),observer.authority.region(),
                observer.authority.epoch(),observer.authority.revision(),observer.cursor.stream(),0,
                ClientboundPackageObserverPacket.CLOSE|(observer.membershipOnly?ClientboundPackageObserverPacket.MEMBERSHIP_ONLY:0),List.of(),List.of(),rt.level.getGameTime(),PackageObserverTimes.zeros(0)));}
        catch(RuntimeException failure){CreateManaIndustry.LOGGER.debug("[CMI packages] observer close could not be enqueued",failure);}
    }
    public static void receive(ServerboundPackagePacket packet,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player) || !ServerConfig.packageGpuAuthority)return;
        Runtime rt=runtime(player.serverLevel());long tick=rt.level.getGameTime();
        Peer peer=rt.peers.computeIfAbsent(player.getUUID(),id->new Peer());
        if(peer.tick!=tick){peer.tick=tick;peer.messages=0;peer.controlRecords=0;}
        if(++peer.messages>MAX_MESSAGES_PER_TICK){
            var affected=rt.regions.get(packet.region());
            if(affected!=null&&affected.owner().equals(player.getUUID())&&affected.epoch()==packet.epoch())affected.close();
            return;
        }
        if(packet.action()==ServerboundPackagePacket.CAPABILITIES) {
            peer.flags=packet.capabilities();
            if(peer.flags==0){rt.peers.remove(player.getUUID());for(var region:rt.regions.values())if(region.owner().equals(player.getUUID()))region.close();}
            return;
        }
        var region=rt.regions.get(packet.region());
        if(region==null || !region.owner().equals(player.getUUID()) || region.epoch()!=packet.epoch())return;
        if(!subscribed(player,rt.level,region.region())){region.close();return;}
        if(packet.action()==ServerboundPackagePacket.HEARTBEAT){region.heartbeat(player.getUUID(),packet.epoch(),tick);return;}
        if(packet.action()==ServerboundPackagePacket.CONTROL_BATCH) {
            if(packet.revision()!=region.revision())return;
            try {int count=PackageControlBatchCodec.visitValidated(ByteBuffer.wrap(packet.changes()),
                    MAX_CONTROL_RECORDS_PER_TICK-peer.controlRecords,(action,index,id,generation,lease,revision)->
                    control(rt,region,player,action,index,new PackageLease.Identity(id,generation),lease,revision,tick));
                peer.controlRecords+=count;
                // A sent release may concern a sleeping body that heartbeats keep alive.
                // Budget rejection must explicitly hand back, never silently lose that control.
                if(count==0)region.close();
            }
            catch(RuntimeException invalid){
                CreateManaIndustry.LOGGER.debug("[CMI packages] invalid/failed control batch; restoring Create",invalid);
                // Syntax is validated before callbacks. If a callback/transport fails, revoke
                // even an already-processed prefix; a lost terminal control cannot stay owned.
                region.close();
            }
            return;
        }
        if(ServerboundPackagePacket.deltaAction(packet.action())) {
            PackageAuthorityRegion.Result result;
            try {
                ByteBuffer bytes=ByteBuffer.wrap(packet.changes());var changes=packet.action()==ServerboundPackagePacket.DELTA
                        ?PackageDeltaCodec.decode(bytes):PackageBatchDeltaCodec.decode(bytes);
                if(bytes.hasRemaining())return;
                result=packet.action()==ServerboundPackagePacket.PREDICTED_DELTA
                        ?region.deltaPredicted(player.getUUID(),packet.epoch(),packet.revision(),packet.sequence(),tick,changes,4)
                        :packet.action()==ServerboundPackagePacket.RELATIVE_DELTA?region.deltaRelative(player.getUUID(),packet.epoch(),packet.revision(),packet.sequence(),tick,changes,4)
                        :region.delta(player.getUUID(),packet.epoch(),packet.revision(),packet.sequence(),tick,changes,4);
            }catch(RuntimeException invalid){return;}
            if(result==PackageAuthorityRegion.Result.ACCEPTED || result==PackageAuthorityRegion.Result.STALE
                    && packet.revision()==region.revision() && packet.sequence()==region.lastSequence())
                queueAck(rt,region,player,packet.sequence());
            else if(result!=PackageAuthorityRegion.Result.STALE)region.close();
            return;
        }
        if(peer.controlRecords>=MAX_CONTROL_RECORDS_PER_TICK){region.close();return;}
        peer.controlRecords++;
        control(rt,region,player,packet.action(),packet.index(),packet.identity(),packet.leaseEpoch(),packet.revision(),tick);
    }
    private static void control(Runtime rt,PackageAuthorityRegion region,ServerPlayer player,int action,int index,
                                PackageLease.Identity identity,long leaseEpoch,long revision,long tick) {
        if(action==ServerboundPackagePacket.RELEASE) {
            region.release(player.getUUID(),region.epoch(),index,identity,leaseEpoch,tick);return;
        }
        var previous=region.baseline(identity);
        if(previous==null || previous.index()!=index)return;
        var entity=findTarget(rt,identity);if(entity==null)return;
        if(action==ServerboundPackagePacket.PREPARED) {
            var finalBaseline=region.prepared(player.getUUID(),region.epoch(),index,identity,leaseEpoch,revision,tick);
            if(finalBaseline!=null){entity.checkpoint=finalBaseline.snapshot();send(rt.level,region,ClientboundPackagePacket.FINAL_BASELINE,finalBaseline,entity);}
        } else if(action==ServerboundPackagePacket.FINAL_READY) {
            try {
                if(region.finalReady(player.getUUID(),region.epoch(),index,identity,leaseEpoch,revision,tick))
                    send(rt.level,region,ClientboundPackagePacket.ACTIVE,region.baseline(identity),entity);
            }catch(IllegalArgumentException invalid){release(entity,tick);}
        } else if(action==ServerboundPackagePacket.VISIBLE_READY) {
            if(region.visibleReady(player.getUUID(),region.epoch(),index,identity,
                    leaseEpoch,revision,tick))entity.nativeOwner=player.connection;
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
    /** The exact connection has confirmed a successful visible GPU submission. Preparation or
     * ACTIVE alone never drops native replication; observers always retain their motion. */
    public static boolean nativeMotionOwned(PackageEntity entity,ServerPlayerConnection recipient) {
        if(!(entity.level() instanceof ServerLevel level))return false;
        var rt=WORLDS.get(level);var target=rt==null?null:rt.entities.get(entity);
        if(target==null||target.nativeOwner!=recipient||target.region==null)return false;
        if(!ServerConfig.packageGpuAuthority){release(target,level.getGameTime());return false;}
        return target.region.simulated(target.identity,level.getGameTime());
    }
    /** Losing the retained native entity also revokes its authority. Its removal packet makes
     * an immediate motion recovery unnecessary; a later pairing supplies a fresh baseline. */
    public static void nativeUnpaired(PackageEntity entity,ServerPlayerConnection recipient) {
        if(!(entity.level() instanceof ServerLevel level))return;
        var rt=WORLDS.get(level);var target=rt==null?null:rt.entities.get(entity);
        if(target!=null&&target.region!=null&&target.region.owner().equals(recipient.getPlayer().getUUID())
                &&recipient.getPlayer().serverLevel()==level&&recipient.getPlayer().connection==recipient) {
            target.nativeOwner=null;release(target,level.getGameTime());
        }
    }
    public static boolean paused(PackageEntity entity) {
        if(!(entity.level() instanceof ServerLevel level))return false;
        Runtime rt=WORLDS.get(level);EntityTarget target=rt==null?null:rt.entities.get(entity);
        if(target==null || target.region==null)return false;
        if(!ServerConfig.packageGpuAuthority){release(target,level.getGameTime());return false;}
        return target.region.paused(target.identity(),level.getGameTime());
    }
    /** A final handshake can pause travel, but may not replace native external forces yet. */
    public static boolean simulated(PackageEntity entity) {
        if(!ServerConfig.packageGpuAuthority||!(entity.level() instanceof ServerLevel level))return false;
        var rt=WORLDS.get(level);var target=rt==null?null:rt.entities.get(entity);
        return target!=null&&target.region!=null&&target.region.simulated(target.identity,level.getGameTime());
    }
    public static boolean hasSimulated(ServerLevel level) {
        if(!ServerConfig.packageGpuAuthority)return false;var rt=WORLDS.get(level);if(rt==null)return false;
        for(var region:rt.regions.values())if(region.simulatedCount()>0)return true;return false;
    }
    /** This client's common free solver owns both contact bodies, even across its own regions.
     * Never suppress contacts across worlds, different authorities or an unfinished handshake. */
    public static boolean samePhysicsOwner(PackageEntity a,PackageEntity b) {
        if(!ServerConfig.packageGpuAuthority||a==b||!(a.level() instanceof ServerLevel level)||b.level()!=level)return false;
        var rt=WORLDS.get(level);if(rt==null)return false;
        var first=rt.entities.get(a);var second=rt.entities.get(b);
        return first!=null&&second!=null&&first.region!=null&&second.region!=null
                &&first.region.coSimulates(first.identity,second.region,second.identity,level.getGameTime());
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
    private static void close(Runtime rt){rt.closing=true;for(EntityTarget target:rt.entities.values())release(target,rt.level.getGameTime());rt.regions.values().forEach(PackageAuthorityRegion::close);
        for(var entry:rt.observers.entrySet()){entry.getValue().authority.observers().unsubscribe(entry.getValue().cursor);
            var player=rt.level.getServer().getPlayerList().getPlayer(entry.getKey().player());
            if(player!=null && player.serverLevel()==rt.level)closeObserver(rt,player,entry.getValue());}rt.observers.clear();rt.pendingAcks.clear();rt.spareAcks.clear();}
    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id=event.getEntity().getUUID();for(Runtime rt:WORLDS.values()){
            rt.peers.remove(id);rt.observers.entrySet().removeIf(entry->{if(!entry.getKey().player().equals(id))return false;
                entry.getValue().authority.observers().unsubscribe(entry.getValue().cursor);return true;});
            for(var region:rt.regions.values())if(region.owner().equals(id))region.close();}
    }
    @SubscribeEvent public static void onUnload(LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level){Runtime rt=WORLDS.remove(level);if(rt!=null)close(rt);}
    }
    @SubscribeEvent public static void onStopped(ServerStoppedEvent event){WORLDS.clear();}
}
