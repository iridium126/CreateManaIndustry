package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.client.xessfg.XessFgRuntime;
import com.iridium126.createmanaindustry.client.xessfg.XessFgFrameStats;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderSystem.class)
abstract class XessFgPresentMixin {
    @WrapOperation(method = "flipFrame", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"))
    private static void cmi$swapThroughXessFg(long window, Operation<Void> original) {
        if (XessFgRuntime.present(window)) return;
        long start = XessFgFrameStats.beginWgl();
        try {
            original.call(window);
        } finally {
            if (start != 0) XessFgFrameStats.endWgl(start);
        }
    }
}
