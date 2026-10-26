package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;
import java.lang.reflect.*;
import java.util.*;

/** Optional Simulated 1.3.2 ABI; the full rope codec already lives in its owning block entity NBT. */
public final class RiftSimulatedBridge {
    private final ServerLevel world;
    private final Object manager;
    private final LinkedHashMap<UUID,Object> ropes=new LinkedHashMap<>();
    private final Set<UUID> activeBefore=new HashSet<>();
    RiftSimulatedBridge(ServerLevel world) {
        this.world=world;
        if(!net.neoforged.fml.ModList.get().isLoaded("simulated")){manager=null;return;}
        String version=net.neoforged.fml.ModList.get().getModContainerById("simulated").orElseThrow().getModInfo().getVersion().toString();
        if(!version.startsWith("1.3.2"))throw new IllegalStateException("Simulated transport ABI is unverified: "+version);
        try{Class<?> type=Class.forName("dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerLevelRopeManager");manager=type.getMethod("getOrCreate",net.minecraft.world.level.Level.class).invoke(null,world);}
        catch(ReflectiveOperationException error){throw new IllegalStateException("Simulated rope-manager ABI mismatch",error);}
    }
    private static Object call(Object object,String name){try{return object.getClass().getMethod(name).invoke(object);}catch(ReflectiveOperationException error){throw new IllegalStateException("Simulated transport ABI: "+name,error);}}
    @SuppressWarnings("unchecked") private Collection<Object> all(){return manager==null?List.of():(Collection<Object>)call(manager,"getAllStrands");}
    @SuppressWarnings("unchecked") private static Iterable<Object> attachments(Object rope){return (Iterable<Object>)call(rope,"getAttachments");}
    Collection<ServerSubLevel> dependencies(UUID touching) {
        var result=new ArrayList<ServerSubLevel>();
        for(Object rope:all()) {
            boolean touches=false;for(Object attachment:attachments(rope))if(touching.equals(call(attachment,"subLevelID")))touches=true;
            if(!touches)continue;
            for(Object attachment:attachments(rope)) {
                UUID id=(UUID)call(attachment,"subLevelID");if(id==null)throw new IllegalStateException("Rope connects to a fixed world anchor");
                var sub=SubLevelContainer.getContainer(world).getSubLevel(id);if(!(sub instanceof ServerSubLevel server))throw new IllegalStateException("Rope dependency is not loaded");result.add(server);
            }
            ropes.put((UUID)call(rope,"getUUID"),rope);
        }
        return result;
    }
    String topology(){StringBuilder out=new StringBuilder();for(var entry:ropes.entrySet()){out.append(entry.getKey());for(Object a:attachments(entry.getValue()))out.append(call(a,"point")).append(call(a,"subLevelID")).append(call(a,"blockAttachment"));}return out.toString();}
    List<AABB> geometry() {
        var boxes=new ArrayList<AABB>();for(Object rope:ropes.values()){org.joml.Vector3dc previous=null;for(Object point:(Iterable<?>)call(rope,"getPoints")){var p=(org.joml.Vector3dc)point;if(previous!=null)boxes.add(new AABB(Math.min(previous.x(),p.x())-.125,Math.min(previous.y(),p.y())-.125,Math.min(previous.z(),p.z())-.125,Math.max(previous.x(),p.x())+.125,Math.max(previous.y(),p.y())+.125,Math.max(previous.z(),p.z())+.125));previous=p;}}return boxes;
    }
    void freeze(boolean lock) {
        var system=SubLevelPhysicsSystem.require(world);
        for(var entry:ropes.entrySet()) {
            var object=(dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject)entry.getValue();
            if(lock&&object.isActive()){call(object,"updatePose");activeBefore.add(entry.getKey());system.removeObject(object);}
            else if(!lock&&activeBefore.remove(entry.getKey())&&!object.isActive())system.addObject(object);
        }
    }
    void removed(){activeBefore.clear();if(manager!=null)for(UUID id:ropes.keySet())try{manager.getClass().getMethod("removeStrand",UUID.class).invoke(manager,id);}catch(ReflectiveOperationException error){throw new IllegalStateException("Rope manager cleanup ABI",error);}}
    public static void freezeRecovery(ServerLevel world){var bridge=new RiftSimulatedBridge(world);for(Object rope:List.copyOf(bridge.all()))for(Object attachment:attachments(rope)){UUID id=(UUID)call(attachment,"subLevelID");if(id!=null&&com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftTransfers.frozen(id)){var object=(dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject)rope;if(object.isActive()){call(rope,"updatePose");SubLevelPhysicsSystem.require(world).removeObject(object);}break;}}}
    static void discardRecovery(ServerLevel world,Set<UUID> ids){var bridge=new RiftSimulatedBridge(world);for(Object rope:List.copyOf(bridge.all()))for(Object attachment:attachments(rope))if(ids.contains(call(attachment,"subLevelID"))){var object=(dev.ryanhcode.sable.api.physics.object.rope.RopePhysicsObject)rope;if(object.isActive())SubLevelPhysicsSystem.require(world).removeObject(object);try{bridge.manager.getClass().getMethod("removeStrand",UUID.class).invoke(bridge.manager,call(rope,"getUUID"));}catch(ReflectiveOperationException error){throw new IllegalStateException("Recovery rope cleanup ABI",error);}break;}}
    static void validate(CompoundTag be,Set<UUID> group) {
        if(!be.contains("Strand"))return;
        var strand=be.getCompound("Strand");if(!strand.contains("uuid",Tag.TAG_STRING)||!strand.contains("points",Tag.TAG_LIST)||!strand.contains("attachments",Tag.TAG_LIST)||!strand.contains("extension_goal",Tag.TAG_ANY_NUMERIC))throw new IllegalStateException("Unknown rope codec structure");
        for(Tag entry:strand.getList("attachments",Tag.TAG_COMPOUND)){var a=(CompoundTag)entry;if(!a.contains("subLevelID",Tag.TAG_STRING)||!group.contains(UUID.fromString(a.getString("subLevelID"))))throw new IllegalStateException("Rope attachment leaves transport closure");}
    }
    static void rebase(CompoundTag be,RiftPlotCoordinates coordinates,Vec3 delta) {
        if(!be.contains("Strand"))return;CompoundTag strand=be.getCompound("Strand");
        for(Tag entry:strand.getList("attachments",Tag.TAG_COMPOUND)){CompoundTag a=(CompoundTag)entry;int[] p=a.getIntArray("blockAttachment");if(p.length!=3)throw new IllegalStateException("Unknown rope block-position codec");BlockPos next=coordinates.map(new BlockPos(p[0],p[1],p[2]));a.putIntArray("blockAttachment",new int[]{next.getX(),next.getY(),next.getZ()});}
        ListTag points=strand.getList("points",Tag.TAG_LIST);
        for(int i=0;i<points.size();i++){ListTag p=(ListTag)points.get(i);if(p.size()!=3)throw new IllegalStateException("Unknown rope vector codec");ListTag next=new ListTag();next.add(DoubleTag.valueOf(p.getDouble(0)+delta.x));next.add(DoubleTag.valueOf(p.getDouble(1)+delta.y));next.add(DoubleTag.valueOf(p.getDouble(2)+delta.z));points.set(i,next);}
    }
}
