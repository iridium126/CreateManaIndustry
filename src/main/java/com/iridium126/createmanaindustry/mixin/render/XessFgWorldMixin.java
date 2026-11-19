package com.iridium126.createmanaindustry.mixin.render;

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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class XessFgWorldMixin {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void cmi$camera(LevelRenderer renderer, DeltaTracker delta, boolean outline, Camera camera,
                            GameRenderer game, LightTexture light, Matrix4f view, Matrix4f projection,
                            Operation<Void> original) {
        XessFgRuntime.worldView(camera, view, projection);
        original.call(renderer, delta, outline, camera, game, light, view, projection);
    }

    @Inject(method = "renderLevel", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderHand:Z"))
    private void cmi$worldDepth(DeltaTracker delta, CallbackInfo ci) {
        XessFgRuntime.vanillaDepth(Minecraft.getInstance().getMainRenderTarget());
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V", ordinal = 0))
    private void cmi$hudless(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        XessFgRuntime.hudless(Minecraft.getInstance().getMainRenderTarget());
    }
}
