package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.dlss.DlssRuntime;
import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class XessFgWorldMixin {
    @Shadow private boolean renderHand;

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V"))
    private void cmi$renderDlssScene(GameRenderer game, DeltaTracker delta, Operation<Void> original) {
        if (DlssRuntime.requested()) DlssRuntime.renderWorld(Minecraft.getInstance(), () -> original.call(game, delta));
        else original.call(game, delta);
    }

    @WrapOperation(method = {"renderLevel", "renderItemInHand"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V"))
    private void cmi$jitterDlssScene(GameRenderer game, Matrix4f projection, Operation<Void> original) {
        original.call(game, DlssRuntime.jitterProjection(projection));
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void cmi$camera(LevelRenderer renderer, DeltaTracker delta, boolean outline, Camera camera,
                            GameRenderer game, LightTexture light, Matrix4f view, Matrix4f projection,
                            Operation<Void> original) {
        DlssRuntime.sceneView(camera, view, projection);
        XessFgRuntime.worldView(camera, view, projection);
        original.call(renderer, delta, outline, camera, game, light, view, DlssRuntime.jitterProjection(projection));
    }

    @Inject(method = "renderLevel", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderHand:Z"))
    private void cmi$worldDepth(DeltaTracker delta, CallbackInfo ci) {
        var minecraft = Minecraft.getInstance();
        var target = minecraft.getMainRenderTarget();
        boolean firstPersonHand = renderHand && minecraft.options.getCameraType().isFirstPerson();
        XessFgRuntime.beginSharedMotionDepth();
        try {
            DlssRuntime.worldDepth(target, firstPersonHand);
            XessFgRuntime.vanillaDepth(target, firstPersonHand);
        } finally {
            XessFgRuntime.endSharedMotionDepth();
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", ordinal = 0))
    private void cmi$hudless(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        XessFgRuntime.hudless(Minecraft.getInstance().getMainRenderTarget());
    }
}
