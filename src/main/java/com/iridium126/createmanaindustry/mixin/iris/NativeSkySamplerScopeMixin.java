package com.iridium126.createmanaindustry.mixin.iris;

import com.google.common.collect.ImmutableSet;
import com.iridium126.createmanaindustry.client.render.shaderpack.NativeSkyBuildScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.targets.RenderTargets;

@Mixin(value=IrisSamplers.class,remap=false)
public abstract class NativeSkySamplerScopeMixin {
    @ModifyVariable(method="addRenderTargetSamplers",at=@At("HEAD"),argsOnly=true)
    private static RenderTargets cmi$colors(RenderTargets targets){return NativeSkyBuildScope.targets(targets);}
    @ModifyVariable(method="addWorldDepthSamplers",at=@At("HEAD"),argsOnly=true)
    private static RenderTargets cmi$depth(RenderTargets targets){return NativeSkyBuildScope.targets(targets);}
}
