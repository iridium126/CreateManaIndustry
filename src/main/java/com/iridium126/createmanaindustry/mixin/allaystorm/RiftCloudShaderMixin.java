package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.client.allaystorm.rift.VanillaRiftRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class RiftCloudShaderMixin {
    @Inject(method="getRendertypeCloudsShader",at=@At("RETURN"),cancellable=true)
    private static void cmi$clouds(CallbackInfoReturnable<ShaderInstance> cir) {
        var shader=VanillaRiftRenderer.clouds();
        if(shader!=null)cir.setReturnValue(shader);
    }
}
