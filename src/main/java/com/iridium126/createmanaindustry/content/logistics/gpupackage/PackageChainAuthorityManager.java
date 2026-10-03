package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.network.*;
import com.iridium126.createmanaindustry.infrastructure.config.ServerConfig;
import com.simibubi.create.content.kinetics.chainConveyor.*;
import com.simibubi.create.content.logistics.box.PackageItem;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Actual server native-object adapter. Readiness remains gated by the client's chain resources;
 * unprepared/no-authority objects keep their normal Create simulation and replication. */
@EventBusSubscriber(modid=CreateManaIndustry.MODID)
public final class PackageChainAuthorityManager {
    private record Location(ChainConveyorBlockEntity conveyor,BlockPos connection) {}
    private record Discovery(Location location,ChainConveyorPackage box,PackageLease.Identity identity) {}
    private record Outgoing(Session session,ClientboundChainPackagePacket packet) {}
    private static final class Scan {
        final ChainConveyorBlockEntity conveyor;
        Iterator<ChainConveyorPackage> boxes;Iterator<Map.Entry<BlockPos,List<ChainConveyorPackage>>> travel;BlockPos connection;
        Scan(ChainConveyorBlockEntity conveyor){this.conveyor=conveyor;boxes=conveyor.getLoopingPackages().iterator();travel=conveyor.getTravellingPackages().entrySet().iterator();}
        ChainConveyorPackage next(){while(!boxes.hasNext()){if(!travel.hasNext())return null;var entry=travel.next();connection=entry.getKey();boxes=entry.getValue().iterator();}return boxes.next();}
    }
    private static final class Runtime {
        final ServerLevel level;
        final Map<UUID,Session> sessions=new LinkedHashMap<>();
        final Map<Long,Session> epochs=new HashMap<>();
        final LinkedHashMap<PackageLease.Identity,Discovery> discovery=new LinkedHashMap<>();
        final IdentityHashMap<ChainConveyorBlockEntity,Set<PackageLease.Identity>> queued=new IdentityHashMap<>();
        final LinkedHashMap<ChainConveyorBlockEntity,Scan> scans=new LinkedHashMap<>();
        final Map<PackageLease.Identity,Target> leased=new HashMap<>();
        final Set<ChainConveyorBlockEntity> invalidating=Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<ChainConveyorBlockEntity> dirty=new LinkedHashSet<>();boolean batching;
        final ArrayDeque<Outgoing> outgoing=new ArrayDeque<>();
        Runtime(ServerLevel level){this.level=level;}
    }
    private static final class Session {
        final Runtime runtime;final PackageChainAuthority authority;
        final Map<Location,Track> tracks=new LinkedHashMap<>();
        final ByteBuffer decoded=ByteBuffer.allocateDirect(PackageChainEventCodec.BATCH*64);
        int nextTrack,messages;long messageTick=-1;boolean closing,failed;
        Session(Runtime runtime,ServerPlayer player){this.runtime=runtime;authority=new PackageChainAuthority(player.getUUID(),PackageIdentityData.get(runtime.level).epoch(),runtime.level.getGameTime(),
                PackageLease.AUTHORITY_HEARTBEAT_TIMEOUT_TICKS,()->runtime.level.tickRateManager().tickrate());runtime.epochs.put(authority.epoch(),this);}
        void send(ClientboundChainPackagePacket packet){if(runtime.batching)runtime.outgoing.addLast(new Outgoing(this,packet));else deliver(packet);}
        void deliver(ClientboundChainPackagePacket packet) {
            var player=runtime.level.getServer().getPlayerList().getPlayer(authority.owner());if(player==null || player.serverLevel()!=runtime.level)return;
            try{PacketDistributor.sendToPlayer(player,packet);}
            catch(RuntimeException error){failed=true;CreateManaIndustry.LOGGER.error("[CMI packages] chain transport failed; retiring session",error);}
        }
        void close(){if(closing)return;closing=true;runtime.epochs.remove(authority.epoch(),this);try{authority.close();}finally{send(new ClientboundChainPackagePacket(ClientboundChainPackagePacket.CLOSE,runtime.level.dimension().location(),authority.epoch(),null,null,null,0,0,0));}}
    }
    private record Track(int index,PackageNativeChainPlan plan,UUID parent) {}
    private static final class Target implements PackageChainAuthority.Target {
        final Discovery discovery;final Session session;final Track track;boolean removed,activated;
        Target(Discovery discovery,Session session,Track track){this.discovery=discovery;this.session=session;this.track=track;}
        @Override public PackageLease.Identity identity(){return discovery.identity;}
        @Override public PackageChainAuthority.State snapshot(long tick){return track.plan.snapshot(discovery.box,tick);}
        @Override public boolean eligible(){var identity=(PackageIdentified)discovery.box;return !removed && identity.cmi$packageId()==identity().id() && identity.cmi$packageGeneration()==identity().generation()
                && track.plan.current() && track.plan.contains(discovery.box) && PackageItem.isPackage(discovery.box.item);}
        @Override public int eligibility(){return track.plan.eligibility(discovery.box);}
        @Override public boolean freeze() {
            return ((PackageChainAccess)discovery.location.conveyor).cmi$acquirePackage(discovery.box,discovery.location.connection,new PackageOwnershipList.Owner<>() {
                @Override public void materialize(ChainConveyorPackage box) {
                    var baseline=session.authority.baseline(identity());
                    if(baseline!=null)track.plan.materialize(box,baseline,session.runtime.level.getGameTime(),session.authority.active(identity()));
                }
                @Override public void released(ChainConveyorPackage box,boolean deleted){removed=deleted;session.authority.release(identity());}
            });
        }
        @Override public boolean validate(PackageChainAuthority.Event event,long tick){var baseline=session.authority.baseline(identity());
            return baseline!=null && track.plan.reachable(baseline,event,tick) && track.plan.validate(event,discovery.box);}
        @Override public boolean commit(PackageChainAuthority.Event event,long tick){return track.plan.commit(event,discovery.box);}
        @Override public void released(PackageChainAuthority.Baseline baseline,boolean frozen) {
            session.runtime.leased.remove(identity(),this);
            if(frozen && !removed) {
                // Core invalidates identity before this callback; never restore into a new lease.
                track.plan.materialize(discovery.box,baseline,session.runtime.level.getGameTime(),activated);
                ((PackageChainAccess)discovery.location.conveyor).cmi$restorePackage(discovery.box);
                session.runtime.dirty.add(discovery.location.conveyor);
            }
            if(!session.closing && !session.authority.closed())session.send(packet(session,ClientboundChainPackagePacket.RELEASED,baseline,null));
            if(!removed && !discovery.location.conveyor.isRemoved() && !session.runtime.invalidating.contains(discovery.location.conveyor)) {
                var identified=(PackageIdentified)discovery.box;
                identified.cmi$packageIdentity(identity().id(),Math.incrementExact(identity().generation()));
                session.runtime.dirty.add(discovery.location.conveyor);
                enqueue(session.runtime,new Discovery(discovery.location,discovery.box,new PackageLease.Identity(identified.cmi$packageId(),identified.cmi$packageGeneration())));
            }
        }
    }
    private static final Map<ServerLevel,Runtime> WORLDS=new IdentityHashMap<>();
    private PackageChainAuthorityManager() {}
    public static void register(ChainConveyorBlockEntity conveyor) {
        if(ServerConfig.packageGpuAuthority && conveyor.getLevel() instanceof ServerLevel level)
            WORLDS.computeIfAbsent(level,Runtime::new).scans.putIfAbsent(conveyor,new Scan(conveyor));
    }
    /** Called only on a successful native append, not every tick or render. */
    public static void observe(ChainConveyorBlockEntity conveyor,ChainConveyorPackage box,BlockPos connection) {
        if(!ServerConfig.packageGpuAuthority || !(conveyor.getLevel() instanceof ServerLevel level))return;
        var runtime=WORLDS.computeIfAbsent(level,Runtime::new);var value=(PackageIdentified)box;
        if(value.cmi$packageId()<=0 || value.cmi$packageGeneration()<=0)PackageAuthorityManager.identify(box,level);
        var identity=new PackageLease.Identity(value.cmi$packageId(),value.cmi$packageGeneration());var location=new Location(conveyor,connection);
        var previous=runtime.leased.get(identity);
        if(previous!=null && (!previous.discovery.location.equals(location) || previous.discovery.box!=box)) {
            // Same native item entering a new acquisition needs a fresh sidecar lifecycle. Old
            // GPU flights retain their exact original identity and cannot change this object.
            if(previous.discovery.box==box)value.cmi$packageIdentity(identity.id(),Math.incrementExact(identity.generation()));
            else value.cmi$packageIdentity(PackageIdentityData.get(level).identity(),1);
            identity=new PackageLease.Identity(value.cmi$packageId(),value.cmi$packageGeneration());
            if(previous.discovery.box!=box || !((PackageChainAccess)previous.discovery.location.conveyor).cmi$ownsPackage(box))previous.session.authority.release(previous.identity());
        }
        enqueue(runtime,new Discovery(location,box,identity));
    }
    private static void enqueue(Runtime runtime,Discovery discovery) {
        if(runtime.discovery.size()>=262144 && !runtime.discovery.containsKey(discovery.identity))return;
        var old=runtime.discovery.put(discovery.identity,discovery);
        if(old!=null){var keys=runtime.queued.get(old.location.conveyor);if(keys!=null)keys.remove(old.identity);}
        runtime.queued.computeIfAbsent(discovery.location.conveyor,key->new HashSet<>()).add(discovery.identity);
    }
    public static void unregister(ChainConveyorBlockEntity conveyor) {
        if(!(conveyor.getLevel() instanceof ServerLevel level))return;var runtime=WORLDS.get(level);if(runtime==null)return;
        runtime.scans.remove(conveyor);var queued=runtime.queued.remove(conveyor);if(queued!=null)for(var identity:queued)runtime.discovery.remove(identity);
        runtime.invalidating.add(conveyor);
        try{for(var session:runtime.sessions.values())for(var it=session.tracks.values().iterator();it.hasNext();) {
            var track=it.next();if(track.plan.conveyor()==conveyor){session.authority.invalidateTrack(track.index);it.remove();}
        }}finally{runtime.invalidating.remove(conveyor);}
    }
    @SubscribeEvent public static void tick(LevelTickEvent.Pre event) {
        if(!(event.getLevel() instanceof ServerLevel level))return;var runtime=WORLDS.get(level);if(runtime==null)return;
        if(!ServerConfig.packageGpuAuthority){close(runtime);WORLDS.remove(level);return;}
        long tick=level.getGameTime();
        for(var it=runtime.sessions.values().iterator();it.hasNext();) {
            var session=it.next();
            if(session.failed || !PackageAuthorityManager.chainPeer(level,session.authority.owner(),null) || session.authority.expired(tick)){session.close();it.remove();continue;}
            session.authority.tick(tick);if(session.authority.closed()){session.close();it.remove();continue;}
            for(var tracks=session.tracks.values().iterator();tracks.hasNext();) {
                var track=tracks.next();var frame=frame(track.plan.conveyor());
                if(!track.plan.current() || frame==null || !Objects.equals(track.parent,frame.parent())
                        || !PackageAuthorityManager.chainPeer(level,session.authority.owner(),frame.region())){session.authority.invalidateTrack(track.index);tracks.remove();}
            }
        }
        flush(runtime);
        if(!PackageAuthorityManager.hasChainPeer(level))return;
        long deadline=System.nanoTime()+ServerConfig.packageMainThreadBudgetNanos();int visits=0;
        while(!runtime.scans.isEmpty() && visits++<64 && System.nanoTime()<deadline) {
            var it=runtime.scans.entrySet().iterator();var entry=it.next();var scan=entry.getValue();
            if(scan.conveyor.isRemoved()){it.remove();continue;}
            try{var box=scan.next();if(box==null)it.remove();else observe(scan.conveyor,box,scan.connection);}
            catch(ConcurrentModificationException changed){entry.setValue(new Scan(scan.conveyor));}
        }
        visits=0;int queued=runtime.discovery.size();
        while(!runtime.discovery.isEmpty() && queued-->0 && visits++<64 && System.nanoTime()<deadline) {
            var it=runtime.discovery.values().iterator();var discovery=it.next();it.remove();var keys=runtime.queued.get(discovery.location.conveyor);if(keys!=null)keys.remove(discovery.identity);
            if(runtime.leased.containsKey(discovery.identity))continue;
            var conveyor=discovery.location.conveyor;if(conveyor.isRemoved() || !PackageItem.isPackage(discovery.box.item))continue;
            var frame=frame(conveyor);if(frame==null){enqueue(runtime,discovery);continue;}
            var player=PackageAuthorityManager.electChain(level,frame.region());
            if(player==null){enqueue(runtime,discovery);continue;}
            var session=runtime.sessions.computeIfAbsent(player.getUUID(),id->new Session(runtime,player));var track=session.tracks.get(discovery.location);
            if(track!=null&&!Objects.equals(track.parent,frame.parent())){session.authority.invalidateTrack(track.index);session.tracks.remove(discovery.location);track=null;}
            if(track==null || !track.plan.current()) {
                if(session.nextTrack>=131072){session.close();continue;}
                try{track=new Track(session.nextTrack,new PackageNativeChainPlan(conveyor,discovery.location.connection,1),frame.parent());session.nextTrack++;}
                catch(IllegalArgumentException unavailable){enqueue(runtime,discovery);continue;}
                session.tracks.put(discovery.location,track);session.send(trackPacket(session,track));
            }
            if(!track.plan.contains(discovery.box))continue;
            long seen=session.authority.observedGeneration(discovery.identity.id());
            if(seen>=discovery.identity.generation()) {
                // Reloading an old native save may reconstruct the old generation. The live
                // sidecar namespace still remembers it; never append that retired identity.
                var value=(PackageIdentified)discovery.box;value.cmi$packageIdentity(discovery.identity.id(),Math.incrementExact(seen));
                runtime.dirty.add(conveyor);enqueue(runtime,new Discovery(discovery.location,discovery.box,new PackageLease.Identity(value.cmi$packageId(),value.cmi$packageGeneration())));continue;
            }
            var target=new Target(discovery,session,track);var baseline=session.authority.offer(target,track.index,track.plan.track().revision(),tick);
            if(baseline==null){enqueue(runtime,discovery);continue;}
            runtime.leased.put(discovery.identity,target);session.send(packet(session,ClientboundChainPackagePacket.OFFER,baseline,discovery));
        }
    }
    public static void receive(ServerboundChainPackagePacket packet,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player) || !ServerConfig.packageGpuAuthority)return;
        var runtime=WORLDS.get(player.serverLevel());var session=runtime==null?null:runtime.sessions.get(player.getUUID());
        if(session==null || session.authority.epoch()!=packet.epoch() || !PackageAuthorityManager.chainPeer(runtime.level,player.getUUID(),null))return;
        long tick=runtime.level.getGameTime();if(session.messageTick!=tick){session.messageTick=tick;session.messages=0;}if(++session.messages>512)return;
        try {
            var core=session.authority;
            if(packet.action()==ServerboundChainPackagePacket.HEARTBEAT){if(!core.heartbeat(player.getUUID(),packet.epoch(),tick))session.close();return;}
            if(packet.action()==ServerboundChainPackagePacket.EVENTS) {
                PackageChainAuthority.Result result;runtime.batching=true;
                try{result=core.events(player.getUUID(),packet.epoch(),packet.sequence(),tick,ByteBuffer.wrap(packet.events()),session.decoded);}
                finally{runtime.batching=false;flush(runtime);}
                if(result==PackageChainAuthority.Result.ACCEPTED || result==PackageChainAuthority.Result.DUPLICATE)
                    session.send(new ClientboundChainPackagePacket(ClientboundChainPackagePacket.ACK,runtime.level.dimension().location(),packet.epoch(),null,null,null,0,0,packet.sequence()));
                else session.close();return;
            }
            if(packet.action()==ServerboundChainPackagePacket.RELEASE){core.release(player.getUUID(),packet.epoch(),packet.index(),packet.identity(),packet.leaseEpoch(),tick);return;}
            var target=runtime.leased.get(packet.identity());if(target==null || target.session!=session)return;
            var baseline=packet.action()==ServerboundChainPackagePacket.PREPARED
                    ?core.prepared(player.getUUID(),packet.epoch(),packet.index(),packet.identity(),packet.leaseEpoch(),packet.revision(),packet.candidate(),tick)
                    :core.ready(player.getUUID(),packet.epoch(),packet.index(),packet.identity(),packet.leaseEpoch(),packet.revision(),tick);
            // Invalid or delayed controls cannot revoke a newer confirmed owner. A stalled
            // frozen preparation expires independently after two ticks.
            if(baseline==null)return;
            if(packet.action()==ServerboundChainPackagePacket.FINAL_READY)target.activated=true;
            session.send(packet(session,packet.action()==ServerboundChainPackagePacket.PREPARED?ClientboundChainPackagePacket.FINAL:ClientboundChainPackagePacket.ACTIVE,baseline,null));
        }catch(RuntimeException failure){CreateManaIndustry.LOGGER.error("[CMI packages] native chain authority failed",failure);session.close();}
    }
    public static void receiveInteraction(ServerboundChainInteractionPacket packet,IPayloadContext context) {
        if(!(context.player() instanceof ServerPlayer player) || !ServerConfig.packageGpuAuthority)return;
        var request=packet.request();var runtime=WORLDS.get(player.serverLevel());var session=runtime==null?null:runtime.epochs.get(request.epoch());
        var result=PackageChainAuthority.Result.STALE;
        if(session!=null && !session.closing) {
            long tick=runtime.level.getGameTime();
            if(session.messageTick!=tick){session.messageTick=tick;session.messages=0;}
            if(++session.messages>512)result=PackageChainAuthority.Result.INVALID;
            else try {
                runtime.batching=true;
                var target=runtime.leased.get(request.identity());
                result=session.authority.pickup(player.getUUID(),request,tick,b->{
                    if(target==null || target.session!=session || player.isSpectator()
                            || com.simibubi.create.foundation.utility.AdventureUtil.isAdventure(player))return null;
                    var conveyor=target.discovery.location.conveyor;var pos=conveyor.getBlockPos();
                    int maxRange=com.simibubi.create.infrastructure.config.AllConfigs.server().kinetics.maxChainConveyorLength.get()+16;
                    if(!runtime.level.isLoaded(pos) || !player.canInteractWithBlock(pos,maxRange))return null;
                    var state=target.track.plan.pickupCheckpoint(b,request.progress(),tick);if(state==null)return null;
                    var p=state.pose();var center=new net.minecraft.world.phys.Vec3(p.x(),p.y()-9.0/16,p.z());
                    var bounds=new net.minecraft.world.phys.AABB(center,center).move(0,-.25,0).expandTowards(0,.5,0).inflate(.45);
                    var frame=PackageChainSpace.capture(runtime.level,pos);
                    if(frame==null||!Objects.equals(target.track.parent,frame.parent()))return null;
                    var from=player.getEyePosition();double range=player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.BLOCK_INTERACTION_RANGE)+1;
                    var to=com.simibubi.create.foundation.utility.RaycastHelper.getTraceTarget(player,range,from);
                    return frame.hits(bounds,from,to)?state:null;
                },state->{
                    var item=target.discovery.box.item.copy();
                    // Remove the exact native identity before awarding its contents. The core
                    // has already claimed this transaction; retries cannot award it again.
                    target.track.plan.removePicked(target.discovery.box,state);
                    if(player.getMainHandItem().isEmpty())player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,item);
                    else player.getInventory().placeItemBackInInventory(item);
                    target.discovery.location.conveyor.setChanged();runtime.dirty.add(target.discovery.location.conveyor);
                });
                if(result==PackageChainAuthority.Result.FAILED)session.close();
            }catch(RuntimeException failure){CreateManaIndustry.LOGGER.error("[CMI packages] native chain pickup failed",failure);session.close();result=PackageChainAuthority.Result.FAILED;}
            finally{runtime.batching=false;flush(runtime);}
        }
        PacketDistributor.sendToPlayer(player,new ClientboundChainInteractionPacket(request.epoch(),request.identity(),request.transaction(),result));
    }
    private static ClientboundChainPackagePacket trackPacket(Session session,Track track) {
        var plan=track.plan;var nodes=plan.nodes().stream().map(n->new ClientboundChainPackagePacket.Node(n.threshold(),n.flags())).toList();
        return new ClientboundChainPackagePacket(ClientboundChainPackagePacket.TRACK,session.runtime.level.dimension().location(),session.authority.epoch(),
                new ClientboundChainPackagePacket.Track(track.index,plan.conveyor().getBlockPos(),plan.connection(),plan.track(),nodes,track.parent),null,null,0,0,0);
    }
    private static ClientboundChainPackagePacket packet(Session session,int action,PackageChainAuthority.Baseline baseline,Discovery discovery) {
        return new ClientboundChainPackagePacket(action,session.runtime.level.dimension().location(),session.authority.epoch(),null,baseline,
                discovery==null?null:BuiltInRegistries.ITEM.getKey(discovery.box.item.getItem()),discovery==null?0:PackageItem.getWidth(discovery.box.item),discovery==null?0:PackageItem.getHeight(discovery.box.item),0);
    }
    /** Native add/notify inside a validated transaction batch publishes one BE snapshot per
     * touched conveyor, after every item move finishes, rather than one snapshot per package. */
    public static boolean deferNotification(ChainConveyorBlockEntity conveyor) {
        if(!(conveyor.getLevel() instanceof ServerLevel level))return false;
        var runtime=WORLDS.get(level);if(runtime==null || !runtime.batching)return false;runtime.dirty.add(conveyor);return true;
    }
    private static void flush(Runtime runtime) {
        // Deliver terminal ownership notices before vanilla BE snapshots, after inventory
        // callbacks finish. A send failure cannot interrupt native removal before the award.
        while(!runtime.outgoing.isEmpty()){var next=runtime.outgoing.removeFirst();next.session.deliver(next.packet);}
        if(runtime.dirty.isEmpty())return;var dirty=new ArrayList<>(runtime.dirty);runtime.dirty.clear();
        for(var conveyor:dirty)if(!conveyor.isRemoved())conveyor.notifyUpdate();
    }
    private static void close(Runtime runtime){try{for(var session:runtime.sessions.values())session.close();}finally{flush(runtime);}}
    private static PackageChainSpace.Frame frame(ChainConveyorBlockEntity conveyor){
        try{return PackageChainSpace.capture(conveyor.getLevel(),conveyor.getBlockPos());}
        catch(RuntimeException|LinkageError unavailable){return null;}
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event){if(event.getLevel() instanceof ServerLevel level){var runtime=WORLDS.remove(level);if(runtime!=null)close(runtime);}}
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){WORLDS.clear();}
}
