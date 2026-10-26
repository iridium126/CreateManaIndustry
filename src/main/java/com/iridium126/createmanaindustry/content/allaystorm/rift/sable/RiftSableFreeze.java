package com.iridium126.createmanaindustry.content.allaystorm.rift.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.physics.constraint.*;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import org.joml.*;
import java.util.*;

/** Per-body lock, never a global pause. Exact pinning at both physics boundaries avoids solver drift. */
public final class RiftSableFreeze {
    private record Lock(Pose3d pose,Vector3d linear,Vector3d angular,FixedConstraintHandle constraint,long tick,Map<net.minecraft.world.level.ChunkPos,Long> loaded){}
    private static final Map<ServerSubLevel,Lock> LOCKS=new IdentityHashMap<>();
    public static boolean frozen(ServerSubLevel sub){return LOCKS.containsKey(sub)||com.iridium126.createmanaindustry.content.allaystorm.rift.StormRiftTransfers.frozen(sub.getUniqueId());}
    public static boolean at(net.minecraft.world.level.Level level,net.minecraft.core.BlockPos pos){if(level==null||level.isClientSide||LOCKS.isEmpty())return false;var sub=dev.ryanhcode.sable.Sable.HELPER.getContaining(level,net.minecraft.world.phys.Vec3.atCenterOf(pos));return sub instanceof ServerSubLevel server&&frozen(server);}
    public static void lock(ServerSubLevel sub) {
        if(LOCKS.containsKey(sub))return;
        var pipeline=SubLevelContainer.getContainer(sub.getLevel()).physicsSystem().getPipeline();
        var body=RigidBodyHandle.of(sub);Pose3d pose=new Pose3d(sub.logicalPose());
        Vector3d local=new Vector3d(pose.rotationPoint()),world=pose.transformPosition(new Vector3d(local));
        var constraint=pipeline.addConstraint(sub,null,new FixedConstraintConfiguration(local,world,new Quaterniond(pose.orientation()).invert()));
        LOCKS.put(sub,new Lock(pose,body.getLinearVelocity(new Vector3d()),body.getAngularVelocity(new Vector3d()),constraint,sub.getLevel().getGameTime(),new HashMap<>()));pin(pipeline);
    }
    public static void pin(PhysicsPipeline pipeline) {
        for(var item:LOCKS.entrySet()) {
            var sub=item.getKey();if(sub.isRemoved()||SubLevelContainer.getContainer(sub.getLevel()).physicsSystem().getPipeline()!=pipeline)continue;
            var pose=item.getValue().pose;var current=sub.getMassTracker().getCenterOfMass();var center=current==null?new Vector3d(pose.position()):pose.transformPosition(new Vector3d(current));pipeline.teleport(sub,center,pose.orientation());sub.logicalPose().set(pose);pipeline.resetVelocity(sub);sub.updateLastPose();
        }
    }
    public static void unlock(ServerSubLevel sub,boolean restoreVelocity) {
        Lock lock=LOCKS.remove(sub);if(lock==null)return;
        if(lock.constraint.isValid())lock.constraint.remove();
        if(!sub.isRemoved()){for(var chunk:sub.getPlot().getLoadedChunks()){long elapsed=sub.getLevel().getGameTime()-lock.loaded.getOrDefault(chunk.getPos(),lock.tick);shift(chunk.getChunk().getBlockTicks(),elapsed);shift(chunk.getChunk().getFluidTicks(),elapsed);}var pipeline=SubLevelContainer.getContainer(sub.getLevel()).physicsSystem().getPipeline();pipeline.resetVelocity(sub);if(restoreVelocity)pipeline.addLinearAndAngularVelocity(sub,lock.linear,lock.angular);}
    }
    private static <T> void shift(net.minecraft.world.ticks.TickContainerAccess<T> access,long elapsed){if(elapsed<=0)return;if(!(access instanceof net.minecraft.world.ticks.LevelChunkTicks<T> queue))throw new IllegalStateException("Unsupported plot tick container");var ticks=queue.getAll().toList();queue.removeIf(ignored->true);for(var tick:ticks)queue.schedule(new net.minecraft.world.ticks.ScheduledTick<>(tick.type(),tick.pos(),tick.triggerTick()+elapsed,tick.priority(),tick.subTickOrder()));}
    public static void loaded(ServerSubLevel sub,net.minecraft.world.level.ChunkPos pos){Lock lock=LOCKS.get(sub);if(lock!=null)lock.loaded.putIfAbsent(pos,sub.getLevel().getGameTime());sub.buildMassTracker();sub.updateMergedMassData(1);var pipeline=SubLevelContainer.getContainer(sub.getLevel()).physicsSystem().getPipeline();pipeline.onStatsChanged(sub);pin(pipeline);}
    public static long serializationTime(ServerSubLevel sub,net.minecraft.world.level.ChunkPos pos,long ordinary){Lock lock=LOCKS.get(sub);return lock==null?ordinary:lock.loaded.getOrDefault(pos,lock.tick);}
    public static <T> net.minecraft.world.ticks.ScheduledTick<T> peek(net.minecraft.server.level.ServerLevel level,net.minecraft.world.ticks.ScheduledTick<T> tick){if(tick==null)return null;var sub=dev.ryanhcode.sable.Sable.HELPER.getContaining(level,net.minecraft.world.phys.Vec3.atCenterOf(tick.pos()));if(!(sub instanceof ServerSubLevel server)||!LOCKS.containsKey(server))return tick;long frozen=serializationTime(server,new net.minecraft.world.level.ChunkPos(tick.pos()),level.getGameTime());return new net.minecraft.world.ticks.ScheduledTick<>(tick.type(),tick.pos(),level.getGameTime()+java.lang.Math.max(1,tick.triggerTick()-frozen),tick.priority(),tick.subTickOrder());}
    public static void velocities(ServerSubLevel sub,net.minecraft.nbt.CompoundTag tag) {
        Lock lock=LOCKS.get(sub);if(lock!=null){tag.put("linear_velocity",dev.ryanhcode.sable.util.SableNBTUtils.writeVector3d(lock.linear));tag.put("angular_velocity",dev.ryanhcode.sable.util.SableNBTUtils.writeVector3d(lock.angular));}
    }
    public static void clear(){LOCKS.clear();}
}
