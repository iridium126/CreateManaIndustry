package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageForceHooks;
import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageAuthorityManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.logistics.box.PackageEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep native pair callbacks and the other body's response, replacing only the GPU body's kick. */
@Mixin(Entity.class)
public abstract class PackageExternalImpulseMixin {
    @WrapOperation(method="push(Lnet/minecraft/world/entity/Entity;)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;push(DDD)V"))
    private void cmi$pairImpulse(Entity entity,double x,double y,double z,Operation<Void> original){
        if(!(entity instanceof PackageEntity box)||!PackageForceHooks.simulated(box))original.call(entity,x,y,z);
    }
    @Inject(method="push(DDD)V",at=@At("HEAD"))
    private void cmi$unknownImpulse(double x,double y,double z,CallbackInfo ci){
        // Unmodelled custom impulses must materialize the checkpoint rather than disappear.
        if((Object)this instanceof PackageEntity box&&PackageForceHooks.simulated(box))PackageAuthorityManager.release(box);
    }
}
