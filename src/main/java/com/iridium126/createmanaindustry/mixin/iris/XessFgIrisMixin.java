package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.allaystorm.rift.RiftRenderFrame;
import com.iridium126.createmanaindustry.client.render.shaderpack.ShaderPackSession;
import com.iridium126.createmanaindustry.client.xessfg.XessFgIrisOptions;
import com.iridium126.createmanaindustry.client.xessfg.XessFgCoordinates;
import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.irisshaders.iris.pathways.colorspace.ColorSpace;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
abstract class XessFgIrisMixin {
    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void cmi$beginXessFgIris(CallbackInfo ci) {
        if (XessFgRuntime.requested() && !RiftRenderFrame.auxiliary()) {
            var client = Minecraft.getInstance();
            var camera = client.gameRenderer.getMainCamera();
            var target = client.getMainRenderTarget();
            var pack = ShaderPackSession.current();
            XessFgRuntime.irisBegin(pack.generation(), cmi$coordinates(pack, client, camera, target.width, target.height),
                    camera, target.width, target.height, pack.terrainMotion());
        }
    }

    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"))
    private void cmi$captureXessFgIris(CallbackInfo ci) {
        if (!XessFgRuntime.requested() || RiftRenderFrame.auxiliary() || IrisVideoSettings.colorSpace != ColorSpace.SRGB) return;
        var pack = ShaderPackSession.current();
        var client = Minecraft.getInstance();
        if (client.level == null) return;
        var target = client.getMainRenderTarget();
        var state = CapturedRenderingState.INSTANCE;
        var camera = client.gameRenderer.getMainCamera();
        var coordinates = cmi$coordinates(pack, client, camera, target.width, target.height);
        if (coordinates == null) return;
        var targets = ((NativeSkyPipelineAccess) this).cmi$targets();
        int depth = client.options.getCameraType().isFirstPerson()
                ? targets.getDepthTextureNoHand().getTextureId() : targets.getDepthTexture();
        int handDepth = depth == targets.getDepthTexture() ? 0 : targets.getDepthTexture();
        XessFgRuntime.irisDepth(depth, handDepth, target.width, target.height, camera,
                state.getGbufferModelView(), state.getGbufferProjection(), coordinates);
    }

    @Unique
    private static XessFgCoordinates cmi$coordinates(ShaderPackSession pack, Minecraft client, Camera camera, int width, int height) {
        if (pack.xessFgPack() == null || pack.options() == null || client.level == null
                || IrisVideoSettings.colorSpace != ColorSpace.SRGB) return null;
        int fluid = switch (camera.getFluidInCamera()) {
            case WATER -> 1;
            case LAVA -> 2;
            case POWDER_SNOW -> 3;
            default -> 0;
        };
        return pack.xessFgPack().coordinates(new XessFgIrisOptions(pack.options()), SystemTimeUniforms.COUNTER.getAsInt(),
                width, height, fluid, client.level.getRainLevel(CapturedRenderingState.INSTANCE.getTickDelta()) > .02f,
                SystemTimeUniforms.TIMER.getFrameTimeCounter());
    }
}
