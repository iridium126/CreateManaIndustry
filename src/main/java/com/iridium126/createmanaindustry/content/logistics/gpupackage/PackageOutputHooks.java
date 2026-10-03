package com.iridium126.createmanaindustry.content.logistics.gpupackage;

import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** Complete output pose and impulse before the join event captures a durable record. */
public final class PackageOutputHooks {
    private PackageOutputHooks() {}
    public static Entity prepare(Level world,Entity original,BlockPos source,Direction outlet) {
        if(!(world instanceof ServerLevel)||original.getClass()!=ItemEntity.class
                ||!PackageItem.isPackage(((ItemEntity)original).getItem()))return original;
        var item=(ItemEntity)original;
        var box=PackageEntity.fromDroppedItem(world,item,item.getItem());
        // Include the machine's interaction volume as well as any shape overhang.
        // This is a single source shape lookup at creation, never a CPU physics step.
        var bounds=new AABB(source);
        var shape=world.getBlockState(source).getCollisionShape(world,source);
        if(!shape.isEmpty())bounds=bounds.minmax(shape.bounds().move(source));
        var pose=PackageOutputPose.clearSource(PackageOutputPose.dropped(item.position(),item.getDeltaMovement(),box.getYRot()),
                box.getBbWidth(),box.getBbHeight(),bounds,outlet);
        box.setPos(pose.x(),pose.y(),pose.z());
        box.setDeltaMovement(pose.vx()/20.0,pose.vy()/20.0,pose.vz()/20.0);
        return box;
    }
}
