package com.iridium126.createmanaindustry.mixin.hextrick;

import com.iridium126.createmanaindustry.compat.trickster.partition.PartitionCollision;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

/** Vanilla projectiles integrate directly with setPos rather than Entity.move. Spawn/load stay untouched. */
@Mixin(Entity.class)
public abstract class PartitionProjectileMixin {
    @WrapMethod(method="setPos(DDD)V")
    private void cmi$projectile(double x,double y,double z,Operation<Void> original) {
        Entity e=(Entity)(Object)this;
        if(e instanceof Projectile&&e.tickCount>0&&!e.isRemoved()&&!PartitionCollision.transiting()) {
            Vec3 delta=PartitionCollision.clip(e,new Vec3(x-e.getX(),y-e.getY(),z-e.getZ()),e.getBoundingBox());
            x=e.getX()+delta.x;y=e.getY()+delta.y;z=e.getZ()+delta.z;
        }
        original.call(x,y,z);
    }
}
