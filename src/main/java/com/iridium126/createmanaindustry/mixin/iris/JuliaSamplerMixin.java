package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.allaystorm.rift.JuliaProjection;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Iris allocates a real sampler unit. No colortex number or hard-coded texture unit is reserved. */
@Mixin(value=IrisSamplers.class,remap=false)
public abstract class JuliaSamplerMixin {
    @Inject(method="addCompositeSamplers",at=@At("TAIL"))
    private static void cmi$julia(SamplerHolder samplers,RenderTargets targets,CallbackInfo ci) {
        samplers.addDynamicSampler(JuliaProjection::texture,"CmiJuliaHit");
        samplers.addDynamicSampler(()->{
            var pipeline=net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            if(pipeline instanceof com.iridium126.createmanaindustry.client.render.shaderpack.RiftIrisPipeline rift&&rift.cmi$targetSky()!=null)return rift.cmi$targetSky().texture();
            return 0;
        },"CmiTargetSky");
    }
}
