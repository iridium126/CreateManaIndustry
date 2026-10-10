package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class ParticleLevelFrameMixin {
    /** NeoForge dispatches AFTER_LEVEL after the LevelRenderer call returns. */
    @WrapMethod(method="renderLevel(Lnet/minecraft/client/DeltaTracker;)V")
    private void cmi$particleRenderScope(DeltaTracker delta, Operation<Void> original) {
        var engine = CMIParticleEngine.INSTANCE;
        engine.enterLevelFrame((Object)this == Minecraft.getInstance().gameRenderer);
        try {
            original.call(delta);
        } finally {
            engine.leaveLevelFrame();
        }
    }

    /** Capture the finished main-camera matrices without ending the enclosing frame. */
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void cmi$particleLevelFrame(LevelRenderer renderer,DeltaTracker delta,boolean outline,Camera camera,GameRenderer game,LightTexture light,Matrix4f view,Matrix4f projection,Operation<Void> original){
        CMIParticleEngine.INSTANCE.captureLevelFrame(camera,view,projection,delta);
        original.call(renderer,delta,outline,camera,game,light,view,projection);
    }
}
