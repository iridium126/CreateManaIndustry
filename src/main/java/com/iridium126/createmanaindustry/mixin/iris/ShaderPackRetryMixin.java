package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.*;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=Iris.class,remap=false)
abstract class ShaderPackRetryMixin {
    @WrapMethod(method="loadExternalShaderpack")
    private static boolean cmi$load(String name,Operation<Boolean> original){
        boolean loaded=original.call(name);
        if(!loaded&&ShaderPackSession.current().requestUnmodifiedRetry())try{return original.call(name);}finally{ShaderPackSession.finishRetry();}
        return loaded;
    }
    @WrapMethod(method="createPipeline")
    private static WorldRenderingPipeline cmi$pipeline(NamespacedId dimension,Operation<WorldRenderingPipeline> original){
        WorldRenderingPipeline result=original.call(dimension);
        if(Iris.getCurrentPack().isPresent()&&!(result instanceof IrisRenderingPipeline))ShaderPackSession.pipelineFailed();
        return result;
    }
}
