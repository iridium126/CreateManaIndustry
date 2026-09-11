package com.iridium126.createmanaindustry.mixin.voxy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.Minecraft;
import me.cortex.voxy.client.core.VoxyRenderSystem;

import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyClientIngest;
import com.iridium126.createmanaindustry.client.dimension.lod.voxy.AllvrVoxyYSlab;
import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;
import com.iridium126.createmanaindustry.dimension.AllvrDimensions;

/**
 * Camera Y remap for the allay dimension (voxy integration plan §7.5-1): the
 * viewport's camera Y is shifted into the player-centered virtual window so
 * the camera and the injected sections share one coordinate frame (the
 * sections' virtual cell Y comes from the same window). Every downstream
 * consumer of the viewport — frustum, Hi-Z, section translation — sees a
 * consistent camera, and the GPU never receives a ±30M float.
 * <p>
 * Both render entry paths (sodium without iris, iris captured viewport) call
 * {@code VoxyRenderSystem.setupViewport}. Patch the exact camera argument at
 * that common setup point, rather than relying on a generic method-variable
 * injection in {@code Viewport.setCamera}.
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public abstract class AllvrVoxyViewportMixin {

    @ModifyArg(
        method = "setupViewport(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;DDD)Lme/cortex/voxy/client/core/rendering/Viewport;",
        at = @At(
            value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/Viewport;setCamera(DDD)Lme/cortex/voxy/client/core/rendering/Viewport;",
            remap = false
        ),
        index = 1,
        remap = false
    )
    private double allvr$virtualCameraY(double cameraY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !AllvrDimensions.isAllay(mc.level)) {
            return cameraY;
        }
        if (AllvrSodiumBridge.voxyWindowCameraFrameActive()) {
            cameraY += AllvrSodiumBridge.window().originBlockY();
        }
        return AllvrVoxyYSlab.virtualCameraY(
            AllvrVoxyClientIngest.activeSlabId(mc.level), cameraY);
    }
}

