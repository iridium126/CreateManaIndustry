package com.iridium126.createmanaindustry.mixin.render;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.culling.Frustum;

/** Exposes the combined matrix used by the same Frustum passed to native renderers. */
@Mixin(Frustum.class)
public interface PackageCameraFrustumAccessor {
    @Accessor("matrix")
    Matrix4f createmanaindustry$getMatrix();
}
