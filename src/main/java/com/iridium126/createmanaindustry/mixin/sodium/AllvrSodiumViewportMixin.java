package com.iridium126.createmanaindustry.mixin.sodium;

import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.iridium126.createmanaindustry.client.dimension.render.sodium.AllvrSodiumBridge;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;

/** Gives Sodium's frustum viewport the same virtual Y as its section graph. */
@Mixin(value = Viewport.class, remap = false)
public abstract class AllvrSodiumViewportMixin {

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true,
        ordinal = 0)
    private static Vector3d cmi$virtualViewportCamera(Vector3d camera) {
        if (!AllvrSodiumBridge.active()) {
            return camera;
        }
        return new Vector3d(camera.x(),
            AllvrSodiumBridge.window().virtualCameraY(camera.y()), camera.z());
    }
}
