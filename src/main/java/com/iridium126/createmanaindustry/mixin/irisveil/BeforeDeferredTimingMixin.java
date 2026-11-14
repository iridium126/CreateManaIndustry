package com.iridium126.createmanaindustry.mixin.irisveil;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import top.leonx.irisveil.compat.iris.BeforeDeferredWorldRenderers;

@Mixin(value = BeforeDeferredWorldRenderers.Frame.class, remap = false)
public abstract class BeforeDeferredTimingMixin {
    @WrapMethod(method = "renderCallbacks()V")
    private void cmi$callbacks(Operation<Void> original){
        var scope=ParticleFrameStages.begin("irisveil.before-deferred.callbacks");boolean success=false;
        try{original.call();success=true;}finally{if(scope!=null)scope.end(success);}
    }
}
