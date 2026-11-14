package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.packages.render.PackageShaderHook;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelRenderer.class, priority = 1001)
abstract class PackageMainRenderMixin {
    @Inject(method = "renderLevel", at = @At(value = "INVOKE_STRING",
            target = "Lnet/minecraft/util/profiling/ProfilerFiller;popPush(Ljava/lang/String;)V",
            args = "ldc=blockentities"))
    private void cmi$drawGpuPackages(CallbackInfo ci) {
        if (PackageShaderHook.wantsMainDraw()) {
            PackageShaderHook.render(((LevelRenderer) (Object) this).getFrustum());
        }
    }
}
