package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.render.shaderpack.NativeSkyBuildScope;
import net.irisshaders.iris.samplers.IrisImages;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value=IrisImages.class,remap=false)
public abstract class NativeSkyImageScopeMixin {
    @ModifyVariable(method="addRenderTargetImages",at=@At("HEAD"),argsOnly=true)
    private static RenderTargets cmi$images(RenderTargets targets){return NativeSkyBuildScope.targets(targets);}
}
