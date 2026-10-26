package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** A recovery job owns both world's structure IDs until every native plot and storage pointer agrees. */
public final class RiftSableRecovery {
    private static final Map<UUID,Recovery> JOBS=new HashMap<>();
    public static void clear(){JOBS.clear();}
    private static final class Recovery {
        RiftSableStore.Job scan,save;final Map<UUID,ServerSubLevel> subs=new LinkedHashMap<>();
        RiftSableMetadataCheckpoint metadata;boolean metadataApplied;
        int cursor;Iterator<String> chunks;CompoundTag current;ServerSubLevel loading;
        final Set<UUID> ids=new LinkedHashSet<>();final List<CompoundTag> tags=new ArrayList<>();final boolean rollback;
        Recovery(CompoundTag tag,boolean target){rollback=!target;for(Tag t:tag.getCompound("Structure").getList(target?"TargetSubLevels":"SubLevels",Tag.TAG_COMPOUND)){CompoundTag sub=((CompoundTag)t).copy();tags.add(sub);ids.add(sub.getUUID("uuid"));}if(tags.isEmpty())throw new IllegalStateException("Missing complete structure recovery data");}
    }
    public static boolean step(UUID id,ServerLevel source,ServerLevel target,ServerLevel canonical,CompoundTag tag,RiftTransferJournal journal,long deadline) {
        Recovery job=JOBS.computeIfAbsent(id,ignored->new Recovery(tag,canonical==target));
        if(job.scan==null){job.scan=new RiftSableStore.Job(List.of(source,target),job.ids,null,List.of(),journal);return false;}
        if(!job.scan.ready())return false;
        job.scan.finish();
        if(job.cursor==0&&job.chunks==null){RiftSimulatedBridge.discardRecovery(source,job.ids);RiftSimulatedBridge.discardRecovery(target,job.ids);}
        while(System.nanoTime()<deadline&&job.cursor<job.tags.size()) {
            if(job.chunks==null) {
                job.current=job.tags.get(job.cursor);UUID uuid=job.current.getUUID("uuid");
                for(ServerLevel world:List.of(source,target)) {
                    var container=SubLevelContainer.getContainer(world);var old=container.getSubLevel(uuid);
                    if(old instanceof ServerSubLevel sub){RiftSableFreeze.unlock(sub,false);container.removeSubLevel(sub,SubLevelRemovalReason.REMOVED);}
                }
                var container=SubLevelContainer.getContainer(canonical);var plot=job.current.getCompound("plot");int x=plot.getInt("plot_x"),z=plot.getInt("plot_z");
                if(container.getOccupancy().get(container.getIndex(x,z))) {
                    var persisted=job.scan.found(canonical).get(uuid);
                    if(persisted==null||persisted.tag().getCompound("plot").getInt("plot_x")!=x||persisted.tag().getCompound("plot").getInt("plot_z")!=z)throw new IllegalStateException("Recovery plot is occupied by an unproven owner");
                    container.getOccupancy().clear(container.getIndex(x,z));
                }
                job.loading=(ServerSubLevel)container.allocateSubLevel(uuid,x,z,SableNBTUtils.readPose3d(job.current.getCompound("pose")));RiftSableFreeze.lock(job.loading);job.subs.put(uuid,job.loading);
                job.chunks=List.copyOf(plot.getCompound("chunks").getAllKeys()).iterator();
            }
            if(job.chunks.hasNext()) {
                String key=job.chunks.next();var part=job.current.getCompound("plot").copy();CompoundTag one=new CompoundTag();one.put(key,part.getCompound("chunks").get(key));part.put("chunks",one);job.loading.getPlot().load(part);RiftSableFreeze.loaded(job.loading,job.loading.getPlot().toGlobal(new net.minecraft.world.level.ChunkPos(Long.parseLong(key))));
            } else {
                if(job.current.contains("display_name"))job.loading.setName(job.current.getString("display_name"));if(job.current.contains("user_data"))job.loading.setUserDataTag(job.current.getCompound("user_data"));job.loading.updateBoundingBox();job.loading.forceUpdateGlobalBounds();job.chunks=null;job.cursor++;
            }
        }
        return job.cursor>=job.tags.size();
    }
    public static boolean checkpoint(UUID id,ServerLevel source,ServerLevel target,ServerLevel canonical,CompoundTag tag,RiftTransferJournal journal,long deadline) {
        Recovery job=JOBS.get(id);if(job==null)return true;
        if(job.save==null){job.save=new RiftSableStore.Job(List.of(source,target),job.ids,canonical,job.tags,journal);return false;}
        if(!job.save.ready())return false;job.save.finish();
        if(!job.metadataApplied){var original=tag.getCompound("Structure").getList("SubLevels",Tag.TAG_COMPOUND);var sourceContainer=SubLevelContainer.getContainer(source);var targetContainer=SubLevelContainer.getContainer(target);int size=16<<sourceContainer.getLogPlotSize();var moves=new ArrayList<RiftPlotCoordinates.Move>();for(Tag item:original){var old=(CompoundTag)item;var next=job.tags.stream().filter(t->t.getUUID("uuid").equals(old.getUUID("uuid"))).findFirst().orElseThrow();var a=old.getCompound("plot");var b=next.getCompound("plot");int x=(a.getInt("plot_x")+sourceContainer.getOrigin().x)*size,z=(a.getInt("plot_z")+sourceContainer.getOrigin().y)*size;moves.add(new RiftPlotCoordinates.Move(x,z,size,(b.getInt("plot_x")+targetContainer.getOrigin().x)*size-x,(b.getInt("plot_z")+targetContainer.getOrigin().y)*size-z));}var coordinates=new RiftPlotCoordinates(moves);var translation=tag.getCompound("Translation");var delta=new net.minecraft.world.phys.Vec3(translation.getDouble("x"),translation.getDouble("y"),translation.getDouble("z"));for(Tag item:original){var old=(CompoundTag)item;RiftSableMetadata.apply(source,target,job.subs.get(old.getUUID("uuid")),old.getCompound("CMITransportMetadata"),coordinates,delta,canonical==target);}job.metadataApplied=true;}
        if(job.metadata==null)job.metadata=new RiftSableMetadataCheckpoint(List.of(source,target),journal);return job.metadata.step();
    }
    public static void release(UUID id){Recovery job=JOBS.remove(id);if(job!=null)for(var sub:job.subs.values()){RiftSableFreeze.unlock(sub,false);if(job.rollback){var tag=job.tags.stream().filter(t->t.getUUID("uuid").equals(sub.getUniqueId())).findFirst().orElseThrow();SubLevelContainer.getContainer(sub.getLevel()).physicsSystem().getPipeline().addLinearAndAngularVelocity(sub,SableNBTUtils.readVector3d(tag.getCompound("linear_velocity")),SableNBTUtils.readVector3d(tag.getCompound("angular_velocity")));}}}
    private RiftSableRecovery(){}
}
