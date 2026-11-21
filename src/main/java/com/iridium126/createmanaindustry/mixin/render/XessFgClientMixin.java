package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;
import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class XessFgClientMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void cmi$dlssSceneTarget(CallbackInfoReturnable<RenderTarget> result) {
        var world = DlssRuntime.worldTarget();
        if (world != null) result.setReturnValue(world);
    }
    @Inject(method = "runTick(Z)V", at = @At("HEAD"))
    private void cmi$beginXessFg(boolean render, CallbackInfo ci) {
        XessFgRuntime.begin((Minecraft) (Object) this, render);
    }

    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V"))
    private void cmi$beginXessRender(boolean render, CallbackInfo ci) {
        XessFgRuntime.renderStart();
    }

    @WrapOperation(method = "runTick(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen(II)V"))
    private void cmi$exportXessFrame(RenderTarget target, int width, int height, Operation<Void> original) {
        if (!XessFgRuntime.prepareDisplay(target, width, height)) original.call(target, width, height);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void cmi$releaseXessFg(CallbackInfo ci) {
        DlssRuntime.shutdown();
        XessFgRuntime.shutdown();
    }
}
