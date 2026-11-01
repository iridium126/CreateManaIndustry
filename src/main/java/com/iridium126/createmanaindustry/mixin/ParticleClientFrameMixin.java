package com.iridium126.createmanaindustry.mixin;

import com.iridium126.createmanaindustry.client.particles.engine.ClientFrameCapture;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ParticleClientFrameMixin {
    @WrapMethod(method="runTick")
    private void cmi$fullFrame(boolean render,Operation<Void> original){
        com.iridium126.createmanaindustry.client.particles.engine.NeighborInputCapture.INSTANCE.poll();
        com.iridium126.createmanaindustry.client.particles.engine.ParticleQuerySequence.INSTANCE.poll();
        ClientFrameCapture.INSTANCE.begin();boolean success=false;
        try{original.call(render);success=true;}finally{ClientFrameCapture.INSTANCE.end(success);}
    }
    @Inject(method="runTick",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"))
    private void cmi$beforeSwap(boolean render,CallbackInfo ci){ClientFrameCapture.INSTANCE.beforeDisplay();}
    @Inject(method="destroy",at=@At("HEAD"))
    private void cmi$releaseCaptures(CallbackInfo ci){ClientFrameCapture.INSTANCE.close();com.iridium126.createmanaindustry.client.particles.engine.NeighborInputCapture.INSTANCE.close();com.iridium126.createmanaindustry.client.particles.engine.ParticleQuerySequence.INSTANCE.close();}
}
