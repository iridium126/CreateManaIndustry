package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;
import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.sugar.Local;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FinalPassRenderer.class, remap = false)
abstract class DlssIrisFinalMixin {
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private CustomUniforms customUniforms;
    @Unique private ImmutableSet<Integer> cmi$finalReadsFromAlt;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void cmi$captureFinalFlips(CallbackInfo ci, @Local(argsOnly = true, ordinal = 0) ImmutableSet<Integer> flipped) {
        cmi$finalReadsFromAlt = flipped;
    }

    @Inject(method = "renderFinalPass", at = @At("HEAD"))
    private void cmi$reconstructWithoutTaa(CallbackInfo ci) {
        if (DlssRuntime.irisFinal(renderTargets, cmi$finalReadsFromAlt)) customUniforms.update();
    }

    @Inject(method = "renderFinalPass", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/targets/RenderTargets;getRenderTargetCount()I", ordinal = 0))
    private void cmi$restoreHistoryTargets(CallbackInfo ci) {
        DlssRuntime.finishIrisPost();
    }
}
