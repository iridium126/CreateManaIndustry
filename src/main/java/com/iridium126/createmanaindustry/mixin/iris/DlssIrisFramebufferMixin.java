package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlFramebuffer.class, remap = false)
abstract class DlssIrisFramebufferMixin {
    @Shadow @Final private Int2IntMap attachments;

    @Inject(method = {"bind", "bindAsDrawBuffer"}, at = @At("HEAD"))
    private void cmi$postDraw(CallbackInfo ci) {
        DlssRuntime.irisFramebuffer((GlFramebuffer) (Object) this, attachments, true);
    }

    @Inject(method = "bindAsReadBuffer", at = @At("HEAD"))
    private void cmi$postRead(CallbackInfo ci) {
        DlssRuntime.irisFramebuffer((GlFramebuffer) (Object) this, attachments, false);
    }
}
