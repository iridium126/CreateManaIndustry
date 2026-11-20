package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiBufferSource.BufferSource.class)
abstract class XessFgMotionBufferMixin {
    @Inject(method = "getBuffer", at = @At("RETURN"), cancellable = true)
    private void cmi$motion(RenderType type, CallbackInfoReturnable<VertexConsumer> result) {
        var source = result.getReturnValue();
        var tracked = XessFgRuntime.motionBuffer(source, type);
        if (tracked != source) result.setReturnValue(tracked);
    }
}
