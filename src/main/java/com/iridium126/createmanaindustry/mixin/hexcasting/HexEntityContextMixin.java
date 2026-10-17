package com.iridium126.createmanaindustry.mixin.hexcasting;
import com.iridium126.createmanaindustry.CreateManaIndustry;
import com.iridium126.createmanaindustry.client.hex.pattern.HexPatternService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.*;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(LevelRenderer.class)
public abstract class HexEntityContextMixin {
    @WrapMethod(method="renderEntity")
    private void cmi$hexEntity(Entity entity,double x,double y,double z,float partial,PoseStack pose,MultiBufferSource buffer,Operation<Void> original){
        if(!(entity instanceof net.minecraft.world.entity.player.Player)){original.call(entity,x,y,z,partial,pose,buffer);return;}
        boolean shadow=CreateManaIndustry.IRIS_ACTIVE&&com.iridium126.createmanaindustry.client.particles.shaderpack.HexIrisHook.inShadow();
        var previous=HexPatternService.enterEntity(shadow);try{original.call(entity,x,y,z,partial,pose,buffer);}finally{HexPatternService.leaveEntity(previous);}
    }
}
