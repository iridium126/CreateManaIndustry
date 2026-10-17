package com.iridium126.createmanaindustry.mixin.hexcasting;
import com.iridium126.createmanaindustry.client.hex.pattern.HexPatternService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec2;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;
@Mixin(targets="at.petrak.hexcasting.client.gui.GuiSpellcasting",remap=false)
public abstract class HexSpellUiMixin {
    @WrapMethod(method="render")
    private void cmi$hexUi(GuiGraphics graphics,int x,int y,float partial,Operation<Void> original){HexPatternService.beginUi(this,graphics);try{original.call(graphics,x,y,partial);}finally{HexPatternService.endUi();}}
    @WrapOperation(method="render",at=@At(value="INVOKE",target="Lat/petrak/hexcasting/client/render/RenderLib;drawPatternFromPoints(Lorg/joml/Matrix4f;Ljava/util/List;Ljava/util/Set;ZIIFFFD)V"))
    private void cmi$hexPattern(Matrix4f mat,List<Vec2> points,Set<Integer> duplicates,boolean last,int tail,int head,float flow,float readability,float length,double seed,Operation<Void> original){
        var saved=new Matrix4f(mat);
        if(!HexPatternService.uiPattern(mat,points,duplicates,last,tail,head,flow,readability,length,seed,()->original.call(saved,points,duplicates,last,tail,head,flow,readability,length,seed)))original.call(mat,points,duplicates,last,tail,head,flow,readability,length,seed);
    }
    @WrapOperation(method="render",at=@At(value="INVOKE",target="Lat/petrak/hexcasting/client/render/RenderLib;drawSpot(Lorg/joml/Matrix4f;Lnet/minecraft/world/phys/Vec2;FFFFF)V"))
    private void cmi$hexDot(Matrix4f mat,Vec2 point,float radius,float r,float g,float b,float a,Operation<Void> original){var saved=new Matrix4f(mat);if(!HexPatternService.uiDot(mat,point,radius,r,g,b,a,()->original.call(saved,point,radius,r,g,b,a)))original.call(mat,point,radius,r,g,b,a);}
    @Inject(method="render",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;enableDepthTest()V",ordinal=0))
    private void cmi$hexFlush(GuiGraphics graphics,int x,int y,float partial,CallbackInfo ci){HexPatternService.flushUi();}
}
