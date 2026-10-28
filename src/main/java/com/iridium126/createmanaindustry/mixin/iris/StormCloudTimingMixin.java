package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Optional whole native-stage A/B timing; does not claim to isolate cloud shader instructions. */
@Mixin(value=CompositeRenderer.class,remap=false)
public abstract class StormCloudTimingMixin {
    @WrapMethod(method="renderAll()V")
    private void cmi$cloudTiming(Operation<Void> original){
        var session=ShaderPackSession.current();boolean timing=session.beginCloudTiming(this),success=false;
        try{original.call();success=true;}finally{if(timing)session.endCloudTiming(this,success);}
    }
}
