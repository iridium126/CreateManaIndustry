package com.iridium126.createmanaindustry.mixin.iris;

import com.google.common.collect.ImmutableList;
import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = CompositeRenderer.class, remap = false)
abstract class DlssIrisCompositeMixin {
    @Shadow @Final private CompositePass compositePass;
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private CustomUniforms customUniforms;
    @Unique private DlssIrisAccess.Pass cmi$postPass;

    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableList;get(I)Ljava/lang/Object;"))
    private Object cmi$reconstructionCut(ImmutableList<?> passes, int index, Operation<Object> original) {
        Object pass = original.call(passes, index);
        if (compositePass == CompositePass.COMPOSITE) {
            if (DlssRuntime.irisPass(pass, renderTargets)) customUniforms.update();
            cmi$postPass = DlssRuntime.irisPost() ? (DlssIrisAccess.Pass) pass : null;
        }
        return pass;
    }

    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;viewport(IIII)V"))
    private void cmi$postViewport(int x, int y, int width, int height, Operation<Void> original) {
        var pass = cmi$postPass;
        if (pass == null || !DlssRuntime.irisPost() || pass.cmi$drawBuffers().length == 0) {
            original.call(x, y, width, height);
            return;
        }
        var size = DlssRuntime.irisPostSize(pass.cmi$drawBuffers()[0]);
        var viewport = pass.cmi$viewport();
        original.call((int) (size.x * viewport.viewportX()), (int) (size.y * viewport.viewportY()),
                (int) (size.x * viewport.scale()), (int) (size.y * viewport.scale()));
    }

    @WrapOperation(method = "renderAll", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gl/program/ComputeProgram;dispatch(FF)V"))
    private void cmi$postCompute(ComputeProgram compute, float width, float height, Operation<Void> original) {
        if (DlssRuntime.irisPost()) {
            original.call(compute, (float) DlssRuntime.irisPostWidth((int) width), (float) DlssRuntime.irisPostHeight((int) height));
        } else original.call(compute, width, height);
    }
}
