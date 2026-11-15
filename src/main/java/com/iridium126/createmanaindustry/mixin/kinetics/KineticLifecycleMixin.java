package com.iridium126.createmanaindustry.mixin.kinetics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.iridium126.createmanaindustry.content.kinetics.temporarykinetics.TemporaryKinetics;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

@Mixin(value = SmartBlockEntity.class, remap = false)
public abstract class KineticLifecycleMixin {
    @Shadow private boolean chunkUnloaded;

    @Inject(method = "setRemoved", at = @At("TAIL"))
    private void createmanaindustry$discardRemovedEffect(CallbackInfo ci) {
        if (!chunkUnloaded && (Object) this instanceof KineticBlockEntity be)
            TemporaryKinetics.removed(be, false);
    }

    @Inject(method = "onChunkUnloaded", at = @At("TAIL"))
    private void createmanaindustry$unlinkUnloadedEffect(CallbackInfo ci) {
        if ((Object) this instanceof KineticBlockEntity be)
            TemporaryKinetics.removed(be, true);
    }
}
