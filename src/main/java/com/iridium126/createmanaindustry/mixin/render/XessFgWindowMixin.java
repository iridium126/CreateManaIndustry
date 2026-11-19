package com.iridium126.createmanaindustry.mixin.render;

import com.iridium126.createmanaindustry.infrastructure.config.ClientConfig;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Window.class)
abstract class XessFgWindowMixin {
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;

    @Unique private long cmi$borderlessMonitor;
    @Unique private int cmi$previousDecoration;
    @Unique private boolean cmi$previousMaximized;

    @WrapOperation(method = "setMode", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwGetWindowMonitor(J)J"))
    private long cmi$preserveWindowedPlacement(long window, Operation<Long> original) {
        return cmi$borderlessMonitor != 0 ? cmi$borderlessMonitor : original.call(window);
    }

    @WrapOperation(method = "setMode", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/glfw/GLFW;glfwSetWindowMonitor(JJIIIII)V"))
    private void cmi$applyBorderless(long window, long monitor, int x, int y, int width, int height, int refresh,
                                     Operation<Void> original) {
        var config = ClientConfig.xessFg();
        if (monitor != 0 && config.enabled() && config.surface().borderless() && Platform.get() == Platform.WINDOWS) {
            var desktopMode = GLFW.glfwGetVideoMode(monitor);
            if (desktopMode != null) {
                if (cmi$borderlessMonitor == 0) {
                    cmi$previousDecoration = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_DECORATED);
                    cmi$previousMaximized = GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE;
                    if (cmi$previousMaximized) GLFW.glfwRestoreWindow(window);
                    GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                }
                cmi$borderlessMonitor = monitor;
                try (var stack = MemoryStack.stackPush()) {
                    var left = stack.mallocInt(1);
                    var top = stack.mallocInt(1);
                    GLFW.glfwGetMonitorPos(monitor, left, top);
                    this.x = left.get(0);
                    this.y = top.get(0);
                    this.width = desktopMode.width();
                    this.height = desktopMode.height();
                    original.call(window, 0L, this.x, this.y, this.width, this.height, GLFW.GLFW_DONT_CARE);
                }
                return;
            }
        }
        boolean restoreMaximized = false;
        if (cmi$borderlessMonitor != 0) {
            cmi$borderlessMonitor = 0;
            GLFW.glfwSetWindowAttrib(window, GLFW.GLFW_DECORATED, cmi$previousDecoration);
            restoreMaximized = monitor == 0 && cmi$previousMaximized;
        }
        original.call(window, monitor, x, y, width, height, refresh);
        if (restoreMaximized) GLFW.glfwMaximizeWindow(window);
    }
}
