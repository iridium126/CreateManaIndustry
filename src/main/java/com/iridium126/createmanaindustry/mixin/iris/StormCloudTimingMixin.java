package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import net.irisshaders.iris.pipeline.CompositePass;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Optional whole native-stage A/B timing; does not claim to isolate cloud shader instructions. */
@Mixin(value=CompositeRenderer.class,remap=false)
public abstract class StormCloudTimingMixin {
    @Shadow @Final private CompositePass compositePass;
    @WrapMethod(method="renderAll()V")
    private void cmi$cloudTiming(Operation<Void> original){
        var session=ShaderPackSession.current();boolean timing=session.beginCloudTiming(this,compositePass.name().toLowerCase(java.util.Locale.ROOT)),success=false;
        try{original.call();success=true;}finally{if(timing)session.endCloudTiming(this,success);}
    }
}
