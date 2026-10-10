package com.iridium126.createmanaindustry.mixin.iris;

import com.iridium126.createmanaindustry.client.particles.engine.CMIParticleEngine;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.*;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class ParticleLevelFrameMixin {
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void cmi$particleLevelFrame(LevelRenderer renderer,DeltaTracker delta,boolean outline,Camera camera,GameRenderer game,LightTexture light,Matrix4f view,Matrix4f projection,Operation<Void> original){
        var engine=CMIParticleEngine.INSTANCE;engine.enterLevelFrame(camera,view,projection,delta);
        try{original.call(renderer,delta,outline,camera,game,light,view,projection);}finally{engine.leaveLevelFrame();}
    }
}
