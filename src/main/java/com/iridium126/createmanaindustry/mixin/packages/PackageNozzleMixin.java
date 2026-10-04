package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageForceHooks;
import com.simibubi.create.content.kinetics.fan.NozzleBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures Create's already-synchronized nozzle force inputs; package positions stay GPU-only. */
@Mixin(NozzleBlockEntity.class)
public abstract class PackageNozzleMixin {
    @Shadow private float range;
    @Shadow private boolean pushing;

    @Inject(method="tick",at=@At("RETURN"))
    private void cmi$captureForce(CallbackInfo ci){PackageForceHooks.nozzle((NozzleBlockEntity)(Object)this,range,pushing);}
}
