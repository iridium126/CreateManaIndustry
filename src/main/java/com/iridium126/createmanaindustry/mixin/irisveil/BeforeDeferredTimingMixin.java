package com.iridium126.createmanaindustry.mixin.irisveil;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets="top.leonx.irisveil.compat.iris.BeforeDeferredWorldRenderers$Frame",remap=false)
public abstract class BeforeDeferredTimingMixin {
    @WrapMethod(method="renderCallbacks",require=0)
    private void cmi$callbacks(Operation<Void> original){
        var scope=ParticleFrameStages.begin("irisveil.before-deferred.callbacks");boolean success=false;
        try{original.call();success=true;}finally{if(scope!=null)scope.end(success);}
    }
}
