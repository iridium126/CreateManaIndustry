package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
@Mixin({Entity.class,ServerPlayer.class})
public abstract class RiftExternalTravelMixin {
    @WrapMethod(method="changeDimension")
    private Entity cmi$locked(DimensionTransition transition,Operation<Entity> original){Entity self=(Entity)(Object)this;return !self.level().isClientSide&&StormRiftTransfers.frozen(self.getUUID())&&!RiftTransferContext.contains(self.getUUID())?null:original.call(transition);}
}
