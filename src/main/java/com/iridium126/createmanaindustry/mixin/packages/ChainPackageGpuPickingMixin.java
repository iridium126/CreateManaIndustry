package com.iridium126.createmanaindustry.mixin.packages;

import com.iridium126.createmanaindustry.client.particles.packages.PackageWorldRuntime;
import com.simibubi.create.content.kinetics.chainConveyor.ChainPackageInteractionHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs at Create's existing package stage, preserving its earlier glue/chain/tool handlers. */
@Mixin(ChainPackageInteractionHandler.class)
public abstract class ChainPackageGpuPickingMixin {
    @Inject(method="onUse()Z",at=@At("HEAD"),cancellable=true)
    private static void cmi$gpuPick(CallbackInfoReturnable<Boolean> cir){if(PackageWorldRuntime.chainUse())cir.setReturnValue(true);}
}
