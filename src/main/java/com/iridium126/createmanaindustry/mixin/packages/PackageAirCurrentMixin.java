package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.content.logistics.gpupackage.PackageForceHooks;
import com.simibubi.create.content.kinetics.fan.AirCurrent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Capture the current fan input without modifying native entity motion. */
@Mixin(AirCurrent.class)
public abstract class PackageAirCurrentMixin {
    @Inject(method="tick",at=@At("RETURN"))
    private void cmi$captureForce(CallbackInfo ci){PackageForceHooks.fan((AirCurrent)(Object)this);}
}
