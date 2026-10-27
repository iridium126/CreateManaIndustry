package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.NativeSkyView;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=ShaderMap.class,remap=false)
public abstract class NativeSkyShaderMapMixin {
    @Inject(method="getShader",at=@At("HEAD"),cancellable=true)
    private void cmi$targetSky(ShaderKey key,CallbackInfoReturnable<ShaderInstance> ci) {
        var shader=NativeSkyView.override(key);if(shader!=null)ci.setReturnValue(shader);
    }
}
