package com.iridium126.createmanaindustry.mixin.hexcasting;
import com.iridium126.createmanaindustry.client.particles.hex.HexPatternService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(PlayerRenderer.class)
public abstract class HexPlayerRenderContextMixin {
    @WrapMethod(method="render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
    private void cmi$hexPlayer(AbstractClientPlayer player,float yaw,float partial,PoseStack pose,MultiBufferSource buffer,int light,Operation<Void> original){HexPatternService.beginPlayerRender();try{original.call(player,yaw,partial,pose,buffer,light);}finally{HexPatternService.endPlayerRender();}}
}
