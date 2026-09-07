package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.Minecraft;

import com.iridium126.createmanaindustry.client.dimension.lod.AllvrLodBackendManager;
import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyYWindow;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

/**
 * Camera Y remap for the allay dimension (voxy integration plan §7.5-1): the
 * viewport's camera Y is shifted into the player-centered virtual window so
 * the camera and the injected sections share one coordinate frame (the
 * sections' virtual cell Y comes from the same window). Every downstream
 * consumer of the viewport — frustum, Hi-Z, section translation — sees a
 * consistent camera, and the GPU never receives a ±30M float.
 * <p>
 * Both render entry paths (sodium without iris, iris captured viewport)
 * funnel through {@code Viewport.setCamera}, so this single patch covers
 * them. Only applies while the client level is the allay dimension; the
 * offset is zero everywhere else.
 */
@Mixin(value = me.cortex.voxy.client.core.rendering.Viewport.class, remap = false)
public abstract class AllvrVoxyViewportMixin {

    @ModifyVariable(method = "setCamera(DDD)Lme/cortex/voxy/client/core/rendering/Viewport;",
        at = @At("HEAD"), ordinal = 1, argsOnly = true, remap = false)
    private double allvr$virtualCameraY(double cameraY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !AllvrDimensions.isAllay(mc.level)) {
            return cameraY;
        }
        AllvrVoxyYWindow window = AllvrLodBackendManager.voxyWindow();
        return window == null ? cameraY : window.virtualCameraY(cameraY);
    }
}

