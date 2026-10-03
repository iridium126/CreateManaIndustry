package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.particles.shaderpack.PackageShaderHook;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.FrustumHolder;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ShadowRenderer.class,remap=false)
public abstract class PackageShadowRenderMixin {
    @Shadow @Final private boolean shouldRenderEntities;
    @Shadow @Final private boolean shouldRenderBlockEntities;
    @Shadow private FrustumHolder entityFrustumHolder;
    @Shadow @Final private float halfPlaneLength;
    @Shadow @Final private float renderDistanceMultiplier;
    @Shadow @Final private float entityShadowDistanceMultiplier;
    @Inject(method="renderShadows",at=@At(value="INVOKE_STRING",
            target="Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",args="ldc=draw entities"))
    private void cmi$drawPackageShadows(LevelRendererAccessor renderer,Camera camera,CallbackInfo ci) {
        boolean hasEntityFrustum=entityShadowDistanceMultiplier!=1.0F && !(entityShadowDistanceMultiplier<0.0F);
        double blockEntityDistance=halfPlaneLength*(renderDistanceMultiplier*entityShadowDistanceMultiplier);
        PackageShaderHook.renderShadow(camera,entityFrustumHolder.getFrustum(),shouldRenderEntities,shouldRenderBlockEntities,
                ShadowRenderer.visibleBlockEntities,hasEntityFrustum,blockEntityDistance);
    }
}
