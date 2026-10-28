package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.RiftIrisUniforms;
import net.irisshaders.iris.gl.uniform.DynamicUniformHolder;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.uniforms.CommonUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=CommonUniforms.class,remap=false)
public abstract class RiftCommonUniformsMixin {
    @Inject(method="addDynamicUniforms",at=@At("TAIL"))
    private static void cmi$skyUniforms(DynamicUniformHolder uniforms,FogMode fogMode,CallbackInfo ci){RiftIrisUniforms.bind(uniforms);}
}
