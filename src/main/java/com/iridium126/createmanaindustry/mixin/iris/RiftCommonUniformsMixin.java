package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.RiftIrisUniforms;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=CommonUniforms.class,remap=false)
public abstract class RiftCommonUniformsMixin {
    @Inject(method="addNonDynamicUniforms",at=@At("TAIL"))
    private static void cmi$skyUniforms(UniformHolder uniforms,IdMap ids,PackDirectives directives,FrameUpdateNotifier notifier,CallbackInfo ci){RiftIrisUniforms.bind(uniforms);}
}
