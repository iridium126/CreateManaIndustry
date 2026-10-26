package com.iridium126.createmanaindustry.mixin.allaystorm;
import com.iridium126.createmanaindustry.content.allaystorm.rift.RiftMountPersistence;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(PlayerList.class)
public abstract class RiftManagedLogoutMixin {
    @WrapOperation(method="remove",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;hasExactlyOnePlayerPassenger()Z"))
    private boolean cmi$keepNativeMount(Entity entity,Operation<Boolean> original){return !RiftMountPersistence.managed(entity)&&original.call(entity);}
}
