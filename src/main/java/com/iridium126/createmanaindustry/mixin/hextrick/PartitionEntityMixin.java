package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionCollision;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Entity.class)
public abstract class PartitionEntityMixin {
    @WrapMethod(method="collideBoundingBox")
    private static Vec3 cmi$sweep(Entity entity,Vec3 move,AABB box,Level level,List<VoxelShape> hits,Operation<Vec3> original) {
        // Restrict before the large-world resolver and again after vanilla step/axis resolution.
        return PartitionCollision.clip(entity,original.call(entity,PartitionCollision.clip(entity,move,box),box,level,hits),box);
    }
    @WrapMethod(method="teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FF)Z")
    private boolean cmi$teleportLevel(ServerLevel level,double x,double y,double z,Set<RelativeMovement> relative,float yaw,float pitch,Operation<Boolean> original) {
        Entity entity=(Entity)(Object)this;
        if(entity.level()==level&&!PartitionCollision.teleportAllowed(entity,new Vec3(x,y,z)))return false;
        try(var ignored=PartitionCollision.transit()){return original.call(level,x,y,z,relative,yaw,pitch);}
    }
    @WrapMethod(method="teleportTo(DDD)V")
    private void cmi$teleport(double x,double y,double z,Operation<Void> original) {
        if(!PartitionCollision.teleportAllowed((Entity)(Object)this,new Vec3(x,y,z)))return;
        try(var ignored=PartitionCollision.transit()){original.call(x,y,z);}
    }
}
