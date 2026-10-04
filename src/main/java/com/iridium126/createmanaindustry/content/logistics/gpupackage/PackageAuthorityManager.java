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
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-only world adapter. Never links Minecraft client classes; item operations are delegated to PackageLightGameplay. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID)
public final class PackageAuthorityManager {
    private static final String ID="CMIGpuPackageId",GENERATION="CMIGpuPackageGeneration";
    private static final long RETRY_TICKS=40;
    private static final int MAX_OFFERS_PER_TICK=64,MAX_MESSAGES_PER_TICK=512,MAX_ENVIRONMENT_MESSAGES_PER_TICK=4096;
    private static final int MAX_ENVIRONMENT_SAMPLES_PER_TICK=32768;
    private static final int MAX_CONTROL_RECORDS_PER_TICK=4096;
    private static final int MAX_OBSERVER_REGIONS=8,OBSERVER_RECORDS_PER_BATCH=64,MAX_OBSERVER_POLLS=64;
    private static final Map<ServerLevel,Runtime> WORLDS=new IdentityHashMap<>();
    private static final class Peer {int flags,messages,environmentMessages,controlRecords,environmentRecords;long tick=-1,environmentNanos;}
    private record ObserverKey(UUID player,PackageRegion region) {}
    private static final class Observer {
        final PackageAuthorityRegion authority;
        PackageObserverFeed.Cursor cursor;
        Observer(PackageAuthorityRegion authority,long stream){this.authority=authority;reset(stream);}
        void reset(long stream){cursor=authority.observers().subscribe(stream);}
    }
    private static final class Runtime {
        final PackageLightStore light;
        boolean unloading;
        final Set<EntityTarget> changed=new LinkedHashSet<>();
        final Set<EntityTarget> voidPackages=new LinkedHashSet<>();
        final ArrayDeque<PackageAuthorityRegion> closingRegions=new ArrayDeque<>();
        final LinkedHashMap<net.minecraft.world.level.ChunkPos,Boolean> chunkChanges=new LinkedHashMap<>();
        Iterator<PackageLightStore.Entry> chunkWork;
        net.minecraft.world.level.ChunkPos chunkWorking;
        boolean chunkLoading;
        final ArrayList<EntityTarget> lightTargets=new ArrayList<>();
        final Map<UUID,Set<PackageLease.Identity>> viewers=new HashMap<>();
        final Map<UUID,Set<PackageLease.Identity>> pendingVisuals=new HashMap<>();
        final ServerLevel level;
        final LinkedHashMap<UUID,Peer> peers=new LinkedHashMap<>();
        final Map<PackageRegion,PackageAuthorityRegion> regions=new HashMap<>();
        final Map<PackageLease.Identity,EntityTarget> identities=new HashMap<>();
        final LinkedHashMap<PackageLease.Identity,EntityTarget> discovery=new LinkedHashMap<>();
        final LinkedHashMap<ObserverKey,Observer> observers=new LinkedHashMap<>();
        final Map<PackageAuthorityRegion,PackageAckRanges.Builder> pendingAcks=new LinkedHashMap<>();
        final ArrayDeque<PackageAckRanges.Builder> spareAcks=new ArrayDeque<>();
        final ArrayList<PackageDeltaCodec.Entry> deltaScratch=new ArrayList<>(512);
        final PackageAuthorityRegion.DeltaWorkspace deltaWorkspace=new PackageAuthorityRegion.DeltaWorkspace();
        boolean closing;
        Runtime(ServerLevel level){this.level=level;light=PackageLightStore.get(level);long maximum=0;for(var entry:light.entries()){maximum=Math.max(maximum,entry.identity.id());if(level.hasChunkAt(net.minecraft.core.BlockPos.containing(entry.position())))activate(this,entry);}if(maximum>0)PackageIdentityData.get(level).observe(maximum);}
    }
    private static final class EntityTarget implements PackageAuthorityRegion.Target {
        final PackageLightStore.Entry light;
        int lightOrdinal=-1;
        final UUID uuid;
        net.minecraft.resources.ResourceLocation model;
        float width,height;
        final Runtime runtime;
        final PackageLease.Identity identity;
        PackageAuthorityRegion region;
        PackageAuthorityRegion.Snapshot checkpoint;
        long retry,environmentWritten,environmentSimulationStep,environmentOriginStep,environmentOriginTick;
        EntityTarget(Runtime runtime,PackageLightStore.Entry entry){this.runtime=runtime;light=entry;identity=entry.identity;uuid=entry.uuid;model=entry.model;width=entry.width;height=entry.height;}
        @Override public PackageLease.Identity identity(){return identity;}
        @Override public PackageAuthorityRegion.Snapshot snapshot(){return light.state();}
        @Override public boolean eligible(){return runtime.level.hasChunkAt(net.minecraft.core.BlockPos.containing(light.position()));}
        @Override public void apply(PackageAuthorityRegion.Snapshot state){runtime.light.update(light,state);runtime.changed.add(this);checkpoint=state;queueVoid(runtime,this);}
        @Override public void released(PackageAuthorityRegion.Baseline baseline) {
            var previous=region;region=null;checkpoint=null;retry=runtime.level.getGameTime();
            // Pausing leaves the durable record at its latest confirmed state.
            if(light!=null&&runtime.identities.get(identity)==this&&!runtime.unloading)runtime.discovery.put(identity,this);
            try{if(previous!=null&&!runtime.unloading)send(runtime.level,previous,ClientboundPackagePacket.RELEASED,baseline,this);}
            catch(RuntimeException disconnected){CreateManaIndustry.LOGGER.debug("[CMI packages] pause notification unavailable",disconnected);}
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
            if(!((PackageInitialEntityAccess)entity).cmi$validInitialEntity()){event.setCanceled(true);return;}
            var identity=identity(entity,level);
            Runtime rt=runtime(level);var backing=rt.light.byIdentity(identity);
            // SavedData is authoritative after transfer. An old entity chunk must not duplicate it.
            if(backing!=null&&backing.uuid.equals(entity.getUUID())){event.setCanceled(true);entity.discard();return;}
            if(backing!=null){entity.getPersistentData().putLong(ID,PackageIdentityData.get(level).identity());identity=identity(entity,level);}
            {
                // A duplicated external save tag gets a fresh identity, never aliases another live entity.
                if(rt.identities.containsKey(identity)) {
                    var tag=entity.getPersistentData();tag.putLong(ID,PackageIdentityData.get(level).identity());identity=identity(entity,level);
                }
                // Capture before the entity can enter ticking, collision or native tracking.
                // Machine output, dropped items and entities read from old saves share this boundary.
                var velocity=entity.getDeltaMovement();
                var pose=new PackageLease.Pose(entity.getX(),entity.getY(),entity.getZ(),
                        (float)(velocity.x*20),(float)(velocity.y*20),(float)(velocity.z*20),entity.getYRot());
                var entry=rt.light.capture(entity,identity,new PackageAuthorityRegion.Snapshot(pose,entity.onGround()?PackageAuthorityRegion.GROUNDED:0));
                activate(rt,entry);rt.changed.add(rt.identities.get(identity));
                event.setCanceled(true);entity.discard();}
        }
    }
    @SubscribeEvent public static void onTick(LevelTickEvent.Pre event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;
        Runtime rt=runtime(level);
        drainVoid(rt);
        if(!ServerConfig.packageGpuAuthority&&!rt.regions.isEmpty()){close(rt);rt.regions.clear();rt.closing=false;}
        long tick=level.getGameTime();
        for(Iterator<PackageAuthorityRegion> it=rt.regions.values().iterator();it.hasNext();) {
            var region=it.next();
            if(region.closed()){it.remove();continue;}
            if(region.expired(tick)){pauseRegion(rt,region);it.remove();continue;}
            region.tick(tick);
            var authority=level.getServer().getPlayerList().getPlayer(region.owner());
            boolean heartbeatExpired=region.expired(tick),peerPresent=rt.peers.containsKey(region.owner());
            boolean inRange=subscribed(authority,level,region.region());
            if(heartbeatExpired || !peerPresent || !inRange){
                CreateManaIndustry.LOGGER.debug("[CMI packages] pausing region {}: heartbeatExpired={}, peerPresent={}, subscribed={}",
                        region.region(),heartbeatExpired,peerPresent,inRange);
                pauseRegion(rt,region);it.remove();
            }
        }
        long retireDeadline=System.nanoTime()+ServerConfig.packageMainThreadBudgetNanos();int retireBudget=64;
        while(retireBudget>0&&!rt.closingRegions.isEmpty()&&System.nanoTime()<retireDeadline){
            var retired=rt.closingRegions.peekFirst();retireBudget-=retired.drainClose(1);
            if(retired.size()==0)rt.closingRegions.removeFirst();
        }
        pumpChunkChanges(rt);
        publishLight(rt);
        pumpObservers(rt);
        if(rt.peers.isEmpty()||!ServerConfig.packageGpuAuthority)return;
        long deadline=System.nanoTime()+ServerConfig.packageMainThreadBudgetNanos();int attempts=0;
        int available=rt.discovery.size();
        while(attempts++<MAX_OFFERS_PER_TICK && available-->0 && System.nanoTime()<deadline) {
            var iterator=rt.discovery.values().iterator();EntityTarget target=iterator.next();iterator.remove();
            rt.discovery.put(target.identity,target);
            if(target.region!=null && target.region.baseline(target.identity())!=null)continue;
            target.region=null;
            if(target.retry>tick || !target.eligible())continue;
            PackageRegion key=PackageRegion.at(target.snapshot().pose());
            var region=rt.regions.get(key);
            if(region==null) {
                ServerPlayer authority=elect(rt,key);if(authority==null)continue;
                region=new PackageAuthorityRegion(key,authority.getUUID(),PackageIdentityData.get(level).epoch(),1,tick,()->level.tickRateManager().tickrate());
                rt.regions.put(key,region);
            }
            var baseline=region.offer(target,tick);if(baseline==null)continue;
            target.environmentWritten=target.environmentSimulationStep=target.environmentOriginStep=0;target.environmentOriginTick=tick;
            target.region=region;target.checkpoint=baseline.snapshot();target.retry=tick+PackageTickTiming.deadlineTicks(RETRY_TICKS,level.tickRateManager().tickrate());
            rt.discovery.remove(target.identity);
            send(level,region,ClientboundPackagePacket.OFFER,baseline,target);
        }
    }
    private static ServerPlayer elect(Runtime rt,PackageRegion key) {
        // Evaluate subscribers once per region election, not once per package/frame.
        for(var peer:rt.peers.entrySet()) {
            int requiredFree=ServerboundPackagePacket.FREE_READY|ServerboundPackagePacket.WIDE_VELOCITY;
            if((peer.getValue().flags&requiredFree)!=requiredFree)continue;
            ServerPlayer player=rt.level.getServer().getPlayerList().getPlayer(peer.getKey());
            if(subscribed(player,rt.level,key))return player;
        }
        return null;
    }
    /** Flush only exact successful sequences at this level's tick end. No per-package scan;
     * normal batches wait less than one tick, and bounded overflow flushes before accepting
     * another sequence. Failed transport pauses durable records without dropping confirmations. */
    @SubscribeEvent public static void onTickPost(LevelTickEvent.Post event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;
        Runtime rt=WORLDS.get(level);if(rt==null || rt.pendingAcks.isEmpty())return;
        for(var iterator=rt.pendingAcks.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();var region=entry.getKey();
            var player=level.getServer().getPlayerList().getPlayer(region.owner());
            if(player!=null && player.serverLevel()==level)flushAcks(rt,region,player,entry.getValue());
            else pauseRegion(rt,region);
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
            CreateManaIndustry.LOGGER.warn("[CMI packages] ACK enqueue failed; pausing package records",failure);
            pauseRegion(rt,region);return false;
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
        if(peer.tick!=tick){peer.tick=tick;peer.messages=peer.environmentMessages=0;peer.controlRecords=peer.environmentRecords=0;peer.environmentNanos=0;}
        if(++peer.messages>MAX_MESSAGES_PER_TICK)return;
        var key=new ObserverKey(player.getUUID(),packet.region());var previous=rt.observers.get(key);
        if(packet.action()==ServerboundPackageObserverPacket.UNSUBSCRIBE) {
            if(previous!=null && previous.cursor.stream()==packet.stream()) {
                previous.authority.observers().unsubscribe(previous.cursor);rt.observers.remove(key);
            }return;
        }
        var region=rt.regions.get(packet.region());
        if(packet.action()!=ServerboundPackageObserverPacket.SUBSCRIBE)return;
        // Observer state now carries wider quantized velocities. Keep older clients on
        // Create's native replication path instead of sending them the incompatible layout.
        if(!supportsWideVelocity(rt,player.getUUID()))return;
        if(region==null || region.owner().equals(player.getUUID()) || !subscribed(player,rt.level,packet.region()))return;
        int regions=0;for(var observer:rt.observers.keySet())if(observer.player().equals(player.getUUID()))regions++;
        if(previous==null && regions>=MAX_OBSERVER_REGIONS)return;
        if(previous!=null)previous.authority.observers().unsubscribe(previous.cursor);
        rt.observers.put(key,new Observer(region,PackageIdentityData.get(rt.level).epoch()));
    }
    private static void pumpObservers(Runtime rt) {
        if(rt.observers.isEmpty())return;
        long deadline=System.nanoTime()+ServerConfig.packageMainThreadBudgetNanos();
        int idle=0,polls=0;
        while(!rt.observers.isEmpty() && idle<rt.observers.size() && polls++<MAX_OBSERVER_POLLS && System.nanoTime()<deadline) {
            var iterator=rt.observers.entrySet().iterator();var entry=iterator.next();
            var key=entry.getKey();var observer=entry.getValue();iterator.remove();
            var player=rt.level.getServer().getPlayerList().getPlayer(key.player());
            if(observer.authority.owner().equals(key.player()) || !supportsWideVelocity(rt,key.player()) || rt.regions.get(key.region())!=observer.authority
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
                        var visual=new ClientboundPackageObserverPacket.Visual(-1,target.uuid,target.model,target.width,target.height);
                        members.add(new PackageObserverFeed.Member<>(member.index(),member.identity(),member.leaseEpoch(),member.revision(),
                                member.state(),visual,member.stateTick()));
                    }
                    PacketDistributor.sendToPlayer(player,new ClientboundPackageObserverPacket(rt.level.dimension().location(),key.region(),
                            observer.authority.epoch(),observer.authority.revision(),batch.stream(),batch.sequence(),
                            (batch.reset()?ClientboundPackageObserverPacket.RESET:0)|(batch.complete()?ClientboundPackageObserverPacket.COMPLETE:0)
                                    ,members,batch.changes(),rt.level.getGameTime(),batch.stateTicks()));
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
                ClientboundPackageObserverPacket.CLOSE,List.of(),List.of(),rt.level.getGameTime(),PackageObserverTimes.zeros(0)));}
        catch(RuntimeException failure){CreateManaIndustry.LOGGER.debug("[CMI packages] observer close could not be enqueued",failure);}
    }
    private static boolean supportsWideVelocity(Runtime rt,UUID player) {
        Peer peer=rt.peers.get(player);
        int required=ServerboundPackagePacket.FREE_READY|ServerboundPackagePacket.WIDE_VELOCITY;
        return peer!=null&&(peer.flags&required)==required;
    }
    public static void receive(ServerboundPackagePacket packet,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player) || !ServerConfig.packageGpuAuthority)return;
        Runtime rt=runtime(player.serverLevel());long tick=rt.level.getGameTime();
        Peer peer=rt.peers.computeIfAbsent(player.getUUID(),id->new Peer());
        if(peer.tick!=tick){peer.tick=tick;peer.messages=peer.environmentMessages=0;peer.controlRecords=peer.environmentRecords=0;peer.environmentNanos=0;}
        if(packet.action()==ServerboundPackagePacket.ENVIRONMENT){if(++peer.environmentMessages>MAX_ENVIRONMENT_MESSAGES_PER_TICK)return;}
        else if(++peer.messages>MAX_MESSAGES_PER_TICK){
            var affected=rt.regions.get(packet.region());
            if(affected!=null&&affected.owner().equals(player.getUUID())&&affected.epoch()==packet.epoch())pauseRegion(rt,affected);
            return;
        }
        if(packet.action()==ServerboundPackagePacket.CAPABILITIES) {
            peer.flags=player.connection.hasChannel(ClientboundLightPackagePacket.TYPE)&&player.connection.hasChannel(ServerboundLightPackageInteraction.TYPE)?packet.capabilities():0;
            if(peer.flags==0){rt.peers.remove(player.getUUID());for(var region:rt.regions.values())if(region.owner().equals(player.getUUID()))pauseRegion(rt,region);}
            return;
        }
        var region=rt.regions.get(packet.region());
        if(region==null || !region.owner().equals(player.getUUID()) || region.epoch()!=packet.epoch())return;
        if(!subscribed(player,rt.level,region.region())){pauseRegion(rt,region);return;}
        if(packet.action()==ServerboundPackagePacket.HEARTBEAT){region.heartbeat(player.getUUID(),packet.epoch(),tick);return;}
        if(packet.action()==ServerboundPackagePacket.CONTROL_BATCH) {
            if(packet.revision()!=region.revision())return;
            try {int count=PackageControlBatchCodec.visitValidated(packet.changesView(),
                    MAX_CONTROL_RECORDS_PER_TICK-peer.controlRecords,(action,index,id,generation,lease,revision)->
                    control(rt,region,player,action,index,new PackageLease.Identity(id,generation),lease,revision,tick));
                peer.controlRecords+=count;
                // A sent release may concern a sleeping body that heartbeats keep alive.
                // Budget rejection must explicitly revoke authority, never silently lose that control.
                if(count==0)pauseRegion(rt,region);
            }
            catch(RuntimeException invalid){
                CreateManaIndustry.LOGGER.debug("[CMI packages] invalid/failed control batch; pausing package records",invalid);
                // Syntax is validated before callbacks. If a callback/transport fails, revoke
                // even an already-processed prefix; a lost terminal control cannot stay owned.
                pauseRegion(rt,region);
            }
            return;
        }
        if(packet.action()==ServerboundPackagePacket.ENVIRONMENT){
            if(packet.revision()!=region.revision())return;
            long budget=ServerConfig.packageMainThreadBudgetNanos();if(peer.environmentNanos>=budget)return;
            long started=System.nanoTime(),deadline=started+(budget-peer.environmentNanos);
            try{
                var event=PackageEnvironmentEvent.decode(packet.changesView());var target=rt.identities.get(event.identity());
                if(target==null||target.light==null||target.region!=region)return;
                var baseline=region.baseline(event.identity());
                if(baseline==null||baseline.leaseEpoch()!=event.lease()||baseline.index()!=event.index()||baseline.revision()!=event.revision()||!region.simulated(event.identity(),tick))return;
                if(event.first()>target.environmentWritten)return;
                for(int n=0;n<event.samples().size();n++){
                    long serial=event.first()+n+1;if(serial<=target.environmentWritten)continue;
                    // Confirm the successful prefix. The retained GPU journal retries
                    // the suffix; duplicate retransmissions consume no sample budget.
                    if(peer.environmentRecords>=MAX_ENVIRONMENT_SAMPLES_PER_TICK||System.nanoTime()>=deadline)break;
                    peer.environmentRecords++;
                    var sample=event.samples().get(n);
                    var contactPose=new PackageLease.Pose(region.region().originX()+sample.px(),region.region().originY()+sample.py()-target.light.height*.5,region.region().originZ()+sample.pz(),0,0,0,0);
                    String rejection=sample.ticks()>region.historyTicks()?"duration exceeds history":
                            sample.step()-sample.ticks()<target.environmentSimulationStep?"overlapping simulation interval":
                            target.environmentOriginStep>0&&sample.step()-target.environmentOriginStep>tick-target.environmentOriginTick+region.historyTicks()?"future simulation interval":
                            !region.environmentReachable(event.identity(),sample.step(),tick,contactPose)?"contact outside pose reach":
                            PackageLightGameplay.environmentRejection(rt.level,target.light,region.region(),sample);
                    if(rejection!=null){
                        CreateManaIndustry.LOGGER.debug("[CMI packages] rejected environment reason={} identity={} step={} ticks={} previousStep={} contact={} block={} pose={},{},{} committed={} dimensions={}x{} region={}",
                                rejection,event.identity(),sample.step(),sample.ticks(),target.environmentSimulationStep,sample.contact(),sample.block(region.region()),sample.px(),sample.py(),sample.pz(),baseline.snapshot().pose(),target.light.width,target.light.height,region.region());
                        region.release(event.identity());return;
                    }
                    if(target.environmentOriginStep==0){target.environmentOriginStep=sample.step();target.environmentOriginTick=tick;}
                    target.environmentWritten=serial;target.environmentSimulationStep=sample.step();
                    if(PackageLightGameplay.environmentStep(rt.level,target.light,packet.region(),sample))break;
                }
                if(rt.identities.get(event.identity())!=target)return;
                PacketDistributor.sendToPlayer(player,new ClientboundPackagePacket(ClientboundPackagePacket.ENVIRONMENT_ACK,rt.level.dimension().location(),region.region(),region.epoch(),region.revision(),
                        target.environmentWritten,baseline,-1,null,null,0,0,target.light.fireTicks,target.light.health,PackageLightGameplay.environmentPermissions(rt.level,target.light)));
            }catch(IllegalArgumentException invalid){CreateManaIndustry.LOGGER.debug("[CMI packages] invalid environment event",invalid);}
            finally{peer.environmentNanos+=Math.max(0,System.nanoTime()-started);}
            return;
        }
        if(ServerboundPackagePacket.deltaAction(packet.action())) {
            PackageAuthorityRegion.Result result;
            var changes=rt.deltaScratch;
            try {
                ByteBuffer bytes=packet.changesView();if(packet.action()==ServerboundPackagePacket.DELTA)
                    PackageDeltaCodec.decodeInto(bytes,PackageDeltaCodec.MAX_ENTRIES,changes);
                else PackageBatchDeltaCodec.decodeInto(bytes,PackageDeltaCodec.MAX_ENTRIES,changes);
                if(bytes.hasRemaining())return;
                int mode=packet.action()==ServerboundPackagePacket.PREDICTED_DELTA?2:packet.action()==ServerboundPackagePacket.RELATIVE_DELTA?1:0;
                result=region.deltaStepped(player.getUUID(),packet.epoch(),packet.revision(),packet.sequence(),tick,changes,4,mode,packet.simulationStep(),rt.deltaWorkspace);
            }catch(RuntimeException invalid){return;}finally{changes.clear();}
            if(result==PackageAuthorityRegion.Result.ACCEPTED || result==PackageAuthorityRegion.Result.STALE
                    && packet.revision()==region.revision() && packet.sequence()==region.lastSequence()){
                queueAck(rt,region,player,packet.sequence());
                if(result==PackageAuthorityRegion.Result.ACCEPTED)drainVoid(rt);
            }
            else if(result!=PackageAuthorityRegion.Result.STALE){
                CreateManaIndustry.LOGGER.debug("[CMI packages] rejected delta result={} reason={} sequence={} region={}; pausing package records",
                        result,region.lastDeltaRejection(),packet.sequence(),region.region());
                pauseRegion(rt,region);
            }
            return;
        }
        if(peer.controlRecords>=MAX_CONTROL_RECORDS_PER_TICK){pauseRegion(rt,region);return;}
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
                    leaseEpoch,revision,tick)){}
        }
    }
    private static EntityTarget findTarget(Runtime rt,PackageLease.Identity identity) {
        return rt.identities.get(identity);
    }
    private static void send(ServerLevel level,PackageAuthorityRegion region,int action,PackageAuthorityRegion.Baseline baseline,EntityTarget target) {
        ServerPlayer owner=level.getServer().getPlayerList().getPlayer(region.owner());if(owner==null)return;
        PacketDistributor.sendToPlayer(owner,new ClientboundPackagePacket(action,level.dimension().location(),region.region(),region.epoch(),
                region.revision(),0,baseline,-1,target.uuid,target.model,target.width,target.height,
                target.light==null?0:target.light.fireTicks,target.light==null?5:target.light.health,target.light==null?7:PackageLightGameplay.environmentPermissions(level,target.light)));
    }
    private static void release(EntityTarget target,long tick) {
        var region=target.region;if(region==null)return;
        region.release(target.identity());
    }
    private static void pauseRegion(Runtime rt,PackageAuthorityRegion region){
        if(region.closed())return;region.beginClose();rt.closingRegions.addLast(region);
    }
    private static void close(Runtime rt){
        rt.closing=true;for(var region:rt.regions.values())pauseRegion(rt,region);
        for(var entry:rt.observers.entrySet()){entry.getValue().authority.observers().unsubscribe(entry.getValue().cursor);
            var player=rt.level.getServer().getPlayerList().getPlayer(entry.getKey().player());
            if(player!=null&&player.serverLevel()==rt.level)closeObserver(rt,player,entry.getValue());}
        rt.observers.clear();rt.pendingAcks.clear();rt.spareAcks.clear();
    }
    @SubscribeEvent public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id=event.getEntity().getUUID();for(Runtime rt:WORLDS.values()){rt.viewers.remove(id);rt.pendingVisuals.remove(id);
            rt.peers.remove(id);rt.observers.entrySet().removeIf(entry->{if(!entry.getKey().player().equals(id))return false;
                entry.getValue().authority.observers().unsubscribe(entry.getValue().cursor);return true;});
            for(var region:rt.regions.values())if(region.owner().equals(id))pauseRegion(rt,region);}
    }
    @SubscribeEvent public static void lightDimensionChanged(PlayerEvent.PlayerChangedDimensionEvent event) {
        // The client's world switch clears all record visuals, even for a revisited dimension.
        var id=event.getEntity().getUUID();
        for(var rt:WORLDS.values()){rt.viewers.remove(id);rt.pendingVisuals.remove(id);}
    }
    @SubscribeEvent public static void onUnload(LevelEvent.Unload event) {
        if(event.getLevel() instanceof ServerLevel level){Runtime rt=WORLDS.get(level);if(rt!=null){rt.unloading=true;close(rt);WORLDS.remove(level);}}
    }
    @SubscribeEvent public static void onStopped(ServerStoppedEvent event){WORLDS.clear();}
    private static void activate(Runtime rt,PackageLightStore.Entry entry){if(rt.identities.containsKey(entry.identity))return;var target=new EntityTarget(rt,entry);rt.identities.put(entry.identity,target);rt.discovery.put(entry.identity,target);addLight(rt,target);queueVoid(rt,target);}
    private static void queueVoid(Runtime rt,EntityTarget target){if(PackageLightGameplay.belowVoid(target.light.state().pose().y(),rt.level.getMinBuildHeight()))rt.voidPackages.add(target);else rt.voidPackages.remove(target);}
    /** Event-driven lifecycle work; never scans or simulates the package population. */
    private static void drainVoid(Runtime rt){
        long deadline=System.nanoTime()+ServerConfig.packageMainThreadBudgetNanos();int budget=64;
        while(budget-->0&&!rt.voidPackages.isEmpty()&&System.nanoTime()<deadline){
            var iterator=rt.voidPackages.iterator();var target=iterator.next();iterator.remove();
            consumeLight(rt.level,target.light);
        }
    }
    @SubscribeEvent public static void lightChunkLoaded(ChunkEvent.Load event){if(event.getLevel() instanceof ServerLevel level){var pos=event.getChunk().getPos();level.getServer().execute(()->{var rt=WORLDS.get(level);if(rt!=null)rt.chunkChanges.put(pos,true);});}}
    @SubscribeEvent public static void lightChunkUnloaded(ChunkEvent.Unload event){if(event.getLevel() instanceof ServerLevel level){var pos=event.getChunk().getPos();level.getServer().execute(()->{var rt=WORLDS.get(level);if(rt!=null)rt.chunkChanges.put(pos,false);});}}
    private static void pumpChunkChanges(Runtime rt){
        long deadline=System.nanoTime()+ServerConfig.packageMainThreadBudgetNanos();
        for(int n=0;n<64&&System.nanoTime()<deadline;n++){
            if(rt.chunkWork==null){
                if(rt.chunkChanges.isEmpty())break;var iterator=rt.chunkChanges.entrySet().iterator();var change=iterator.next();
                rt.chunkWorking=change.getKey();rt.chunkLoading=change.getValue();iterator.remove();rt.chunkWork=rt.light.inChunk(rt.chunkWorking).iterator();
            }
            var latest=rt.chunkChanges.remove(rt.chunkWorking);if(latest!=null){rt.chunkLoading=latest;rt.chunkWork=rt.light.inChunk(rt.chunkWorking).iterator();}
            if(!rt.chunkWork.hasNext()){rt.chunkWork=null;continue;}
            var entry=rt.chunkWork.next();if(rt.light.byIdentity(entry.identity)!=entry||!new net.minecraft.world.level.ChunkPos(net.minecraft.core.BlockPos.containing(entry.position())).equals(rt.chunkWorking))continue;
            if(rt.chunkLoading){if(rt.level.hasChunkAt(net.minecraft.core.BlockPos.containing(entry.position())))activate(rt,entry);}
            else{var target=rt.identities.remove(entry.identity);if(target!=null&&target.light==entry){release(target,rt.level.getGameTime());rt.discovery.remove(entry.identity);removeLight(rt,target);rt.changed.remove(target);withdrawVisual(rt,entry);}}
        }
    }
    private static void withdrawVisual(Runtime rt,PackageLightStore.Entry entry){for(var viewer:rt.viewers.entrySet()){var pending=rt.pendingVisuals.get(viewer.getKey());if(pending!=null)pending.remove(entry.identity);if(viewer.getValue().remove(entry.identity)){var player=rt.level.getServer().getPlayerList().getPlayer(viewer.getKey());if(player!=null&&player.serverLevel()==rt.level)sendLight(player,List.of(new ClientboundLightPackagePacket.Row(entry.identity,true,null,0,0,null)));}}}
    private static void addLight(Runtime rt,EntityTarget target){if(target.lightOrdinal>=0)return;target.lightOrdinal=rt.lightTargets.size();rt.lightTargets.add(target);}
    private static void removeLight(Runtime rt,EntityTarget target){rt.voidPackages.remove(target);int index=target.lightOrdinal;if(index<0)return;var last=rt.lightTargets.removeLast();if(last!=target){rt.lightTargets.set(index,last);last.lightOrdinal=index;}target.lightOrdinal=-1;}
    public static List<PackageLightStore.Entry> queryLight(ServerLevel level,AABB bounds){return runtime(level).light.query(bounds);}
    static void transferLight(ServerLevel source,ServerLevel destination,PackageLightStore.Entry entry,PackageLease.Pose pose){
        var arrival=net.minecraft.core.BlockPos.containing(pose.x(),pose.y(),pose.z());destination.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.PORTAL,new net.minecraft.world.level.ChunkPos(arrival),3,arrival);
        if(source==destination){pauseForTransfer(source,entry);entry.portalCooldown=300;entry.portalUpdatedTick=source.getGameTime();updateLight(source,entry,new PackageAuthorityRegion.Snapshot(pose,0));return;}
        var rt=runtime(destination);var identity=new PackageLease.Identity(PackageIdentityData.get(destination).identity(),entry.identity.generation());var next=new PackageLightStore.Entry(identity,entry.uuid,entry.model,entry.width,entry.height,-1,entry.data,new PackageAuthorityRegion.Snapshot(pose,0));next.health=entry.health;next.fireTicks=entry.fireTicks;next.tossedBy=entry.tossedBy;next.portalCooldown=300;next.portalUpdatedTick=destination.getGameTime();rt.light.put(next);consumeLight(source,entry);var target=new EntityTarget(rt,next);rt.identities.put(identity,target);rt.discovery.put(identity,target);addLight(rt,target);rt.changed.add(target);}
    static PackageLightStore.Entry light(ServerLevel level,PackageLease.Identity identity){var t=runtime(level).identities.get(identity);return t==null?null:t.light;}
    static void updateLight(ServerLevel level,PackageLightStore.Entry entry,PackageAuthorityRegion.Snapshot state){var t=runtime(level).identities.get(entry.identity);if(t!=null&&t.light==entry)t.apply(state);}
    static void pauseForTransfer(ServerLevel level,PackageLightStore.Entry entry){var t=runtime(level).identities.get(entry.identity);if(t!=null){release(t,level.getGameTime());t.retry=level.getGameTime();}}
    public static boolean consumeLight(ServerLevel level,PackageLightStore.Entry entry){
        var rt=runtime(level);if(rt.light.byIdentity(entry.identity)!=entry)return false;
        var target=rt.identities.remove(entry.identity);if(target!=null){release(target,level.getGameTime());removeLight(rt,target);rt.changed.remove(target);}rt.discovery.remove(entry.identity);rt.light.remove(entry);withdrawVisual(rt,entry);return true;
    }
    private static ClientboundLightPackagePacket.Row visual(PackageLightStore.Entry entry){return new ClientboundLightPackagePacket.Row(entry.identity,false,entry.model,entry.width,entry.height,entry.state().pose());}
    private static void sendLight(ServerPlayer player,List<ClientboundLightPackagePacket.Row> rows){try{for(int i=0;i<rows.size();i+=128)PacketDistributor.sendToPlayer(player,new ClientboundLightPackagePacket(player.serverLevel().dimension().location(),rows.subList(i,Math.min(i+128,rows.size()))));}catch(RuntimeException disconnected){CreateManaIndustry.LOGGER.debug("[CMI packages] lightweight visual delivery unavailable",disconnected);}}
    private static void publishLight(Runtime rt){
        long tick=rt.level.getGameTime();
        for(var player:rt.level.players()) {
            if(!player.connection.hasChannel(ClientboundLightPackagePacket.TYPE))continue;
            var known=rt.viewers.computeIfAbsent(player.getUUID(),k->new HashSet<>());
            var pending=rt.pendingVisuals.computeIfAbsent(player.getUUID(),k->new LinkedHashSet<>());
            var rows=new ArrayList<ClientboundLightPackagePacket.Row>();
            if(tick%20==0||known.isEmpty()) {
                double range=Math.min(256,rt.level.getServer().getPlayerList().getViewDistance()*16);
                var nearby=rt.light.query(new AABB(player.position(),player.position()).inflate(range));var present=new HashSet<PackageLease.Identity>();
                for(var entry:nearby){if(!rt.level.hasChunkAt(net.minecraft.core.BlockPos.containing(entry.position())))continue;activate(rt,entry);present.add(entry.identity);if(known.add(entry.identity))rows.add(visual(entry));}
                for(var it=known.iterator();it.hasNext();){var id=it.next();if(!present.contains(id)){it.remove();rows.add(new ClientboundLightPackagePacket.Row(id,true,null,0,0,null));}}
            }
            for(var target:rt.changed)if(target.light!=null&&known.contains(target.identity))pending.add(target.identity);
            // Keep delayed updates until their delivery cadence. The last movement may stop
            // between cadence ticks; clearing it would leave the backing pick pose stale forever.
            for(var iterator=pending.iterator();iterator.hasNext();) {
                var identity=iterator.next();var target=rt.identities.get(identity);
                if(!known.contains(identity)||target==null||target.light==null){iterator.remove();continue;}
                if(target.region!=null&&target.region.simulated(target.identity,tick)) {
                    if(target.region.owner().equals(player.getUUID())&&tick%20!=0)continue;
                    var observer=rt.observers.get(new ObserverKey(player.getUUID(),target.region.region()));if(observer!=null&&observer.cursor.complete()&&tick%5!=0)continue;
                }
                rows.add(visual(target.light));iterator.remove();
            }
            if(!rows.isEmpty())sendLight(player,rows);
        }
        rt.changed.clear();
    }
}
