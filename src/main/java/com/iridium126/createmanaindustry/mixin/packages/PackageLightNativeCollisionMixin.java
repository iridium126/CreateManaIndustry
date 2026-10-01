package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityManager;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.simibubi.create.content.logistics.box.PackageEntity;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native packages see record colliders at their current position during the transfer boundary. */
@Mixin(Entity.class)
public abstract class PackageLightNativeCollisionMixin {
    @WrapOperation(method="collide",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> cmi$lightContacts(Level level,Entity entity,AABB bounds,Operation<List<VoxelShape>> original){var nativeShapes=original.call(level,entity,bounds);if(!(entity instanceof PackageEntity)||!(level instanceof ServerLevel server))return nativeShapes;var records=PackageAuthorityManager.queryLight(server,bounds);if(records.isEmpty())return nativeShapes;var shapes=new java.util.ArrayList<>(nativeShapes);for(var entry:records)if(entry.bounds().maxY<entity.getBoundingBox().minY+.125)shapes.add(Shapes.create(entry.bounds()));return shapes;}
}
