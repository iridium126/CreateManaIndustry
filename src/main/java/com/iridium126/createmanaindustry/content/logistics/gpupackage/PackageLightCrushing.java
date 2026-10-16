package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.kinetics.crusher.*;
import com.simibubi.create.foundation.damageTypes.CreateDamageSources;
import com.simibubi.create.infrastructure.config.AllConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** The controller owns an identity, not a proxy entity. Only its native BE tick applies crushing effects. */
public final class PackageLightCrushing {
    private PackageLightCrushing() {}
    static boolean start(ServerLevel level,PackageLightStore.Entry entry,BlockPos at) {
        var state=level.getBlockState(at);
        if(!(state.getBlock() instanceof CrushingWheelControllerBlock)||!state.getValue(CrushingWheelControllerBlock.VALID)
                ||!(level.getBlockEntity(at) instanceof CrushingWheelControllerBlockEntity controller))return false;
        var access=(PackageCrushingAccess)controller;
        // entityInside still traps an already-owned entity when the wheels stop.
        if(access.cmi$ownsPackage(entry.identity))return true;
        if(controller.crushingspeed==0)return false;
        var data=entry.data.getCompound("NeoForgeData");
        if(data.contains("BypassCrushingWheel")){
            var bypass=net.minecraft.nbt.NbtUtils.readBlockPos(data,"BypassCrushingWheel");
            if(bypass.isPresent()&&bypass.get().equals(at))return false;
        }
        return access.cmi$startPackage(entry.identity);
    }
    /** False releases the controller at the same next-tick boundary as a dead native entity. */
    public static boolean tick(ServerLevel level,CrushingWheelControllerBlockEntity controller,PackageLease.Identity identity) {
        var entry=PackageAuthorityManager.light(level,identity);
        if(entry==null)return PackageLightStore.get(level).byIdentity(identity)!=null;
        var at=controller.getBlockPos();if(!entry.bounds().intersects(new AABB(at).inflate(.5)))return false;
        if(controller.crushingspeed==0)return true;
        var facing=controller.getBlockState().getValue(CrushingWheelControllerBlock.FACING);var axis=facing.getAxis();
        int sign=facing.getAxisDirection().getStep();var p=entry.state().pose();
        double motion=Math.max(-controller.crushingspeed,-.5)*-sign;
        float vx=(float)((axis==Direction.Axis.X?motion:(at.getX()+.5-p.x())/2)*20);
        float vy=(float)((axis==Direction.Axis.Y?motion:0)*20);
        float vz=(float)((axis==Direction.Axis.Z?motion:(at.getZ()+.5-p.z())/2)*20);
        int damage=AllConfigs.server().kinetics.crushingDamage.get();
        double x=p.x(),y=p.y(),z=p.z();
        if(entry.health-damage<=0){x=at.getX()+.5+(axis==Direction.Axis.X?1.05*sign:0);
            y=at.getY()+.5+(axis==Direction.Axis.Y?1.05*sign:0);z=at.getZ()+.5+(axis==Direction.Axis.Z?1.05*sign:0);}
        PackageAuthorityManager.updateLight(level,entry,new PackageAuthorityRegion.Snapshot(new PackageLease.Pose(x,y,z,vx,vy,vz,p.yaw()),0));
        if(!PackageLightNativeEnvironment.hurt(level,entry,CreateDamageSources.crush(level)))
            PackageAuthorityManager.pauseForTransfer(level,entry);
        return true;
    }
}
