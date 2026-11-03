package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.particles.engine.ParticleFrameStages;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value=IrisRenderingPipeline.class,remap=false)
public abstract class TranslucentBoundaryTimingMixin {
    @WrapMethod(method="beginTranslucents")
    private void cmi$boundary(Operation<Void> original){
        var scope=ParticleFrameStages.begin("iris.translucents.begin");boolean success=false;
        try{original.call();success=true;}finally{if(scope!=null)scope.end(success);}
    }
    @WrapOperation(method="beginTranslucents",at=@At(value="INVOKE",target="Lnet/irisshaders/iris/targets/RenderTargets;copyPreTranslucentDepth()V"))
    private void cmi$depth(RenderTargets targets,Operation<Void> original){
        var scope=ParticleFrameStages.begin("iris.translucents.depth-copy");boolean success=false;
        try{original.call(targets);success=true;}finally{if(scope!=null)scope.end(success);}
    }
}
