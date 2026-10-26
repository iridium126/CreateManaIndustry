package com.iridium126.createmanaindustry.mixin.allaystorm;

import com.iridium126.createmanaindustry.client.allaystorm.rift.AllaySkyProvider;
import com.iridium126.createmanaindustry.client.allaystorm.rift.VanillaRiftRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class AllayDimensionSkyMixin {
    @Inject(method="renderSky",at=@At("HEAD"),cancellable=true)
    private void cmi$sky(Matrix4f view,Matrix4f projection,float partial,Camera camera,boolean foggy,Runnable fog,CallbackInfo ci) {
        if (foggy || camera.getFluidInCamera()!=net.minecraft.world.level.material.FogType.NONE) return;
        if (VanillaRiftRenderer.dimension(view,projection,camera,partial)) { fog.run(); ci.cancel(); }
    }
    @Inject(method="renderClouds",at=@At("HEAD"),cancellable=true)
    private void cmi$clouds(PoseStack pose,Matrix4f view,Matrix4f projection,float partial,double x,double y,double z,CallbackInfo ci) {
        VanillaRiftRenderer.prepareClouds(view,partial);
        if (AllaySkyProvider.dimension()&&!VanillaRiftRenderer.nativePack()) ci.cancel();
    }
}
