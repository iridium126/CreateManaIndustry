package com.iridium126.createmanaindustry.mixin.hexcasting;
import com.iridium126.createmanaindustry.client.particles.hex.HexPatternService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.client.*;
import net.minecraft.client.renderer.*;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(GameRenderer.class)
public abstract class HexWorldFrameMixin {
    @WrapMethod(method="renderLevel(Lnet/minecraft/client/DeltaTracker;)V")
    private void cmi$hexFrame(DeltaTracker delta,Operation<Void> original){HexPatternService.beginFrame((Object)this==Minecraft.getInstance().gameRenderer);try{original.call(delta);}finally{HexPatternService.endFrame();}}
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void cmi$hexCamera(LevelRenderer renderer,DeltaTracker delta,boolean outline,Camera camera,GameRenderer game,LightTexture light,Matrix4f view,Matrix4f projection,Operation<Void> original){HexPatternService.capture(camera,view,projection,delta);original.call(renderer,delta,outline,camera,game,light,view,projection);}
}
