package com.iridium126.createmanaindustry.content.allaystorm.rift;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.allaystorm.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import java.util.concurrent.*;

/** Server-owned coordinator. Requests never originate from client acknowledgements. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID)
public final class RiftTransferCoordinator implements StormRiftTransfers.Controller {
    private static final org.slf4j.Logger LOG=com.mojang.logging.LogUtils.getLogger();
    private static RiftTransferCoordinator instance;
    private record Request(UUID player,ServerLevel source,ServerLevel target,StormIdentity identity,double x,double z,VehicleTransferAdapters.Association association){}
    private final MinecraftServer server;
    final RiftTransferJournal journal;
    private final LinkedHashMap<UUID,Request> queued=new LinkedHashMap<>();
    private final LinkedHashMap<UUID,Operation> active=new LinkedHashMap<>();
    private final Map<UUID,UUID> claims=new HashMap<>(),locks=new HashMap<>();
    private final Map<UUID,Long> retry=new HashMap<>();
    private final CompletableFuture<List<CompoundTag>> recovered;
    private final ArrayDeque<RiftRecoveryTask> recovery=new ArrayDeque<>();
    private boolean initialized,disabled,worldsReady,recoveryFault;
    private RiftWorkBudget budget;
    private RiftTransferCoordinator(MinecraftServer server){this.server=server;journal=new RiftTransferJournal(server.getWorldPath(LevelResource.ROOT).resolve("data/cmi-rift-transfers"));recovered=journal.readAll();}
    @SubscribeEvent public static void aboutToStart(ServerAboutToStartEvent event){instance=new RiftTransferCoordinator(event.getServer());StormRiftTransfers.install(instance);}
    @SubscribeEvent public static void start(ServerStartedEvent event){if(instance==null)aboutToStart(new ServerAboutToStartEvent(event.getServer()));instance.worldsReady=true;}
    @SubscribeEvent public static void beforeTick(ServerTickEvent.Pre event){if(instance!=null&&instance.server==event.getServer())instance.initializeRecovery();}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event){if(instance!=null&&instance.server==event.getServer())instance.step();}
    @SubscribeEvent public static void stopping(ServerStoppingEvent event){VehicleTransferAdapters.stopping(event.getServer());}
    @SubscribeEvent public static void stop(ServerStoppedEvent event){if(instance!=null){instance.journal.close();instance=null;}RiftEntityDiskIndex.clear();RiftMountPersistence.clear();VehicleTransferAdapters.clear();StormRiftTransfers.clear();}
    public static RiftTransferCoordinator current(){return instance;}
    public static boolean allowWorldTick(){if(instance!=null&&instance.worldsReady&&instance.recovered.isDone())instance.initializeRecovery();return instance==null||!instance.worldsReady||instance.recovered.isDone()&&!instance.recoveryFault;}
    public boolean frozen(UUID entity){return locks.containsKey(entity);}
    public boolean retained(UUID entity){return claims.containsKey(entity)||locks.containsKey(entity);}
    public boolean staging(ServerLevel level,UUID structure){UUID id=claims.get(structure);Operation op=id==null?null:active.get(id);return op!=null&&op.request.target==level&&!op.published&&op.group.adapter().structures().contains(structure);}
    public boolean together(UUID entity,UUID player){UUID transaction=claims.get(entity);return transaction!=null&&transaction.equals(claims.get(player))&&locks.containsKey(entity);}
    public String report(){return "rift-transfer="+(recoveryFault?"journal fault; world ticks held":disabled?"quarantined":!initialized||!recovery.isEmpty()?"recovering":"active")+"; pending="+active.size()+"; recovery="+recovery.size();}
    public void ready(ServerPlayer player,UUID transaction){if(player==null)return;Operation op=active.get(transaction);if(op!=null&&op.published&&player.level()==op.request.target&&op.players.contains(player.getUUID()))op.ready.add(player.getUUID());}
    public void consider(ServerLevel source,AllayStormData storm,double x,double z,ServerLevel target) {
        if(target==null||disabled||!initialized||!recovery.isEmpty())return;
        for(ServerPlayer player:source.players())if(!claims.containsKey(player.getUUID())&&server.getTickCount()>=retry.getOrDefault(player.getUUID(),0L)&&RiftRules.eligible(source.dimension()==Level.OVERWORLD,storm.active,storm.count,player.isAlive(),player.getX(),player.getY(),player.getZ(),x,z,source.getMaxBuildHeight()))
            queued.putIfAbsent(player.getUUID(),new Request(player.getUUID(),source,target,StormIdentity.of(source,storm),x,z,VehicleTransferAdapters.association(player)));
    }
    public void cancelSource(ServerLevel source,StormIdentity storm){queued.values().removeIf(r->r.source==source&&r.identity.equals(storm));for(Operation op:active.values())if(op.request.source==source&&op.request.identity.equals(storm))op.machine.cancel();}
    void lock(UUID entity,UUID transaction){UUID old=locks.putIfAbsent(entity,transaction);if(old!=null&&!old.equals(transaction))throw new IllegalStateException("Transport lock conflict");}
    void unlock(UUID transaction){locks.values().removeIf(transaction::equals);claims.values().removeIf(transaction::equals);}
    void recovered(UUID transaction,Set<UUID> members){unlock(transaction);for(UUID id:members)retry.put(id,(long)server.getTickCount()+40);}
    void quarantine(UUID transaction,Collection<UUID> ids,Throwable error){for(UUID id:ids){claims.put(id,transaction);locks.put(id,transaction);}LOG.error("Rift transaction {} isolated; journal retained",transaction,error);}
    private void initializeRecovery() {
        if(!initialized) {
            if(!recovered.isDone())return;
            try {
                for(CompoundTag tag:recovered.getNow(List.of()))if(!Set.of("RELEASED","ROLLED_BACK_RELEASED").contains(tag.getString("State")))recovery.add(new RiftRecoveryTask(this,server,tag));
                VehicleTransferAdapters.freezeRecovery(server);
                initialized=true;
            } catch(Throwable error){disabled=true;recoveryFault=true;initialized=true;LOG.error("Rift journal cannot be read safely; world ticks held for journal recovery",error);}
        }
    }
    private void step() {
        VehicleTransferAdapters.maintenance();
        budget=new RiftWorkBudget(System.nanoTime()+1_000_000,2);
        initializeRecovery();if(!initialized||recoveryFault)return;
        if(!recovery.isEmpty()){try(var access=RiftWorldAccess.open()){if(recovery.peek().step(budget))recovery.remove();}return;}
        RiftMountPersistence.tick(server,budget);
        for(Operation op:List.copyOf(active.values())) {
            if(!budget.available())return;
            try(var access=RiftWorldAccess.open()){op.machine.tick(server.getTickCount(),budget.deadline());}
            if(op.machine.terminal()&&op.machine.stage()!=RiftTransaction.Stage.QUARANTINED){active.remove(op.id);for(UUID player:op.players)retry.put(player,(long)server.getTickCount()+40);if(op.machine.failure()!=null)op.fail(op.machine.failure());else if(op.machine.stage()==RiftTransaction.Stage.SUCCEEDED)LOG.info("Rift transfer {} succeeded: adapter={} entities={} structures={} source={} target={}",op.id,op.group.adapter().id(),op.group.members().size(),op.group.adapter().structures().size(),op.request.source.dimension().location(),op.request.target.dimension().location());}
        }
        var cursor=queued.values().iterator();
        while(cursor.hasNext()&&budget.available()) {
            Request request=cursor.next();cursor.remove();ServerPlayer player=server.getPlayerList().getPlayer(request.player);
            if(player==null||player.level()!=request.source||claims.containsKey(request.player))continue;
            try {
                if(!request.association.equals(VehicleTransferAdapters.association(player)))throw new IllegalStateException("入口请求后的载具关联已改变，重新收集后重试");
                var adapter=VehicleTransferAdapters.select(player);var group=StormTransferGroup.collect(player,adapter);
                if(group.members().size()>4096||adapter.structures().size()>4096)throw new IllegalStateException("Arrival manifest exceeds protocol limit");
                Set<UUID> ids=ids(group);if(ids.stream().anyMatch(claims::containsKey))continue;
                Operation op=new Operation(request,group);for(UUID id:ids)claims.put(id,op.id);active.put(op.id,op);
            }catch(Throwable error){retry.put(player.getUUID(),(long)server.getTickCount()+40);player.displayClientMessage(Component.literal("裂口传送暂缓："+error.getMessage()),true);LOG.warn("Rift group collection refused",error);}
        }
    }
    static Set<UUID> ids(StormTransferGroup group){var ids=new LinkedHashSet<UUID>(group.adapter().structures());for(Entity entity:group.members())ids.add(entity.getUUID());return ids;}
    private Collection<AABB> reservations(UUID except){var boxes=new ArrayList<AABB>();for(var op:active.values())if(!op.id.equals(except)&&op.landing!=null)boxes.addAll(op.landing.reservation());return boxes;}
    final class Operation implements RiftTransaction.Backend<CompoundTag> {
        final UUID id=UUID.randomUUID();final Request request;StormTransferGroup group;
        final RiftTransaction<CompoundTag> machine;
        final Set<UUID> players=new LinkedHashSet<>(),ready=new HashSet<>();
        final Map<UUID,Entity> moved=new LinkedHashMap<>();
        final RiftChunkTickets sourceTickets;
        RiftGroupLanding landing;
        RiftGroupLanding preparedLanding;
        Vec3 delta;
        ListTag entityTags=new ListTag();int snapshotCursor,commitCursor,rollbackCursor;
        RiftEntityDiskIndex.Scan sourceIndex,targetIndex;
        boolean eventsChecked,detached,mounted,published,rollbackMounted;
        long publishedAt;
        RiftNativeCheckpoint checkpoint;
        CompletableFuture<Void> checkpointed,released;
        Operation(Request request,StormTransferGroup group){this.request=request;this.group=group;machine=new RiftTransaction<>(this,server.getTickCount());sourceTickets=new RiftChunkTickets(request.source,id);for(Entity e:group.members()){sourceTickets.include(e.chunkPosition());if(e instanceof ServerPlayer)players.add(e.getUUID());}}
        public boolean prepare(long deadline) {
            if(!sourceTickets.step(budget))return false;
            group.adapter().prepare(group,request.target);
            if(sourceIndex==null){Set<UUID> entities=group.members().stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());sourceIndex=new RiftEntityDiskIndex.Scan(request.source,entities,journal.executor());targetIndex=new RiftEntityDiskIndex.Scan(request.target,entities,journal.executor());}
            if(!sourceIndex.ready()||!targetIndex.ready())return false;
            for(var positions:sourceIndex.snapshot().values())for(ChunkPos position:positions)sourceTickets.include(position);
            if(!group.adapter().snapshotReady(group,deadline))return false;
            for(AABB box:group.geometry())sourceTickets.include(box);
            if(!sourceTickets.step(budget))return false;
            // Native structure storage discovery also belongs to the moving, unfrozen preparation phase.
            if(!group.adapter().preflightReady(group,request.target,journal,deadline))return false;
            if(landing==null)landing=new RiftGroupLanding(request.target,id,group,request.x,request.z);
            if(!landing.step(budget,reservations(id)))return false;
            if(landing.result()==null)throw new IllegalStateException("32 格邻域内没有整组安全空间");
            Entity player=request.source.getEntity(request.player);if(player==null)throw new IllegalStateException("Trigger player left source");
            var fresh=StormTransferGroup.collect(player,VehicleTransferAdapters.select(player));
            if(!group.sameMembership(fresh))throw new IllegalStateException("乘客或结构连接发生变化，重新收集后重试");
            // Re-evaluate geometry after locking; preparation never freezes a moving/cold-loading group.
            group=new StormTransferGroup(group.source(),group.key(),group.adapter().reference(player),group.members(),group.mounts(),group.adapter());return true;
        }
        public void freeze(){for(UUID member:ids(group))lock(member,id);for(Entity e:group.members())if(e instanceof ServerPlayer p)p.closeContainer();group.adapter().freeze(group,true);group.adapter().beginSnapshot(group);preparedLanding=landing;landing=null;}
        public CompoundTag snapshot(long deadline) {
            if(!group.adapter().snapshotReady(group,deadline))return null;
            if(landing==null){landing=preparedLanding.refreshed(group);preparedLanding=null;}
            if(!landing.resident())throw new IllegalStateException("Group geometry changed into a cold target footprint; thawing before recollection");
            if(!landing.step(budget,reservations(id)))return null;
            if(landing.result()==null)throw new IllegalStateException("锁定后整组安全空间已变化");
            delta=landing.result().subtract(group.reference());
            while(snapshotCursor<group.members().size()&&System.nanoTime()<deadline)entityTags.add(RiftEntitySnapshot.capture(group.members().get(snapshotCursor++)));
            if(snapshotCursor<group.members().size())return null;
            CompoundTag tag=new CompoundTag();tag.putUUID("Group",group.key());tag.putString("Source",request.source.dimension().location().toString());tag.putString("Target",request.target.dimension().location().toString());tag.putString("Adapter",group.adapter().id());tag.put("Entities",entityTags);tag.put("Structure",group.adapter().snapshot(group));
            ListTag edges=new ListTag();for(var mount:group.mounts()){CompoundTag edge=new CompoundTag();edge.putUUID("Passenger",mount.passenger());edge.putUUID("Vehicle",mount.vehicle());edge.putInt("Order",mount.order());edges.add(edge);}tag.put("Mounts",edges);tag.put("Translation",vector(delta));
            return tag;
        }
        public void preflight(CompoundTag tag){throw new UnsupportedOperationException("Use budgeted preflight");}
        public boolean preflightStep(CompoundTag tag,long deadline) {
            Set<UUID> entityIds=group.members().stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
            if(!sourceIndex.ready()||!targetIndex.ready())return false;
            if(!targetIndex.snapshot().isEmpty())throw new IllegalStateException("Target entity UUID exists on disk");
            for(var positions:sourceIndex.snapshot().values())for(ChunkPos position:positions)sourceTickets.include(position);
            if(!sourceTickets.resident())throw new IllegalStateException("New source storage columns appeared after locking; thawing before recollection");
            if(!sourceTickets.step(budget))return false;
            if(!group.adapter().preflightReady(group,request.target,journal,deadline))return false;
            for(Entity e:group.members())if(e.isRemoved()||e.level()!=request.source||request.target.getEntity(e.getUUID())!=null||!e.canChangeDimensions(request.source,request.target))throw new IllegalStateException("Entity unavailable or UUID conflict: "+e.getUUID());
            if(!eventsChecked){for(int i=0;i<group.members().size();i++)entityTags.set(i,RiftEntitySnapshot.capture(group.members().get(i)));try(var guarded=RiftWorldAccess.block()){for(Entity e:group.members())if(!CommonHooks.onTravelToDimension(e,request.target.dimension()))throw new IllegalStateException("A companion's travel event was cancelled");}for(int i=0;i<group.members().size();i++)if(!entityTags.getCompound(i).equals(RiftEntitySnapshot.capture(group.members().get(i))))throw new IllegalStateException("Travel event changed a member's recovery snapshot; recollecting the group");eventsChecked=true;}
            return true;
        }
        public CompletableFuture<Void> persist(String state,CompoundTag tag){return journal.write(id,state.equals("ROLLED_BACK")?"ROLLED_BACK_RELEASED":state,tag);}
        public void commit(CompoundTag tag){throw new UnsupportedOperationException("Use budgeted commit");}
        public void verify(CompoundTag tag){for(Entity e:moved.values())if(e.isRemoved()||e.level()!=request.target||RiftKnownEntities.find(request.target,e.getUUID(),List.of(e.chunkPosition()))!=e)throw new IllegalStateException("Target member missing from native entity sections");verifyMounts(group.mounts(),moved);}
        public boolean commitStep(CompoundTag tag,long deadline) {
            if(!group.adapter().commitReady(group,request.target,delta,tag.getCompound("Structure"),deadline))return false;
            if(!detached){try(var context=RiftTransferContext.open(request.target.dimension(),ids(group))){for(Entity e:group.members())e.stopRiding();}detached=true;}
            List<Entity> order=group.members().stream().sorted(Comparator.comparing(e->e instanceof ServerPlayer)).toList();
            while(commitCursor<order.size()&&System.nanoTime()<deadline) {
                Entity old=order.get(commitCursor);group.adapter().beforeEntityTransfer(old);
                CompoundTag saved=entityTags.stream().map(t->(CompoundTag)t).filter(t->t.getUUID("UUID").equals(old.getUUID())).findFirst().orElseThrow();Vec3 destination=group.adapter().destination(old,RiftEntitySnapshot.position(saved),delta);CompoundTag state=group.adapter().targetSnapshot(old,saved,delta);RiftEntitySnapshot.at(state,destination,request.target.dimension().location());
                try(var travel=RiftTransferContext.open(request.target.dimension(),Set.of(old.getUUID()))) {
                    Entity arrived=old.changeDimension(new DimensionTransition(request.target,destination,Vec3.ZERO,old.getYRot(),old.getXRot(),DimensionTransition.DO_NOTHING));
                    if(arrived==null)throw new IllegalStateException("Native dimension transfer failed: "+old.getUUID());moved.put(old.getUUID(),arrived);arrived.load(state);arrived.moveTo(destination.x,destination.y,destination.z,old.getYRot(),old.getXRot());arrived.setDeltaMovement(Vec3.ZERO);arrived.fallDistance=0;
                }
                commitCursor++;
            }
            if(commitCursor<order.size())return false;
            if(!mounted){mount(group.mounts(),moved);group.adapter().mounted(group,moved);mounted=true;}
            verify(tag);if(!group.adapter().verify(group,request.target,tag.getCompound("Structure"),deadline))return false;
            if(!landing.verify(deadline,moved.keySet(),group.adapter().structures()))return false;
            ListTag targetEntities=new ListTag();for(Entity e:moved.values()){RiftMountPersistence.mark(e);e.setDeltaMovement(Vec3.ZERO);e.fallDistance=0;targetEntities.add(RiftEntitySnapshot.capture(e));}tag.put("DestinationEntities",targetEntities);group.adapter().zeroMotion(request.target);
            return true;
        }
        public void publish(CompoundTag tag){throw new UnsupportedOperationException("Use budgeted publish");}
        public boolean publishStep(CompoundTag tag,long deadline) {
            if(!group.adapter().cleanupSource(group,deadline))return false;
            if(checkpoint==null){checkpoint=new RiftNativeCheckpoint(journal,moved.keySet());var sourcePositions=new LinkedHashSet<ChunkPos>();for(var positions:sourceIndex.snapshot().values())sourcePositions.addAll(positions);for(Entity e:group.members())sourcePositions.add(e.chunkPosition());checkpoint.include(request.source,sourcePositions);checkpoint.include(request.target,moved.values().stream().map(Entity::chunkPosition).toList());for(Entity e:moved.values())if(e instanceof ServerPlayer p)checkpoint.player(p);}
            if(!checkpoint.step(budget))return false;
            if(!group.adapter().checkpoint(group,request.target,tag.getCompound("Structure"),journal,deadline))return false;
            if(checkpointed==null){checkpointed=journal.write(id,"CHECKPOINTED",tag);return false;}
            if(!checkpointed.isDone())return false;checkpointed.getNow(null);
            if(!published) {
                List<ServerPlayer> companions=moved.values().stream().filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast).toList();
                group.adapter().track(request.target,companions);RiftGroupTracking.publish(request.target,moved.values(),companions);
                for(ServerPlayer p:companions)for(Entity vehicle:moved.values())if(!vehicle.getPassengers().isEmpty())p.connection.send(new ClientboundSetPassengersPacket(vehicle));
                var packet=new RiftArrivalPacket(id,request.target.dimension().location(),AllaySkyProfile.context(request.target),new ArrayList<>(moved.keySet()),new ArrayList<>(group.adapter().structures()),group.mounts());
                for(ServerPlayer p:companions)PacketDistributor.sendToPlayer(p,packet);published=true;publishedAt=server.getTickCount();
            }
            if(!ready.containsAll(players)&&server.getTickCount()-publishedAt<200)return false;
            if(released==null){released=journal.write(id,"RELEASED",tag);return false;}if(!released.isDone())return false;released.getNow(null);return true;
        }
        public void rollback(CompoundTag tag){throw new UnsupportedOperationException("Use budgeted rollback");}
        public boolean rollbackStep(CompoundTag tag,long deadline) {
            if(tag==null)return true;
            if(!rollbackMounted){for(Entity e:moved.values())e.stopRiding();rollbackMounted=true;}
            ListTag originals=tag.getList("Entities",Tag.TAG_COMPOUND);
            while(rollbackCursor<originals.size()&&System.nanoTime()<deadline) {
                CompoundTag saved=originals.getCompound(rollbackCursor);UUID uuid=saved.getUUID("UUID");Entity current=moved.get(uuid);
                if(current==null){current=request.source.getEntity(uuid);if(current!=null)moved.put(uuid,current);}
                if(current==null){for(ServerLevel world:server.getAllLevels()){current=world.getEntity(uuid);if(current!=null)break;}if(current!=null)moved.put(uuid,current);}
                if(current==null&&!saved.getBoolean("Player")){current=net.minecraft.world.entity.EntityType.loadEntityRecursive(saved.copy(),request.source,e->e);if(current==null||!request.source.addFreshEntity(current))throw new IllegalStateException("Cannot reconstruct missing member during compensation: "+uuid);moved.put(uuid,current);}
                if(current==null)throw new IllegalStateException("Player disconnected during compensation; recovery journal retained: "+uuid);
                if(current.level()!=request.source){Vec3 pos=position(saved);try(var travel=RiftTransferContext.open(request.source.dimension(),Set.of(uuid))){Entity restored=current.changeDimension(new DimensionTransition(request.source,pos,Vec3.ZERO,current.getYRot(),current.getXRot(),DimensionTransition.DO_NOTHING));if(restored==null)throw new IllegalStateException("Compensation transfer failed");current=restored;moved.put(uuid,current);}}
                current.load(saved);RiftMountPersistence.mark(current);rollbackCursor++;
            }
            if(rollbackCursor<originals.size())return false;
            if(!group.adapter().restoreReady(group,tag.getCompound("Structure"),deadline))return false;
            mount(group.mounts(),moved);group.adapter().restored(group,moved,tag.getCompound("Structure"));verifyMounts(group.mounts(),moved);
            if(checkpoint==null){checkpoint=new RiftNativeCheckpoint(journal,moved.keySet());var from=new LinkedHashSet<ChunkPos>();if(sourceIndex!=null&&sourceIndex.ready())for(var ps:sourceIndex.snapshot().values())from.addAll(ps);for(Entity e:moved.values())from.add(e.chunkPosition());checkpoint.include(request.source,from);checkpoint.include(request.target,landing==null?List.of():landing.reservation().stream().map(b->new ChunkPos(net.minecraft.core.BlockPos.containing(b.getCenter()))).toList());for(Entity e:moved.values())if(e instanceof ServerPlayer p)checkpoint.player(p);}
            return checkpoint.step(budget)&&group.adapter().checkpoint(group,request.source,tag.getCompound("Structure"),journal,deadline);
        }
        public void release(){group.adapter().release(group);group.adapter().freeze(group,false);sourceTickets.close();if(sourceIndex!=null)sourceIndex.close();if(targetIndex!=null)targetIndex.close();if(landing!=null)landing.close();if(preparedLanding!=null)preparedLanding.close();unlock(id);}
        public void quarantine(CompoundTag tag,Throwable error){RiftTransferCoordinator.this.quarantine(id,ids(group),error);fail(error);}
        void fail(Throwable error){for(UUID uuid:players){ServerPlayer p=server.getPlayerList().getPlayer(uuid);if(p!=null)p.displayClientMessage(Component.literal("裂口传送未完成："+error.getMessage()),true);}LOG.warn("Rift transaction {} ended at {}",id,machine.stage(),error);}
    }
    static CompoundTag vector(Vec3 p){CompoundTag t=new CompoundTag();t.putDouble("x",p.x);t.putDouble("y",p.y);t.putDouble("z",p.z);return t;}
    static Vec3 position(CompoundTag tag){ListTag p=tag.getList("Pos",Tag.TAG_DOUBLE);return new Vec3(p.getDouble(0),p.getDouble(1),p.getDouble(2));}
    static void mount(List<StormTransferGroup.Mount> mounts,Map<UUID,Entity> entities){for(var edge:mounts.stream().sorted(Comparator.comparingInt(StormTransferGroup.Mount::order)).toList()){Entity p=entities.get(edge.passenger()),v=entities.get(edge.vehicle());if(p==null||v==null||p==v||p.getVehicle()!=v&&!p.startRiding(v,true))throw new IllegalStateException("Cannot restore riding edge");}var vehicles=new LinkedHashSet<UUID>();for(var edge:mounts)vehicles.add(edge.vehicle());for(UUID id:vehicles){var riders=mounts.stream().filter(m->m.vehicle().equals(id)).sorted(Comparator.comparingInt(StormTransferGroup.Mount::order)).map(m->entities.get(m.passenger())).toList();((com.iridium126.createmanaindustry.mixin.allaystorm.RiftPassengersAccessor)entities.get(id)).cmi$orderedPassengers(com.google.common.collect.ImmutableList.copyOf(riders));}}
    static void verifyMounts(List<StormTransferGroup.Mount> mounts,Map<UUID,Entity> entities){for(var e:mounts){Entity p=entities.get(e.passenger()),v=entities.get(e.vehicle());if(p==null||v==null||p.getVehicle()!=v||v.getPassengers().indexOf(p)!=e.order())throw new IllegalStateException("Riding edge/order verification failed");}}
}
