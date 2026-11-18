package com.iridium126.createmanaindustry.mixin;

import com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture;
import com.iridium126.createmanaindustry.client.particles.engine.ParticleChainCapture;
import com.iridium126.createmanaindustry.client.particles.engine.spatial.OptixQueryWorker;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ParticleClientFrameMixin {
    @WrapMethod(method = "runTick(Z)V")
    private void cmi$fullFrame(boolean render, Operation<Void> original) {
        if (OptixQueryWorker.resourcesRetiring()) OptixQueryWorker.retire();
        ParticleChainCapture.INSTANCE.poll();
        var capture = ClientFrameCapture.INSTANCE;
        if (!capture.busy()) {
            original.call(render);
            return;
        }
        boolean success = false;
        try {
            capture.begin();
            original.call(render);
            success = true;
        } finally {
            capture.end(success);
        }
    }
    @Inject(method = "runTick(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void cmi$beforeSwap(boolean render, CallbackInfo ci) {
        ClientFrameCapture.INSTANCE.beforeDisplay();
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void cmi$releaseCaptures(CallbackInfo ci) {
        try {
            ClientFrameCapture.INSTANCE.close();
        } finally {
            ParticleChainCapture.INSTANCE.close();
        }
    }
}
