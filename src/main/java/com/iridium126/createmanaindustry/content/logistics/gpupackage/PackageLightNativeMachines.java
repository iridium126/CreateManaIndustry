package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.BeltTunnelInteractionHandler;
import com.simibubi.create.content.logistics.chute.AbstractChuteBlock;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.funnel.*;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Record equivalents of Create's PackageEntity-specific landing and centering branches. */
final class PackageLightNativeMachines {
    private PackageLightNativeMachines() {}
    static boolean center(ServerLevel level,PackageLightStore.Entry entry,Vec3 target) {
        var p=entry.state().pose();float yaw=p.yaw(),goal=((int)yaw)/90*90;
        var pose=new PackageLease.Pose(p.x()+(target.x-p.x())*.2,p.y()+(target.y-p.y())*.2,p.z()+(target.z-p.z())*.2,
                p.vx()*.75f,p.vy()*.1875f,p.vz()*.75f,yaw+Mth.wrapDegrees(goal-yaw)*.5f);
        PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(pose,entry.state().flags()));
        entry.insertionDelay=Math.max(0,entry.insertionDelay-3);return entry.insertionDelay==0;
    }
    static void inside(ServerLevel level,PackageLightStore.Entry entry,BlockPos at) {
        var state=level.getBlockState(at);
        if(state.getBlock() instanceof FunnelBlock&&!state.getValue(AbstractFunnelBlock.POWERED)&&!state.getValue(FunnelBlock.EXTRACTING)) {
            var facing=AbstractFunnelBlock.getFunnelFacing(state);
            var open=Vec3.atCenterOf(at).add(Vec3.atLowerCornerOf(facing.getNormal()).scale(-.125));
            var diff=entry.position().subtract(open);
            if((facing.getAxis().choose(diff.x,diff.y,diff.z)<0)==(facing.getAxisDirection()==Direction.AxisDirection.POSITIVE))return;
            var filter=BlockEntityBehaviour.get(level,at,FilteringBehaviour.TYPE);
            double dy=facing==Direction.UP?.25:-.5;
            if(filter!=null&&filter.test(entry.box(level))&&!center(level,entry,open.add(0,dy,0)))return;
            PackageLightGameplay.inserted(level,entry,AbstractFunnelBlock.tryInsert(level,at,entry.box(level).copy(),false));
        } else if(BeltBlock.canTransportObjects(state)&&entry.state().pose().vy()<=0) {
            if(!center(level,entry,Vec3.atCenterOf(at).add(0,5/16f,0)))return;
            if(BeltTunnelInteractionHandler.getTunnelOnPosition(level,at)!=null)return;
            var handler=level.getCapability(Capabilities.ItemHandler.BLOCK,at,null);
            if(handler!=null)PackageLightGameplay.inserted(level,entry,handler.insertItem(0,entry.box(level).copy(),false));
        }
    }
    static void landed(ServerLevel level,PackageLightStore.Entry entry) {
        var pos=entry.position();BlockPos feet=BlockPos.containing(pos);
        BlockPos chute=BlockPos.containing(pos.add(0,.5,0)).below();
        if(level.getBlockState(chute).getBlock() instanceof AbstractChuteBlock) {
            var input=BlockEntityBehaviour.get(level,chute,DirectBeltInputBehaviour.TYPE);
            if(input==null||!input.canInsertFromSide(Direction.UP)||!center(level,entry,Vec3.atBottomCenterOf(chute.above())))return;
            if(PackageLightGameplay.inserted(level,entry,input.handleInsertion(entry.box(level).copy(),Direction.UP,false))&&entry.tossedBy!=null){
                var player=level.getServer().getPlayerList().getPlayer(entry.tossedBy);
                if(player!=null)com.simibubi.create.foundation.advancement.AllAdvancements.PACKAGE_CHUTE_THROW.awardTo(player);
            }
            return;
        }
        BlockPos belt=BeltBlock.canTransportObjects(level.getBlockState(feet))?feet:feet.below();
        if(BeltBlock.canTransportObjects(level.getBlockState(belt))){inside(level,entry,belt);return;}
        // Basin, saw, drain and millstone landings explicitly require ItemEntity in Create.
        // Only the depot/station/ejector shared landing behaviour accepts PackageEntity.
        if(BlockEntityBehaviour.get(level,feet,DepotBehaviour.TYPE)==null)return;
        var input=BlockEntityBehaviour.get(level,feet,DirectBeltInputBehaviour.TYPE);
        if(input!=null&&center(level,entry,Vec3.atCenterOf(feet).add(0,5/16f,0)))
            PackageLightGameplay.inserted(level,entry,input.handleInsertion(entry.box(level).copy(),Direction.DOWN,false));
    }
}
