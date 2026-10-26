package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.sublevel.*;
import dev.ryanhcode.sable.sublevel.plot.*;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.*;
import org.joml.Vector3d;
import java.util.*;

/** Sable 2.0.6 native serialization bridge. Source level fields are never reassigned. */
public final class SableVehicleTransferAdapter extends CreateVehicleTransferAdapter {
    private final LinkedHashMap<UUID,ServerSubLevel> source=new LinkedHashMap<>(),arrived=new LinkedHashMap<>();
    private final Map<UUID,CompoundTag> data=new LinkedHashMap<>();
    private final Map<UUID,Iterator<PlotChunkHolder>> cursors=new HashMap<>();
    private final Map<UUID,List<AABB>> shapes=new HashMap<>();
    private final Map<UUID,BlockPos> slots=new HashMap<>();
    private final Map<UUID,UUID> contained=new HashMap<>(),tracking=new HashMap<>();
    private final ServerSubLevel main;
    private RiftPlotCoordinates coordinates;
    private boolean successful;
    private Iterator<UUID> loading;
    private final Map<UUID,Iterator<PlotChunkHolder>> verification=new HashMap<>();
    private final Set<UUID> verifiedBodies=new HashSet<>();
    private Iterator<String> loadingChunks;
    private UUID loadingId;
    private CompoundTag loadingTag;
    private RiftSableStore.Job store;
    private RiftSableStore.Job inspect;
    private RiftSableMetadataCheckpoint metadata;
    private boolean metadataApplied;
    private Vec3 translation=Vec3.ZERO;
    private ServerLevel targetWorld;
    private final RiftSimulatedBridge simulated;
    private final UUID reservation=UUID.randomUUID();
    private SableVehicleTransferAdapter(ServerSubLevel main){this.main=main;this.simulated=new RiftSimulatedBridge(main.getLevel());}
    public static VehicleTransferAdapter select(Entity member) {
        var sub=owner(member);return sub==null?null:new SableVehicleTransferAdapter(sub);
    }
    public static ServerSubLevel owner(Entity member) {
        Entity root=member.getRootVehicle();SubLevel sub=Sable.HELPER.getContaining(root);if(sub==null&&root instanceof EntityMovementExtension movement)sub=movement.sable$getTrackingSubLevel();if(sub==null)sub=Sable.HELPER.getContaining(member);if(sub==null&&member instanceof EntityMovementExtension movement)sub=movement.sable$getTrackingSubLevel();
        return sub instanceof ServerSubLevel server?server:null;
    }
    @Override public String id(){return "sable-native-2.0.6";}
    @Override public boolean flying(){return true;}
    @Override public Set<UUID> structures(){return Set.copyOf(source.keySet());}
    @Override public Vec3 reference(Entity member){var p=main.logicalPose().transformPosition(new Vector3d(main.getSelfMassTracker().getCenterOfMass()));return new Vec3(p.x,p.y,p.z);}
    @Override public Collection<Entity> collect(Entity member) {
        var pending=new ArrayDeque<ServerSubLevel>();pending.add(main);
        while(!pending.isEmpty()) {
            var sub=pending.removeFirst();if(source.putIfAbsent(sub.getUniqueId(),sub)!=null)continue;
            for(SubLevel dep:SubLevelHelper.getConnectedChain(sub))if(dep!=null&&!source.containsKey(dep.getUniqueId()))pending.add((ServerSubLevel)dep);
            for(var dep:SubLevelHelper.getLoadingDependencyChain(sub))if(dep!=null&&!dep.isRemoved()&&!dep.getPlot().getLoadedChunks().isEmpty()&&!source.containsKey(dep.getUniqueId()))pending.add(dep);
            for(var dep:simulated.dependencies(sub.getUniqueId()))if(!source.containsKey(dep.getUniqueId()))pending.add(dep);
            // Native actor edges are directional. Include the reverse closure as well.
            for(var candidate:SubLevelContainer.getContainer(sub.getLevel()).getAllSubLevels()) {
                if(source.containsKey(candidate.getUniqueId()))continue;
                for(var actor:candidate.getPlot().getBlockEntityActors()) {
                    var deps=actor.sable$getConnectionDependencies();if(deps!=null)for(SubLevel dep:deps)if(dep==sub)pending.add(candidate);
                }
            }
        }
        var entities=new LinkedHashMap<UUID,Entity>();
        var known=new LinkedHashMap<UUID,Entity>();for(Entity entity:main.getLevel().getAllEntities())known.put(entity.getUUID(),entity);for(var sub:source.values())for(var chunk:sub.getPlot().getLoadedChunks())for(Entity entity:RiftKnownEntities.column(main.getLevel(),chunk.getPos()))known.put(entity.getUUID(),entity);
        for(Entity entity:known.values()) {
            SubLevel owner=Sable.HELPER.getContaining(entity);
            SubLevel track=entity instanceof EntityMovementExtension ext?ext.sable$getTrackingSubLevel():null;
            if(owner!=null&&source.containsKey(owner.getUniqueId()))contained.put(entity.getUUID(),owner.getUniqueId());
            if(track!=null&&source.containsKey(track.getUniqueId()))tracking.put(entity.getUUID(),track.getUniqueId());
            if(contained.containsKey(entity.getUUID())||tracking.containsKey(entity.getUUID()))for(Entity e:super.collect(entity))entities.put(e.getUUID(),e);
        }
        for(Entity e:super.collect(member))entities.put(e.getUUID(),e);
        return entities.values();
    }
    @Override protected boolean allowsWorldController(AbstractContraptionEntity e){return contained.containsKey(e.getUUID());}
    @Override protected Entity findChild(Entity parent,UUID id){Entity visible=super.findChild(parent,id);if(visible!=null)return visible;for(var sub:source.values())for(var chunk:sub.getPlot().getLoadedChunks())for(Entity entity:RiftKnownEntities.column(main.getLevel(),chunk.getPos()))if(entity.getUUID().equals(id))return entity;return null;}
    @Override public String topology(){return super.topology()+contained+tracking+simulated.topology();}
    @Override public void prepare(StormTransferGroup group,ServerLevel target) {
        targetWorld=target;
        if(coordinates!=null)return;
        var container=SubLevelContainer.getContainer(target);var occupancy=(BitSet)container.getOccupancy().clone();occupancy.or(RiftPlotReservations.occupied(target));var moves=new ArrayList<RiftPlotCoordinates.Move>();
        int side=1<<container.getLogSideLength();
        for(var sub:source.values()) {
            if(container.getSubLevel(sub.getUniqueId())!=null||container.getHoldingChunkMap().getHoldingSubLevel(sub.getUniqueId())!=null)throw new IllegalStateException("Target structure UUID already exists");
            int index=occupancy.nextClearBit(0);if(index>=side*side)throw new IllegalStateException("No target plot slot");occupancy.set(index);RiftPlotReservations.hold(target,index,reservation);
            int x=index%side,z=index/side;slots.put(sub.getUniqueId(),new BlockPos(x,0,z));
            if(SubLevelContainer.getContainer(sub.getLevel()).getLogPlotSize()!=container.getLogPlotSize())throw new IllegalStateException("Plot size ABI mismatch");
            int size=16<<container.getLogPlotSize(),oldX=sub.getPlot().plotPos.x*size,oldZ=sub.getPlot().plotPos.z*size;
            moves.add(new RiftPlotCoordinates.Move(oldX,oldZ,size,(x+container.getOrigin().x)*size-oldX,(z+container.getOrigin().y)*size-oldZ));
        }
        coordinates=new RiftPlotCoordinates(moves);
    }
    @Override public boolean snapshotReady(StormTransferGroup group,long deadline) {
        if(!super.snapshotReady(group,deadline))return false;
        for(var sub:source.values()) {
            if(sub.isRemoved()||sub.getPlot().getLoadedChunks().isEmpty())throw new IllegalStateException("Source structure is not fully resident: "+sub.getUniqueId()+" removed="+sub.isRemoved()+" chunks="+sub.getPlot().getLoadedChunks().size());
            var cursor=cursors.computeIfAbsent(sub.getUniqueId(),ignored->List.copyOf(sub.getPlot().getLoadedChunks()).iterator());
            var boxes=shapes.computeIfAbsent(sub.getUniqueId(),ignored->new ArrayList<>());
            while(cursor.hasNext()) {
                if(System.nanoTime()>=deadline)return false;
                var holder=cursor.next();CompoundTag part;
                try(var scope=new RiftPlotSnapshot(holder)){part=SubLevelSerializer.toData(sub,new ArrayList<>(source.keySet())).fullTag();}
                if(part.getCompound("plot").getInt("data_version")!=1)throw new IllegalStateException("Unknown Sable plot codec");
                part.put("CMITransportMetadata",RiftSableMetadata.capture(sub));
                CompoundTag previous=data.putIfAbsent(sub.getUniqueId(),part);
                if(previous!=null)previous.getCompound("plot").getCompound("chunks").merge(part.getCompound("plot").getCompound("chunks"));
                var chunk=holder.getChunk();
                for(var section:chunk.getSections())if(!section.hasOnlyAir()) {
                    int sectionIndex=Arrays.asList(chunk.getSections()).indexOf(section),baseY=chunk.getSectionYFromSectionIndex(sectionIndex)<<4;
                    for(int y=0;y<16;y++)for(int x=0;x<16;x++)for(int z=0;z<16;z++) {
                        var state=section.getBlockState(x,y,z);if(state.isAir())continue;
                        BlockPos pos=new BlockPos(holder.getPos().getMinBlockX()+x,baseY+y,holder.getPos().getMinBlockZ()+z);
                        for(AABB box:state.getCollisionShape(sub.getLevel(),pos).toAabbs())boxes.add(box.move(pos));
                    }
                }
            }
        }
        return true;
    }
    @Override public void beginSnapshot(StormTransferGroup group){super.beginSnapshot(group);cursors.clear();data.clear();shapes.clear();}
    @Override public List<AABB> geometry(StormTransferGroup group) {
        var all=new ArrayList<AABB>();
        all.addAll(simulated.geometry());
        for(var item:shapes.entrySet())for(AABB box:item.getValue())all.add(transform(source.get(item.getKey()),box));
        for(Entity e:group.members()) {
            var single=new StormTransferGroup(group.source(),group.key(),group.reference(),List.of(e),List.of(),this);
            for(AABB box:super.geometry(single))all.add(contained.containsKey(e.getUUID())?transform(source.get(contained.get(e.getUUID())),box):box);
        }
        return all;
    }
    private static AABB transform(ServerSubLevel sub,AABB box){return RiftGeometry.transform(box,p->{Vector3d q=sub.logicalPose().transformPosition(new Vector3d(p.x,p.y,p.z));return new Vec3(q.x,q.y,q.z);});}
    @Override public void preflight(StormTransferGroup group,ServerLevel target) {
        super.preflight(group,target);
        var destination=SubLevelContainer.getContainer(target);for(UUID uuid:source.keySet())if(destination.getSubLevel(uuid)!=null||destination.getHoldingChunkMap().getHoldingSubLevel(uuid)!=null)throw new IllegalStateException("Target structure UUID appeared during preparation");
        for(var sub:source.values())for(var actor:sub.getPlot().getBlockEntityActors()){var deps=actor.sable$getConnectionDependencies();if(deps==null||!deps.iterator().hasNext())continue;String type=actor.getClass().getName();if(!Set.of("dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity","dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity","dev.simulated_team.simulated.content.blocks.swivel_bearing.link_block.SwivelBearingPlateBlockEntity","dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity").contains(type)&&!(actor instanceof net.minecraft.world.level.block.entity.BlockEntity be&&be.saveWithFullMetadata(group.source().registryAccess()).contains("Strand")))throw new IllegalStateException("Unverified connection actor codec: "+type);for(SubLevel dep:deps)if(dep==null||!source.containsKey(dep.getUniqueId()))throw new IllegalStateException("Structure has a fixed or unavailable connection anchor");}
        for(CompoundTag tag:data.values())for(String key:tag.getCompound("plot").getCompound("chunks").getAllKeys()) {
            var chunk=tag.getCompound("plot").getCompound("chunks").getCompound(key);
            for(Tag entry:chunk.getList("block_entities",Tag.TAG_COMPOUND))validateConnections((CompoundTag)entry);
        }
    }
    @Override public boolean preflightReady(StormTransferGroup group,ServerLevel target,RiftTransferJournal journal,long deadline) {
        if(inspect==null){if(!RiftSableStore.available(List.of(group.source(),target)))return false;inspect=new RiftSableStore.Job(List.of(group.source(),target),source.keySet(),null,List.of(),journal);return false;}
        if(!inspect.ready())return false;inspect.finish();if(!inspect.found(target).isEmpty())throw new IllegalStateException("Structure UUID already exists in target native storage");preflight(group,target);return true;
    }
    private void validateConnections(CompoundTag tag) {
        for(String[] pair:new String[][]{{"OtherConnector","OtherConnectorSubLevelId"},{"SwivelPlate","SubLevelID"},{"ParentPos","ParentSubLevelId"},{"Goal","GoalSubLevel"}})
            if(tag.contains(pair[0])&&(!tag.hasUUID(pair[1])||!source.containsKey(tag.getUUID(pair[1]))))throw new IllegalStateException("Connection has a non-migrating world anchor: "+pair[0]);
        RiftSimulatedBridge.validate(tag,source.keySet());
    }
    @Override public void freeze(StormTransferGroup group,boolean frozen) {if(!successful)simulated.freeze(frozen);for(var sub:source.values())if(!sub.isRemoved()){if(frozen)RiftSableFreeze.lock(sub);else RiftSableFreeze.unlock(sub,!successful);}}
    @Override public CompoundTag snapshot(StormTransferGroup group) {
        CompoundTag tag=super.snapshot(group);ListTag subs=new ListTag();
        for(var sub:source.values()){CompoundTag copy=data.get(sub.getUniqueId()).copy();RiftSableFreeze.velocities(sub,copy);var slot=slots.get(sub.getUniqueId());copy.putInt("TargetX",slot.getX());copy.putInt("TargetZ",slot.getZ());subs.add(copy);}
        tag.put("SubLevels",subs);ListTag owners=new ListTag();var owned=new HashMap<>(contained);owned.putAll(tracking);for(var entry:owned.entrySet()){CompoundTag owner=new CompoundTag();owner.putUUID("Entity",entry.getKey());owner.putUUID("Structure",entry.getValue());owners.add(owner);}tag.put("EntityOwners",owners);return tag;
    }
    private CompoundTag rebased(UUID id,Vec3 delta) {
        CompoundTag tag=data.get(id).copy(),plot=tag.getCompound("plot");var slot=slots.get(id);plot.putInt("plot_x",slot.getX());plot.putInt("plot_z",slot.getZ());
        RiftPlotCoordinates.sections(plot,main.getLevel().dimensionType().minY(),targetWorld.dimensionType().minY(),targetWorld.dimensionType().height());
        var pose=SableNBTUtils.readPose3d(tag.getCompound("pose"));Vec3 rp=coordinates.map(new Vec3(pose.rotationPoint().x,pose.rotationPoint().y,pose.rotationPoint().z));pose.rotationPoint().set(rp.x,rp.y,rp.z);pose.position().add(delta.x,delta.y,delta.z);tag.put("pose",SableNBTUtils.writePose3d(pose));
        tag.remove("linear_velocity");tag.remove("angular_velocity");
        var bounds=tag.getCompound("world_bounds");for(String axis:List.of("X","Y","Z")){double d=axis.equals("X")?delta.x:axis.equals("Y")?delta.y:delta.z;bounds.putDouble("min"+axis,bounds.getDouble("min"+axis)+d);bounds.putDouble("max"+axis,bounds.getDouble("max"+axis)+d);}
        for(String key:plot.getCompound("chunks").getAllKeys()) {
            CompoundTag chunk=plot.getCompound("chunks").getCompound(key);
            for(Tag entry:chunk.getList("block_entities",Tag.TAG_COMPOUND)){coordinates.blockEntity((CompoundTag)entry);RiftSimulatedBridge.rebase((CompoundTag)entry,coordinates,delta);}
            coordinates.ticks(chunk.getList("block_ticks",Tag.TAG_COMPOUND));coordinates.ticks(chunk.getList("fluid_ticks",Tag.TAG_COMPOUND));
        }
        return tag;
    }
    @Override public boolean commitReady(StormTransferGroup group,ServerLevel target,Vec3 delta,CompoundTag snapshot,long deadline) {
        translation=delta;
        if(loading==null)loading=source.keySet().iterator();
        while(System.nanoTime()<deadline) {
            if(loadingChunks==null) {
                if(!loading.hasNext()){ListTag tags=new ListTag();for(UUID uuid:source.keySet())tags.add(rebased(uuid,delta));snapshot.put("TargetSubLevels",tags);return true;}
                loadingId=loading.next();loadingTag=rebased(loadingId,delta);var plot=loadingTag.getCompound("plot");
                // Rapier requires the first native chunk and its mass data in the allocation's own work unit.
                var container=SubLevelContainer.getContainer(target);var slot=slots.get(loadingId);
                if(container.getOccupancy().get(container.getIndex(slot.getX(),slot.getZ()))||container.getSubLevel(loadingId)!=null)throw new IllegalStateException("Target plot/UUID reservation conflict");
                var sub=(ServerSubLevel)container.allocateSubLevel(loadingId,slot.getX(),slot.getZ(),SableNBTUtils.readPose3d(loadingTag.getCompound("pose")));arrived.put(loadingId,sub);RiftSableFreeze.lock(sub);
                loadingChunks=List.copyOf(plot.getCompound("chunks").getAllKeys()).iterator();
            }
            if(loadingChunks.hasNext()) {
                String key=loadingChunks.next();CompoundTag part=loadingTag.getCompound("plot").copy(),chunks=new CompoundTag();chunks.put(key,part.getCompound("chunks").get(key));part.put("chunks",chunks);
                arrived.get(loadingId).getPlot().load(part);
                var loaded=arrived.get(loadingId);RiftSableFreeze.loaded(loaded,loaded.getPlot().toGlobal(new net.minecraft.world.level.ChunkPos(Long.parseLong(key))));
            } else {
                var sub=arrived.get(loadingId);if(loadingTag.contains("display_name"))sub.setName(loadingTag.getString("display_name"));if(loadingTag.contains("user_data"))sub.setUserDataTag(loadingTag.getCompound("user_data"));sub.updateBoundingBox();sub.forceUpdateGlobalBounds();loadingChunks=null;
            }
        }
        return false;
    }
    @Override public Vec3 destination(Entity entity,Vec3 delta){return contained.containsKey(entity.getUUID())?coordinates.map(entity.position()):super.destination(entity,delta);}
    @Override public Vec3 destination(Entity entity,Vec3 original,Vec3 delta){return contained.containsKey(entity.getUUID())?coordinates.map(original):original.add(delta);}
    @Override public CompoundTag targetSnapshot(Entity entity,CompoundTag original,Vec3 translation){if(!contained.containsKey(entity.getUUID()))return super.targetSnapshot(entity,original,translation);var copy=original.copy();coordinates.entity(copy);return copy;}
    @Override public void mounted(StormTransferGroup group,Map<UUID,Entity> entities) {
        super.mounted(group,entities);
        for(var entry:tracking.entrySet())if(entities.get(entry.getKey()) instanceof EntityMovementExtension ext){ext.sable$setTrackingSubLevel(arrived.get(entry.getValue()));ext.sable$setLastTrackingSubLevelID(entry.getValue());}
    }
    @Override public void restored(StormTransferGroup group,Map<UUID,Entity> entities,CompoundTag snapshot){super.mounted(group,entities);for(var entry:tracking.entrySet())if(entities.get(entry.getKey()) instanceof EntityMovementExtension ext){ext.sable$setTrackingSubLevel(source.get(entry.getValue()));ext.sable$setLastTrackingSubLevelID(entry.getValue());}}
    @Override public boolean verify(StormTransferGroup group,ServerLevel target,CompoundTag snapshot,long deadline){
        if(arrived.size()!=source.size())throw new IllegalStateException("Incomplete target structure closure");
        for(var sub:arrived.values()) {
            if(verifiedBodies.contains(sub.getUniqueId()))continue;
            if(sub.isRemoved()||sub.getPlot().getLoadedChunks().isEmpty())throw new IllegalStateException("Incomplete target structure");
            var cursor=verification.computeIfAbsent(sub.getUniqueId(),ignored->List.copyOf(sub.getPlot().getLoadedChunks()).iterator());
            var expected=snapshot.getList("TargetSubLevels",Tag.TAG_COMPOUND).stream().map(t->(CompoundTag)t).filter(t->t.getUUID("uuid").equals(sub.getUniqueId())).findFirst().orElseThrow().getCompound("plot").getCompound("chunks");
            while(cursor.hasNext()) {
                if(System.nanoTime()>=deadline)return false;
                var holder=cursor.next();CompoundTag actual;try(var scope=new RiftPlotSnapshot(holder)){actual=sub.getPlot().save().getCompound("chunks");}
                String key=Long.toString(sub.getPlot().toLocal(holder.getPos()).toLong());
                if(!expected.contains(key))throw new IllegalStateException("Unexpected target plot chunk");RiftPlotVerification.chunk(expected.getCompound(key),actual.getCompound(key));
            }
            if(System.nanoTime()>=deadline)return false;sub.buildMassTracker();sub.updateMergedMassData(1);
            if(sub.getMassTracker().isInvalid())throw new IllegalStateException("Target structure has invalid mass data");
            SubLevelContainer.getContainer(target).physicsSystem().getPipeline().onStatsChanged(sub);
            verifiedBodies.add(sub.getUniqueId());
        }
        return true;
    }
    @Override public boolean cleanupSource(StormTransferGroup group,long deadline) {
        for(var sub:source.values())if(!sub.isRemoved()){if(System.nanoTime()>=deadline)return false;RiftSableFreeze.unlock(sub,false);SubLevelContainer.getContainer(group.source()).removeSubLevel(sub,SubLevelRemovalReason.REMOVED);}
        successful=true;simulated.removed();return true;
    }
    @Override public boolean restoreReady(StormTransferGroup group,CompoundTag snapshot,long deadline) {
        for(var sub:arrived.values())if(!sub.isRemoved()){if(System.nanoTime()>=deadline)return false;RiftSableFreeze.unlock(sub,false);SubLevelContainer.getContainer(sub.getLevel()).removeSubLevel(sub,SubLevelRemovalReason.REMOVED);}return true;
    }
    @Override public void zeroMotion(ServerLevel target){for(var sub:arrived.values())SubLevelContainer.getContainer(target).physicsSystem().getPipeline().resetVelocity(sub);}
    @Override public void release(StormTransferGroup group){freeze(group,false);for(var sub:arrived.values())RiftSableFreeze.unlock(sub,!successful);RiftPlotReservations.release(reservation);}
    @Override public boolean checkpoint(StormTransferGroup group,ServerLevel canonical,CompoundTag snapshot,RiftTransferJournal journal,long deadline) {
        if(store==null){var tags=new ArrayList<CompoundTag>();for(Tag tag:snapshot.getList(canonical==group.source()?"SubLevels":"TargetSubLevels",Tag.TAG_COMPOUND))tags.add(((CompoundTag)tag).copy());var levels=List.of(group.source(),targetWorld);if(!RiftSableStore.available(levels))return false;store=new RiftSableStore.Job(levels,source.keySet(),canonical,tags,journal);return false;}
        if(!store.ready())return false;store.finish();
        if(!metadataApplied){for(UUID uuid:source.keySet()){var sub=canonical==group.source()?source.get(uuid):arrived.get(uuid);RiftSableMetadata.apply(group.source(),targetWorld,sub,data.get(uuid).getCompound("CMITransportMetadata"),coordinates,translation,canonical!=group.source());}metadataApplied=true;}
        if(metadata==null)metadata=new RiftSableMetadataCheckpoint(List.of(group.source(),targetWorld),journal);return metadata.step();
    }
    @Override public void track(ServerLevel target,List<ServerPlayer> players) {
        var tracking=SubLevelContainer.getContainer(target).trackingSystem();
        if(!(tracking instanceof com.iridium126.createmanaindustry.mixin.allaystorm.sable.RiftSableTrackingAccessor sync))throw new IllegalStateException("Sable tracking ABI unavailable");
        for(var sub:arrived.values())for(var player:players)if(sub.getTrackingPlayers().add(player.getUUID()))sync.cmi$sync(player,sub,null);
    }
}
