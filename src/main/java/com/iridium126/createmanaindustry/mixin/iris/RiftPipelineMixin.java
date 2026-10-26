package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.RiftIrisPipeline;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=IrisRenderingPipeline.class,remap=false)
public abstract class RiftPipelineMixin implements RiftIrisPipeline {
    @Unique private long cmi$riftGeneration;
    @Inject(method="<init>(Lnet/irisshaders/iris/shaderpack/programs/ProgramSet;)V",at=@At("TAIL"))
    private void cmi$skyPrograms(ProgramSet programs,CallbackInfo ci) {
        var session=ShaderPackSession.current();session.verifyRiftPrograms(programs);cmi$riftGeneration=session.generation();
    }
    @Override public long cmi$riftGeneration(){return cmi$riftGeneration;}
}
