package com.iridium126.createmanaindustry.mixin.iris;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.particles.shaderpack.HexIrisHook;
import com.iridium126.createmanaindustry.client.particles.hex.HexPatternService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.shadows.frustum.FrustumHolder;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=ShadowRenderer.class,remap=false)
public abstract class HexShadowRenderMixin {
    @Shadow @Final private boolean shouldRenderEntities;
    @Shadow @Final private boolean shouldRenderPlayer;
    @Shadow private FrustumHolder entityFrustumHolder;
    @WrapMethod(method="renderShadows")
    private void cmi$hexShadowScope(LevelRendererAccessor renderer,Camera camera,Operation<Void> original){if(!CreateManaIndustry.HEX_ACTIVE){original.call(renderer,camera);return;}HexIrisHook.begin();try{original.call(renderer,camera);}finally{HexIrisHook.end();}}
    // Both frusta are prepared here; credentials must exist before native player callbacks.
    @Inject(method="renderShadows",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/culling/Frustum;prepare(DDD)V",ordinal=1,shift=At.Shift.AFTER))
    private void cmi$hexShadows(LevelRendererAccessor renderer,Camera camera,CallbackInfo ci){if(CreateManaIndustry.HEX_ACTIVE)HexIrisHook.draw(camera,entityFrustumHolder.getFrustum(),shouldRenderEntities,shouldRenderPlayer);}
}
