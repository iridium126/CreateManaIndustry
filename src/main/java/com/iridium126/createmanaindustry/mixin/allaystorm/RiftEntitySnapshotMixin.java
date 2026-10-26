package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftEntitySnapshot;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class RiftEntitySnapshotMixin {
    @WrapOperation(method="saveWithoutId",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;isVehicle()Z"))
    private boolean cmi$singleSnapshot(Entity entity,Operation<Boolean> original) {
        return !RiftEntitySnapshot.singleMember(entity)&&original.call(entity);
    }
}
