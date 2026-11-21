package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.irisshaders.iris.targets.DepthTexture;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class DlssIrisTextureMixins {
    private DlssIrisTextureMixins() {}

    @Mixin(value = RenderTarget.class, remap = false)
    static class Color {
        @ModifyReturnValue(method = {"getMainTexture", "getAltTexture"}, at = @At("RETURN"))
        private int cmi$postColor(int texture) {
            return DlssRuntime.irisTexture(texture);
        }
    }

    @Mixin(value = RenderTargets.class, remap = false)
    static class SceneDepth {
        @Shadow private int currentDepthTexture;
        @Shadow private int cachedDepthBufferVersion;

        @Inject(method = "resizeIfNeeded", at = @At("HEAD"))
        private void cmi$recognizeDepthTargetSwitch(int version, int texture, int width, int height,
                                                   DepthBufferFormat format, PackDirectives directives,
                                                   CallbackInfoReturnable<Boolean> cir) {
            // Versions are local to each Minecraft target. Equal versions can
            // still require Iris to reattach every framebuffer to a new texture.
            if (currentDepthTexture != texture && cachedDepthBufferVersion == version)
                cachedDepthBufferVersion = ~version;
        }

        @ModifyReturnValue(method = "getDepthTexture", at = @At("RETURN"))
        private int cmi$postDepth(int texture) {
            return DlssRuntime.irisTexture(texture);
        }
    }

    @Mixin(value = DepthTexture.class, remap = false)
    static class CopiedDepth {
        @ModifyReturnValue(method = "getTextureId", at = @At("RETURN"))
        private int cmi$postDepth(int texture) {
            return DlssRuntime.irisTexture(texture);
        }
    }

    @Mixin(value = IrisRenderSystem.class, remap = false)
    static class Image {
        @Inject(method = "bindImageTexture", at = @At("HEAD"))
        private static void cmi$postImage(int unit, int texture, int level, boolean layered, int layer, int access,
                                          int format, CallbackInfo ci) {
            DlssRuntime.irisImage(texture, access);
        }
    }
}
