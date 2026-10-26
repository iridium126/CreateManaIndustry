package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftTransferContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin({Entity.class, ServerPlayer.class})
public abstract class RiftDimensionTravelMixin {
    @WrapOperation(method = "changeDimension", at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/common/CommonHooks;onTravelToDimension(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/resources/ResourceKey;)Z"))
    private boolean cmi$approvedTravel(Entity entity, ResourceKey<Level> destination, Operation<Boolean> original) {
        return RiftTransferContext.consume(entity, destination) || original.call(entity, destination);
    }
}
