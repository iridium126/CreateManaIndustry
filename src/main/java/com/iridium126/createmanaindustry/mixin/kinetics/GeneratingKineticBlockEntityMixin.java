package com.iridium126.createmanaindustry.mixin.kinetics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;

@Mixin(value = GeneratingKineticBlockEntity.class, remap = false)
public abstract class GeneratingKineticBlockEntityMixin {
    @Inject(method = "updateGeneratedRotation", at = @At("HEAD"), cancellable = true)
    private void createmanaindustry$keepTemporaryRotation(CallbackInfo ci) {
        if (TemporaryKinetics.onNativeGenerationChanged((GeneratingKineticBlockEntity) (Object) this))
            ci.cancel();
    }

    @WrapOperation(method = "removeSource", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/base/GeneratingKineticBlockEntity;isSource()Z"))
    private boolean createmanaindustry$rememberOnlyNativeGeneration(GeneratingKineticBlockEntity be,
            Operation<Boolean> original) {
        // The temporary source has its own reactivation check after Create sets the conveyed speed.
        return !TemporaryKinetics.isActive(be) && original.call(be);
    }
}
